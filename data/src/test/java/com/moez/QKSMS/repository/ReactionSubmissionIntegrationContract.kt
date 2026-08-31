/*
 * Copyright (C) 2026 Quik contributors
 *
 * This file is part of QUIK.
 *
 * QUIK is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package dev.octoshrimpy.quik.repository

import dev.octoshrimpy.quik.manager.PermissionBackedReactionRuntimeAuthority
import dev.octoshrimpy.quik.manager.PermissionManager
import dev.octoshrimpy.quik.manager.ReactionRuntimeAuthority
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.model.ReactionAttempt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration checks over the production route, reservation, orchestration, authority, and
 * callback-settlement seams. The only test double below is the persistence adapter behind the
 * production durable gate; it retains records across gate instances and owns no transport or badge
 * counters.
 */
class ReactionSubmissionIntegrationContract {

    @Test
    fun `provider route is reverified before staging and rejects target drift`() {
        val provider = ExactSmsProvider()
        val resolver = OneToOneSmsReactionRouteResolution(provider, UsPhoneCanonicalizer)
        val resolved = resolver.resolve(routeRequest())
            as OneToOneSmsReactionRouteResult.Resolved

        assertEquals(TARGET_ID, resolved.route.providerSmsId)
        assertEquals(THREAD_ID, resolved.route.threadId)
        assertEquals(REMOTE_E164, resolved.route.remoteRecipientE164)

        provider.target = provider.target.copy(body = "changed after selection")

        assertEquals(
            OneToOneSmsReactionRouteResult.Rejected(
                OneToOneSmsReactionRouteRejection.ROUTE_CHANGED
            ),
            resolver.reverify(resolved.route),
        )
    }

    @Test
    fun `transport key mismatch cannot authorize a badge commit`() {
        val target = providerIdentity(contentId = TARGET_ID)
        val settlement = ReactionTransportPolicy.settleCallback(
            callbackFacts(
                target = target,
                expectedTransportKey = providerIdentity(contentId = CARRIER_ID).encode(),
                receivedTransportKey = providerIdentity(contentId = CARRIER_ID + 1).encode(),
                successful = true,
            )
        )

        assertEquals(
            ReactionTransportPolicy.CallbackSettlement.CorrelationMismatch,
            settlement,
        )
        assertFalse(settlement is ReactionTransportPolicy.CallbackSettlement.CommitSent)
    }

    @Test
    fun `restart between staging and submission preserves durable exclusion`() {
        val provider = ExactSmsProvider()
        val route = (
            OneToOneSmsReactionRouteResolution(provider, UsPhoneCanonicalizer)
                .resolve(routeRequest()) as OneToOneSmsReactionRouteResult.Resolved
            ).route
        val store = PersistentTestReservationStore(
            pendingTarget = ReactionPendingTarget(TARGET_ID, route.threadId, route.subscriptionId),
            targetKey = providerIdentity(contentId = route.providerSmsId).encode(),
        )
        val originalGate = gate(store, ownerSessionId = "process-before-restart")
        val lease = requireNotNull(originalGate.tryAcquire(store.pendingTarget))
        val transportKey = providerIdentity(contentId = CARRIER_ID).encode()

        val outcome = ReactionSubmissionCoordinator().run(
            ReactionSubmissionCoordinator.Hooks(
                prepare = {},
                stage = { "content://sms/$CARRIER_ID" },
                syncHiddenAndMarkHandoff = {
                    store.markHandoff(lease.reservation, transportKey)
                    transportKey
                },
                submit = { _, exactTransportKey ->
                    assertEquals(transportKey, exactTransportKey)
                    val restartedGate = gate(store, ownerSessionId = "process-after-restart")
                    assertNull(restartedGate.tryAcquire(store.pendingTarget))
                    assertEquals(
                        ReactionAttempt.State.HANDOFF.name,
                        store.requireAttempt(lease.reservation).state,
                    )
                    true
                },
                markSubmitted = { exactTransportKey ->
                    assertEquals(transportKey, exactTransportKey)
                    store.markSubmitted(
                        lease.reservation,
                        lease.reservation.attemptId,
                    )
                },
                discard = {},
                markFailed = {},
            )
        )

        assertTrue(outcome is ReactionSubmissionCoordinator.Outcome.Submitted)
        assertEquals(
            ReactionAttempt.State.SUBMITTED.name,
            store.requireAttempt(lease.reservation).state,
        )
        assertNull(gate(store, "second-restart").tryAcquire(store.pendingTarget))
    }

    @Test
    fun `default sms role and SEND SMS authority are independently required`() {
        assertTrue(authority(isDefaultSms = true, hasSendSms = true).canSendReaction())
        assertFalse(authority(isDefaultSms = false, hasSendSms = true).canSendReaction())
        assertFalse(authority(isDefaultSms = true, hasSendSms = false).canSendReaction())
        assertFalse(authority(isDefaultSms = false, hasSendSms = false).canSendReaction())
    }

    @Test
    fun `badge settlement is target specific and remains absent before exact success`() {
        val target = providerIdentity(contentId = TARGET_ID)
        val otherTarget = providerIdentity(contentId = TARGET_ID + 1)
        val transportKey = providerIdentity(contentId = CARRIER_ID).encode()

        val beforeSuccess = listOf(
            callbackFacts(target, transportKey, transportKey, successful = false),
            callbackFacts(
                target,
                transportKey,
                providerIdentity(contentId = CARRIER_ID + 1).encode(),
                successful = true,
            ),
        ).map(ReactionTransportPolicy::settleCallback)

        assertTrue(
            beforeSuccess.none { it is ReactionTransportPolicy.CallbackSettlement.CommitSent }
        )

        val success = ReactionTransportPolicy.settleCallback(
            callbackFacts(target, transportKey, transportKey, successful = true)
        )
        assertEquals(
            ReactionTransportPolicy.CallbackSettlement.CommitSent(target),
            success,
        )
        assertFalse(success == ReactionTransportPolicy.CallbackSettlement.CommitSent(otherTarget))
    }

    private fun gate(
        store: ReactionAttemptReservationStore,
        ownerSessionId: String,
    ) = RealmReactionPendingAttemptGate(
        runtimeAuthority = object : ReactionRuntimeAuthority {
            override fun canSendReaction(): Boolean = true
        },
        store = store,
        ownerSessionId = ownerSessionId,
    )

    private fun authority(
        isDefaultSms: Boolean,
        hasSendSms: Boolean,
    ) = PermissionBackedReactionRuntimeAuthority(
        object : PermissionManager {
            override fun isDefaultSms(): Boolean = isDefaultSms
            override fun hasReadSms(): Boolean = false
            override fun hasSendSms(): Boolean = hasSendSms
            override fun hasContacts(): Boolean = false
            override fun hasNotifications(): Boolean = false
            override fun hasPhone(): Boolean = false
            override fun hasStorage(): Boolean = false
            override fun hasRecordAudio(): Boolean = false
            override fun hasExactAlarms(): Boolean = false
        }
    )

    private fun routeRequest() = OneToOneSmsReactionRouteRequest(
        providerSmsId = TARGET_ID,
        navigationThreadId = THREAD_ID,
        activeSubscriptionId = SUBSCRIPTION_ID,
        region = "US",
        targetBody = TARGET_BODY,
    )

    private fun callbackFacts(
        target: ReactionTransportPolicy.ProviderIdentity,
        expectedTransportKey: String,
        receivedTransportKey: String,
        successful: Boolean,
    ) = ReactionTransportPolicy.CallbackFacts(
        currentState = ReactionAttempt.State.SUBMITTED.name,
        expectedTransportKey = expectedTransportKey,
        receivedTransportKey = receivedTransportKey,
        expectedIdentity = target,
        carrierThreadId = target.threadId,
        carrierSubscriptionId = target.subId,
        targetThreadId = target.threadId,
        successful = successful,
    )

    private fun providerIdentity(
        contentId: Long,
    ) = ReactionTransportPolicy.ProviderIdentity(
        type = Message.TYPE_SMS,
        contentId = contentId,
        threadId = THREAD_ID,
        subId = SUBSCRIPTION_ID,
    )

    private class PersistentTestReservationStore(
        val pendingTarget: ReactionPendingTarget,
        private val targetKey: String,
    ) : ReactionAttemptReservationStore {
        private val attempts = linkedMapOf<String, StoredAttempt>()

        override fun tryReserve(
            target: ReactionPendingTarget,
            ownerSessionId: String,
        ): ReactionAttemptReservation? {
            if (target != pendingTarget) return null
            if (attempts.values.any {
                    it.targetKey == targetKey && it.state in ReactionAttemptGatePolicy.blockingStates
                }
            ) {
                return null
            }
            val reservation = ReactionAttemptReservation(
                attemptId = "attempt-${attempts.size + 1}",
                ownerSessionId = ownerSessionId,
            )
            attempts[reservation.attemptId] = StoredAttempt(
                reservation = reservation,
                targetKey = targetKey,
                state = ReactionAttempt.State.PREPARING.name,
            )
            return reservation
        }

        override fun markSubmitted(
            reservation: ReactionAttemptReservation,
            attemptId: String,
        ) {
            require(attemptId == reservation.attemptId)
            requireAttempt(reservation).state = ReactionAttempt.State.SUBMITTED.name
        }

        override fun releaseRejected(reservation: ReactionAttemptReservation) {
            val attempt = requireAttempt(reservation)
            if (attempt.state == ReactionAttempt.State.PREPARING.name) {
                attempts.remove(reservation.attemptId)
            }
        }

        fun markHandoff(
            reservation: ReactionAttemptReservation,
            transportKey: String,
        ) {
            val attempt = requireAttempt(reservation)
            require(attempt.state == ReactionAttempt.State.PREPARING.name)
            attempt.transportKey = transportKey
            attempt.state = ReactionAttempt.State.HANDOFF.name
        }

        fun requireAttempt(reservation: ReactionAttemptReservation): StoredAttempt =
            requireNotNull(attempts[reservation.attemptId])
                .also { require(it.reservation == reservation) }
    }

    private data class StoredAttempt(
        val reservation: ReactionAttemptReservation,
        val targetKey: String,
        var state: String,
        var transportKey: String? = null,
    )

    private class ExactSmsProvider : OneToOneSmsReactionRouteProvider {
        var target = ProviderSmsTarget(
            id = TARGET_ID,
            threadId = THREAD_ID,
            subscriptionId = SUBSCRIPTION_ID,
            address = REMOTE_E164,
            body = TARGET_BODY,
            direction = ProviderSmsDirection.INCOMING,
        )

        override fun loadTarget(providerSmsId: Long): List<ProviderSmsTarget> =
            listOf(target).filter { it.id == providerSmsId }

        override fun loadThread(threadId: Long): List<ProviderSmsThread> =
            listOf(ProviderSmsThread(THREAD_ID, RECIPIENT_ID.toString()))
                .filter { it.id == threadId }

        override fun loadCanonicalAddresses(
            recipientIds: List<Long>,
        ): List<ProviderSmsCanonicalAddress> =
            listOf(ProviderSmsCanonicalAddress(RECIPIENT_ID, REMOTE_E164))
                .filter { it.id in recipientIds }

        override fun loadActiveSubscriptions(): List<ProviderSmsSubscription> =
            listOf(ProviderSmsSubscription(SUBSCRIPTION_ID, SELF_E164, "US"))
    }

    private object UsPhoneCanonicalizer : OneToOneSmsPhoneCanonicalizer {
        override fun supportsRegion(region: String): Boolean = region == "US"

        override fun toE164(address: String, region: String): String? = address
            .filter(Char::isDigit)
            .let { digits ->
                when (digits.length) {
                    10 -> "+1$digits"
                    11 -> digits.takeIf { it.startsWith("1") }?.let { "+$it" }
                    else -> null
                }
            }
    }

    private companion object {
        const val TARGET_ID = 700L
        const val CARRIER_ID = 701L
        const val THREAD_ID = 80L
        const val SUBSCRIPTION_ID = 4
        const val RECIPIENT_ID = 11L
        const val TARGET_BODY = "provider target body"
        const val REMOTE_E164 = "+13125550101"
        const val SELF_E164 = "+13125550100"
    }
}
