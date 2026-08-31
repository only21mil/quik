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

import dev.octoshrimpy.quik.common.util.extensions.now
import dev.octoshrimpy.quik.manager.ReactionRuntimeAuthority
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.model.ReactionAttempt
import io.realm.Realm
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

internal interface ReactionAttemptReservationStore {
    fun tryReserve(
        target: ReactionPendingTarget,
        ownerSessionId: String,
    ): ReactionAttemptReservation?

    fun markSubmitted(reservation: ReactionAttemptReservation, attemptId: String)

    fun releaseRejected(reservation: ReactionAttemptReservation)
}

internal class RealmReactionAttemptReservationStore : ReactionAttemptReservationStore {
    override fun tryReserve(
        target: ReactionPendingTarget,
        ownerSessionId: String,
    ): ReactionAttemptReservation? {
        var reservation: ReactionAttemptReservation? = null
        Realm.getDefaultInstance().use { realm ->
            realm.refresh()
            realm.executeTransaction { transactionRealm ->
                val message = transactionRealm.where(Message::class.java)
                    .equalTo("id", target.messageId)
                    .equalTo("threadId", target.threadId)
                    .equalTo("subId", target.subscriptionId)
                    .equalTo("isEmojiReaction", false)
                    .findFirst()
                    ?: return@executeTransaction
                val targetKey = ReactionTransportPolicy.ProviderIdentity.from(message)
                    ?.encode()
                    ?: return@executeTransaction
                val pending = transactionRealm.where(ReactionAttempt::class.java)
                    .equalTo("targetKey", targetKey)
                    .`in`("state", ReactionAttemptGatePolicy.blockingStates)
                    .findFirst()
                if (pending != null) return@executeTransaction

                val attemptId = ReactionAttempt.newId()
                transactionRealm.insert(
                    ReactionAttempt().apply {
                        id = attemptId
                        this.targetKey = targetKey
                        threadId = target.threadId
                        state = ReactionAttempt.State.PREPARING.name
                        createdAt = now()
                        this.ownerSessionId = ownerSessionId
                    }
                )
                reservation = ReactionAttemptReservation(attemptId, ownerSessionId)
            }
        }
        return reservation
    }

    override fun markSubmitted(
        reservation: ReactionAttemptReservation,
        attemptId: String,
    ) {
        if (attemptId != reservation.attemptId) return
        Realm.getDefaultInstance().use { realm ->
            realm.refresh()
            realm.where(ReactionAttempt::class.java)
                .equalTo("id", reservation.attemptId)
                .equalTo("ownerSessionId", reservation.ownerSessionId)
                .findFirst()
                ?.state
        }
    }

    override fun releaseRejected(reservation: ReactionAttemptReservation) {
        Realm.getDefaultInstance().use { realm ->
            realm.refresh()
            realm.executeTransaction { transactionRealm ->
                transactionRealm.where(ReactionAttempt::class.java)
                    .equalTo("id", reservation.attemptId)
                    .equalTo("ownerSessionId", reservation.ownerSessionId)
                    .findFirst()
                    ?.takeIf(ReactionAttemptGatePolicy::canRelease)
                    ?.deleteFromRealm()
            }
        }
    }
}

internal object ReactionAttemptGatePolicy {
    val blockingStates = arrayOf(
        ReactionAttempt.State.PREPARING.name,
        ReactionAttempt.State.HANDOFF.name,
        ReactionAttempt.State.SUBMITTED.name,
        ReactionAttempt.State.HANDOFF_FAILED.name,
        ReactionAttempt.State.QUARANTINED.name,
    )

    fun validTarget(target: ReactionPendingTarget): Boolean =
        target.messageId > 0L && target.threadId > 0L && target.subscriptionId >= 0

    fun canRelease(attempt: ReactionAttempt): Boolean =
        attempt.state == ReactionAttempt.State.PREPARING.name &&
            attempt.transportKey == null &&
            attempt.routeKind.isEmpty() &&
            attempt.routeDirection.isEmpty() &&
            attempt.routeFingerprint.isEmpty() &&
            attempt.routeRegion.isEmpty() &&
            attempt.targetBodyFingerprint.isEmpty() &&
            attempt.body.isEmpty() &&
            attempt.bodyFingerprint.isEmpty()
}

/** Realm-backed reservation keyed by the target's stable provider identity. */
@Singleton
class RealmReactionPendingAttemptGate internal constructor(
    private val runtimeAuthority: ReactionRuntimeAuthority,
    private val store: ReactionAttemptReservationStore,
    private val ownerSessionId: String,
) : ReactionPendingAttemptGate {
    @Inject constructor(runtimeAuthority: ReactionRuntimeAuthority) : this(
        runtimeAuthority = runtimeAuthority,
        store = RealmReactionAttemptReservationStore(),
        ownerSessionId = UUID.randomUUID().toString(),
    )

    override fun tryAcquire(target: ReactionPendingTarget): ReactionPendingAttemptGate.Lease? {
        if (!ReactionAttemptGatePolicy.validTarget(target) || !runtimeAuthority.canSendReaction()) {
            return null
        }

        return store.tryReserve(target, ownerSessionId)?.let(::RealmLease)
    }

    private inner class RealmLease(
        override val reservation: ReactionAttemptReservation,
    ) : ReactionPendingAttemptGate.Lease {
        override fun markSubmitted(attemptId: String) {
            store.markSubmitted(reservation, attemptId)
        }

        override fun releaseRejected() {
            store.releaseRejected(reservation)
        }
    }
}
