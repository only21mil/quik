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

import java.util.UUID

data class ReactionPendingTarget(
    val messageId: Long,
    val threadId: Long,
    val subscriptionId: Int,
)

data class ReactionAttemptReservation(
    val attemptId: String,
    val ownerSessionId: String,
)

/** Atomically reserves one provider-backed target until its callback becomes terminal. */
interface ReactionPendingAttemptGate {
    fun tryAcquire(target: ReactionPendingTarget): Lease?

    interface Lease {
        val reservation: ReactionAttemptReservation

        fun markSubmitted(attemptId: String)

        /** Removes only an untouched PREPARING reservation owned by this process. */
        fun releaseRejected()
    }
}

/** Process-local gate used only by presentation concurrency tests. */
class InMemoryReactionPendingAttemptGate : ReactionPendingAttemptGate {
    private val ownerSessionId = UUID.randomUUID().toString()
    private val reservations = mutableMapOf<ReactionPendingTarget, Reservation>()

    override fun tryAcquire(target: ReactionPendingTarget): ReactionPendingAttemptGate.Lease? =
        synchronized(reservations) {
            if (reservations.containsKey(target)) return@synchronized null
            Reservation(
                target = target,
                reservation = ReactionAttemptReservation(
                    attemptId = UUID.randomUUID().toString(),
                    ownerSessionId = ownerSessionId,
                ),
            ).also { reservation -> reservations[target] = reservation }
        }

    private inner class Reservation(
        private val target: ReactionPendingTarget,
        override val reservation: ReactionAttemptReservation,
    ) : ReactionPendingAttemptGate.Lease {
        private var submitted = false

        override fun markSubmitted(attemptId: String) {
            synchronized(reservations) {
                if (reservations[target] === this && reservation.attemptId == attemptId) {
                    submitted = true
                }
            }
        }

        override fun releaseRejected() {
            synchronized(reservations) {
                if (reservations[target] === this && !submitted) {
                    reservations.remove(target)
                }
            }
        }
    }
}
