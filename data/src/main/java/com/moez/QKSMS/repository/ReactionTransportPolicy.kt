/*
 * Copyright (C) 2026 Quik contributors
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package dev.octoshrimpy.quik.repository

import android.content.ContentUris
import android.net.Uri
import android.provider.Telephony
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.model.ReactionAttempt

internal object ReactionTransportPolicy {
    enum class Route {
        ONE_TO_ONE_SMS,
        TRUE_GROUP_MMS,
    }

    enum class RouteRejection {
        NO_RECIPIENT,
        FAN_OUT_FORBIDDEN,
        GROUP_ROUTE_UNAVAILABLE,
    }

    sealed class RouteDecision {
        data class Selected(val route: Route) : RouteDecision()
        data class Rejected(val reason: RouteRejection) : RouteDecision()
    }

    fun selectRoute(
        recipientCount: Int,
        targetIsMms: Boolean,
        providerProvedGroup: Boolean,
        sendAsGroup: Boolean = true,
    ): RouteDecision = when {
        recipientCount <= 0 -> RouteDecision.Rejected(RouteRejection.NO_RECIPIENT)
        recipientCount == 1 -> RouteDecision.Selected(Route.ONE_TO_ONE_SMS)
        !sendAsGroup -> RouteDecision.Rejected(RouteRejection.FAN_OUT_FORBIDDEN)
        !targetIsMms -> RouteDecision.Rejected(RouteRejection.FAN_OUT_FORBIDDEN)
        !providerProvedGroup -> RouteDecision.Rejected(RouteRejection.GROUP_ROUTE_UNAVAILABLE)
        else -> RouteDecision.Selected(Route.TRUE_GROUP_MMS)
    }

    data class ProviderIdentity(
        val type: String,
        val contentId: Long,
        val threadId: Long,
        val subId: Int,
    ) {
        fun encode(): String = "v2:$type:$contentId:$threadId:$subId"

        fun toUri(): Uri = ContentUris.withAppendedId(
            if (type == Message.TYPE_MMS) Telephony.Mms.CONTENT_URI else Telephony.Sms.CONTENT_URI,
            contentId,
        )

        companion object {
            fun from(message: Message): ProviderIdentity? {
                if (
                    message.type !in setOf(Message.TYPE_SMS, Message.TYPE_MMS) ||
                    message.contentId <= 0L || message.threadId <= 0L || message.subId < 0
                ) {
                    return null
                }
                return ProviderIdentity(
                    type = message.type,
                    contentId = message.contentId,
                    threadId = message.threadId,
                    subId = message.subId,
                )
            }

            fun decode(value: String): ProviderIdentity? {
                val fields = value.split(':')
                if (fields.size != 5 || fields[0] != "v2") return null
                val type = fields[1].takeIf { it == Message.TYPE_SMS || it == Message.TYPE_MMS }
                    ?: return null
                val contentId = fields[2].toLongOrNull()?.takeIf { it > 0L } ?: return null
                val threadId = fields[3].toLongOrNull()?.takeIf { it > 0L } ?: return null
                val subId = fields[4].toIntOrNull()?.takeIf { it >= 0 } ?: return null
                return ProviderIdentity(type, contentId, threadId, subId)
            }
        }
    }

    /**
     * Identity persisted before the provider insert. The exact provider row id does not exist at
     * that point, so the staging timestamp is shared with the provider row. This marker is only a
     * quarantine key. It can never authorize submission or a successful reaction.
     */
    data class StagingIdentity(
        val type: String,
        val stagedAt: Long,
        val threadId: Long,
        val subId: Int,
    ) {
        fun encode(): String = "stage-v1:$type:$stagedAt:$threadId:$subId"

        companion object {
            fun decode(value: String): StagingIdentity? {
                val fields = value.split(':')
                if (fields.size != 5 || fields[0] != "stage-v1") return null
                val type = fields[1].takeIf { it == Message.TYPE_SMS || it == Message.TYPE_MMS }
                    ?: return null
                val stagedAt = fields[2].toLongOrNull()?.takeIf { it > 0L } ?: return null
                val threadId = fields[3].toLongOrNull()?.takeIf { it > 0L } ?: return null
                val subId = fields[4].toIntOrNull()?.takeIf { it >= 0 } ?: return null
                return StagingIdentity(type, stagedAt, threadId, subId)
            }
        }
    }

    /** Provider locator written by schema 16 before stable v2 identities were introduced. */
    data class LegacyProviderIdentity(
        val type: String,
        val contentId: Long,
    ) {
        companion object {
            private val pattern = Regex("^content://(sms|mms)/(\\d+)$")

            fun decode(value: String): LegacyProviderIdentity? {
                val match = pattern.matchEntire(value) ?: return null
                val contentId = match.groupValues[2].toLongOrNull()?.takeIf { it > 0L }
                    ?: return null
                return LegacyProviderIdentity(match.groupValues[1], contentId)
            }
        }
    }

    data class CallbackFacts(
        val currentState: String,
        val expectedTransportKey: String?,
        val receivedTransportKey: String,
        val expectedIdentity: ProviderIdentity?,
        val carrierThreadId: Long,
        val carrierSubscriptionId: Int,
        val targetThreadId: Long,
        val successful: Boolean,
    )

    enum class CallbackDecision {
        COMMIT_SENT,
        COMMIT_FAILED,
        ALREADY_TERMINAL,
        CORRELATION_MISMATCH,
    }

    sealed class CallbackSettlement {
        /** The exact provider target on which the reaction badge may be persisted. */
        data class CommitSent(val target: ProviderIdentity) : CallbackSettlement()

        object CommitFailed : CallbackSettlement()
        object AlreadyTerminal : CallbackSettlement()
        object CorrelationMismatch : CallbackSettlement()
    }

    fun settleCallback(facts: CallbackFacts): CallbackSettlement =
        when (decideCallback(facts)) {
            CallbackDecision.COMMIT_SENT -> facts.expectedIdentity
                ?.let(CallbackSettlement::CommitSent)
                ?: CallbackSettlement.CorrelationMismatch
            CallbackDecision.COMMIT_FAILED -> CallbackSettlement.CommitFailed
            CallbackDecision.ALREADY_TERMINAL -> CallbackSettlement.AlreadyTerminal
            CallbackDecision.CORRELATION_MISMATCH -> CallbackSettlement.CorrelationMismatch
        }

    fun decideCallback(facts: CallbackFacts): CallbackDecision {
        if (
            facts.currentState == ReactionAttempt.State.SENT.name ||
            facts.currentState == ReactionAttempt.State.FAILED.name
        ) {
            return CallbackDecision.ALREADY_TERMINAL
        }
        val identity = facts.expectedIdentity
            ?: return CallbackDecision.CORRELATION_MISMATCH
        if (
            facts.currentState !in setOf(
                ReactionAttempt.State.HANDOFF.name,
                ReactionAttempt.State.SUBMITTED.name,
                ReactionAttempt.State.HANDOFF_FAILED.name,
                ReactionAttempt.State.QUARANTINED.name,
            ) ||
            facts.expectedTransportKey != facts.receivedTransportKey ||
            facts.carrierThreadId != identity.threadId ||
            facts.targetThreadId != identity.threadId ||
            facts.carrierSubscriptionId != identity.subId
        ) {
            return CallbackDecision.CORRELATION_MISMATCH
        }
        return if (facts.successful) {
            CallbackDecision.COMMIT_SENT
        } else {
            CallbackDecision.COMMIT_FAILED
        }
    }
}

internal class ReactionSubmissionCoordinator {
    enum class Failure {
        PREPARATION,
        STAGING,
        SYNC,
        SUBMISSION,
    }

    sealed class Outcome {
        object Submitted : Outcome()
        data class Failed(val failure: Failure) : Outcome()
    }

    data class Hooks(
        val prepare: () -> Unit,
        val stage: () -> String?,
        val syncHiddenAndMarkHandoff: (String) -> String?,
        val submit: (providerUri: String, transportKey: String) -> Boolean,
        val markSubmitted: (transportKey: String) -> Unit,
        val discard: (String) -> Unit,
        val markFailed: (String?) -> Unit,
    )

    fun run(hooks: Hooks): Outcome {
        try {
            hooks.prepare()
        } catch (_: Exception) {
            return Outcome.Failed(Failure.PREPARATION)
        }

        val providerUri = try {
            hooks.stage()
        } catch (_: Exception) {
            null
        }
        if (providerUri == null) {
            hooks.markFailed(null)
            return Outcome.Failed(Failure.STAGING)
        }

        val stableTransportKey = try {
            hooks.syncHiddenAndMarkHandoff(providerUri)
        } catch (_: Exception) {
            null
        }
        if (stableTransportKey == null) {
            hooks.discard(providerUri)
            hooks.markFailed(null)
            return Outcome.Failed(Failure.SYNC)
        }

        val submitted = try {
            hooks.submit(providerUri, stableTransportKey)
        } catch (_: Exception) {
            false
        }
        if (!submitted) {
            hooks.markFailed(stableTransportKey)
            return Outcome.Failed(Failure.SUBMISSION)
        }
        try {
            hooks.markSubmitted(stableTransportKey)
        } catch (_: Exception) {
            // The platform accepted the carrier. Keep the durable HANDOFF marker so startup can
            // bound the uncertain window without resubmitting or inferring delivery.
        }
        return Outcome.Submitted
    }
}
