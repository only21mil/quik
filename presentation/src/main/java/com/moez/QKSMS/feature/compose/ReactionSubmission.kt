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
package dev.octoshrimpy.quik.feature.compose

import dev.octoshrimpy.quik.interactor.SendReaction
import dev.octoshrimpy.quik.repository.ReactionPendingAttemptGate
import dev.octoshrimpy.quik.repository.ReactionPendingTarget
import dev.octoshrimpy.quik.repository.SendReactionResult
import io.reactivex.Flowable
import java.util.Locale

internal data class ReactionSubmissionSelection(
    val messageId: Long,
    val emoji: String,
    val navigationThreadId: Long,
    val stateThreadId: Long,
    val selectedSubscriptionId: Int?,
    val region: String,
)

internal data class ReactionNavigationSnapshot(
    val conversationThreadId: Long,
    val stateThreadId: Long,
    val selectedSubscriptionId: Int?,
)

internal data class ReactionSubmissionSnapshot(
    val selection: ReactionSubmissionSelection,
    val refreshedConversationId: Long?,
    val refreshedTargetId: Long?,
    val targetThreadId: Long?,
    val targetSubscriptionId: Int?,
    val activeSubscriptionIds: Set<Int>,
    val region: String,
    val isDefaultSms: Boolean,
    val hasSendSms: Boolean,
    val targetIsReaction: Boolean,
    val targetIsFailed: Boolean,
    val targetHasText: Boolean,
    val targetHasNonTextParts: Boolean,
    val localReactionEmojis: Set<String>,
)

internal data class ReactionSubmissionFacts(
    val selectedMessageId: Long,
    val selectedEmoji: String,
    val navigationThreadId: Long,
    val stateThreadId: Long,
    val currentNavigationThreadId: Long,
    val currentStateThreadId: Long,
    val refreshedConversationId: Long?,
    val refreshedTargetId: Long?,
    val targetThreadId: Long?,
    val targetSubscriptionId: Int?,
    val selectedSubscriptionId: Int?,
    val currentSelectedSubscriptionId: Int?,
    val activeSubscriptionIds: Set<Int>,
    val selectedRegion: String,
    val region: String,
    val isDefaultSms: Boolean,
    val hasSendSms: Boolean,
    val targetIsReaction: Boolean,
    val targetIsFailed: Boolean,
    val targetHasText: Boolean,
    val targetHasNonTextParts: Boolean,
    val localReactionEmojis: Set<String>,
)

internal enum class ReactionSubmissionRejection {
    DEFAULT_SMS_REQUIRED,
    SEND_SMS_REQUIRED,
    UNSUPPORTED_REACTION,
    NAVIGATION_CHANGED,
    TARGET_CHANGED,
    SUBSCRIPTION_REQUIRED,
    SUBSCRIPTION_CHANGED,
    REGION_UNAVAILABLE,
    REGION_CHANGED,
    INELIGIBLE_TARGET,
    ATTEMPT_PENDING,
    TRANSPORT_REJECTED,
}

internal sealed class ReactionSubmissionDecision {
    data class Send(val params: SendReaction.Params) : ReactionSubmissionDecision()
    data class Reject(val reason: ReactionSubmissionRejection) : ReactionSubmissionDecision()
}

internal object ReactionSubmissionPolicy {
    fun decide(facts: ReactionSubmissionFacts): ReactionSubmissionDecision {
        if (!facts.isDefaultSms) {
            return ReactionSubmissionDecision.Reject(
                ReactionSubmissionRejection.DEFAULT_SMS_REQUIRED
            )
        }
        if (!facts.hasSendSms) {
            return ReactionSubmissionDecision.Reject(
                ReactionSubmissionRejection.SEND_SMS_REQUIRED
            )
        }

        val reaction = when (facts.selectedEmoji) {
            "❤️" -> SendReaction.Reaction.HEART
            "👍" -> SendReaction.Reaction.LIKE
            "👎" -> SendReaction.Reaction.DISLIKE
            "😂" -> SendReaction.Reaction.LAUGH
            "‼️" -> SendReaction.Reaction.EMPHASIS
            "❓" -> SendReaction.Reaction.QUESTION
            else -> return ReactionSubmissionDecision.Reject(
                ReactionSubmissionRejection.UNSUPPORTED_REACTION
            )
        }

        if (
            facts.navigationThreadId <= 0L ||
            facts.stateThreadId != facts.navigationThreadId ||
            facts.currentNavigationThreadId != facts.navigationThreadId ||
            facts.currentStateThreadId != facts.navigationThreadId ||
            facts.refreshedConversationId != facts.navigationThreadId
        ) {
            return ReactionSubmissionDecision.Reject(
                ReactionSubmissionRejection.NAVIGATION_CHANGED
            )
        }
        if (
            facts.selectedMessageId <= 0L ||
            facts.refreshedTargetId != facts.selectedMessageId ||
            facts.targetThreadId != facts.navigationThreadId
        ) {
            return ReactionSubmissionDecision.Reject(
                ReactionSubmissionRejection.TARGET_CHANGED
            )
        }

        if (facts.currentSelectedSubscriptionId != facts.selectedSubscriptionId) {
            return ReactionSubmissionDecision.Reject(
                ReactionSubmissionRejection.SUBSCRIPTION_CHANGED
            )
        }
        val subscriptionId = facts.currentSelectedSubscriptionId
            ?: facts.activeSubscriptionIds.singleOrNull()
            ?: return ReactionSubmissionDecision.Reject(
                ReactionSubmissionRejection.SUBSCRIPTION_REQUIRED
            )
        if (
            subscriptionId < 0 ||
            subscriptionId !in facts.activeSubscriptionIds ||
            facts.targetSubscriptionId != subscriptionId
        ) {
            return ReactionSubmissionDecision.Reject(
                ReactionSubmissionRejection.SUBSCRIPTION_CHANGED
            )
        }

        val selectedRegion = facts.selectedRegion.normalizedRegion()
        val region = facts.region.normalizedRegion()
        if (selectedRegion == null || region == null) {
            return ReactionSubmissionDecision.Reject(
                ReactionSubmissionRejection.REGION_UNAVAILABLE
            )
        }
        if (selectedRegion != region) {
            return ReactionSubmissionDecision.Reject(
                ReactionSubmissionRejection.REGION_CHANGED
            )
        }
        if (
            facts.targetIsReaction ||
            facts.targetIsFailed ||
            !facts.targetHasText ||
            facts.targetHasNonTextParts
        ) {
            return ReactionSubmissionDecision.Reject(
                ReactionSubmissionRejection.INELIGIBLE_TARGET
            )
        }

        val operation = if (facts.selectedEmoji in facts.localReactionEmojis) {
            SendReaction.ReactionOperation.REMOVE
        } else {
            SendReaction.ReactionOperation.ADD
        }
        return ReactionSubmissionDecision.Send(
            SendReaction.Params(
                targetMessageId = facts.selectedMessageId,
                expectedThreadId = facts.navigationThreadId,
                expectedSubscriptionId = subscriptionId,
                region = region,
                operation = operation,
                reaction = reaction,
            )
        )
    }
}

private fun String.normalizedRegion(): String? =
    trim().uppercase(Locale.ROOT).takeIf { region -> region.matches(Regex("[A-Z]{2}")) }

internal sealed class ReactionDispatchResult {
    data class Submitted(val attemptId: String) : ReactionDispatchResult()

    data class Rejected(
        val reason: ReactionSubmissionRejection,
        val transportReason: SendReactionResult.Rejection? = null,
    ) : ReactionDispatchResult()
}

internal data class ReactionSubmissionErrorLog(
    val throwableClass: String,
    val causeClass: String?,
) {
    companion object {
        fun from(error: Throwable) = ReactionSubmissionErrorLog(
            throwableClass = error.javaClass.name,
            causeClass = error.cause?.javaClass?.name,
        )
    }
}

internal class ReactionRequestDispatcher(
    private val submit: (SendReaction.Params) -> Flowable<SendReactionResult>,
    private val logError: (ReactionSubmissionErrorLog) -> Unit = {},
) {
    constructor(
        sendReaction: SendReaction,
        logError: (ReactionSubmissionErrorLog) -> Unit = {},
    ) : this(sendReaction::buildObservable, logError)

    fun dispatch(decision: ReactionSubmissionDecision): Flowable<ReactionDispatchResult> =
        when (decision) {
            is ReactionSubmissionDecision.Reject -> Flowable.just(
                ReactionDispatchResult.Rejected(decision.reason)
            )
            is ReactionSubmissionDecision.Send -> Flowable.defer { submit(decision.params) }
                .map { result ->
                    when (result) {
                        is SendReactionResult.Submitted ->
                            ReactionDispatchResult.Submitted(result.attemptId)
                        is SendReactionResult.Rejected -> ReactionDispatchResult.Rejected(
                            reason = ReactionSubmissionRejection.TRANSPORT_REJECTED,
                            transportReason = result.reason,
                        )
                    }
                }
                .doOnError { error -> logError(ReactionSubmissionErrorLog.from(error)) }
                .onErrorReturn {
                    ReactionDispatchResult.Rejected(
                        ReactionSubmissionRejection.TRANSPORT_REJECTED
                    )
                }
        }
}

internal class ReactionSubmissionCoordinator(
    private val dispatcher: ReactionRequestDispatcher,
    private val pendingAttemptGate: ReactionPendingAttemptGate,
) {
    fun dispatch(decision: ReactionSubmissionDecision): Flowable<ReactionDispatchResult> {
        if (decision is ReactionSubmissionDecision.Reject) {
            return dispatcher.dispatch(decision)
        }

        decision as ReactionSubmissionDecision.Send
        val target = ReactionPendingTarget(
            messageId = decision.params.targetMessageId,
            threadId = decision.params.expectedThreadId,
            subscriptionId = decision.params.expectedSubscriptionId,
        )
        return Flowable.defer {
            val lease = pendingAttemptGate.tryAcquire(target)
                ?: return@defer Flowable.just(
                    ReactionDispatchResult.Rejected(
                        ReactionSubmissionRejection.ATTEMPT_PENDING
                    )
                )

            dispatcher.dispatch(
                decision.copy(
                    params = decision.params.copy(reservation = lease.reservation)
                )
            )
                .doOnNext { result ->
                    when (result) {
                        is ReactionDispatchResult.Submitted -> lease.markSubmitted(result.attemptId)
                        is ReactionDispatchResult.Rejected -> lease.releaseRejected()
                    }
                }
        }
    }
}
