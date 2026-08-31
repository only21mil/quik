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

import dev.octoshrimpy.quik.model.ReactionAttempt
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactionAttemptGatePolicyTest {
    @Test
    fun `only exact positive provider targets may reserve`() {
        assertTrue(ReactionAttemptGatePolicy.validTarget(ReactionPendingTarget(1, 2, 0)))
        assertFalse(ReactionAttemptGatePolicy.validTarget(ReactionPendingTarget(0, 2, 0)))
        assertFalse(ReactionAttemptGatePolicy.validTarget(ReactionPendingTarget(1, 0, 0)))
        assertFalse(ReactionAttemptGatePolicy.validTarget(ReactionPendingTarget(1, 2, -1)))
    }

    @Test
    fun `every nonterminal transport state blocks a restart reservation`() {
        assertTrue(
            ReactionAttemptGatePolicy.blockingStates.toSet().containsAll(
                setOf(
                    ReactionAttempt.State.PREPARING.name,
                    ReactionAttempt.State.HANDOFF.name,
                    ReactionAttempt.State.SUBMITTED.name,
                    ReactionAttempt.State.HANDOFF_FAILED.name,
                    ReactionAttempt.State.QUARANTINED.name,
                )
            )
        )
        assertFalse(ReactionAttempt.State.SENT.name in ReactionAttemptGatePolicy.blockingStates)
        assertFalse(ReactionAttempt.State.FAILED.name in ReactionAttemptGatePolicy.blockingStates)
    }

    @Test
    fun `release is limited to an untouched owned reservation`() {
        assertTrue(ReactionAttemptGatePolicy.canRelease(ReactionAttempt()))

        val proved = ReactionAttempt().apply {
            routeKind = "ONE_TO_ONE_SMS"
            routeDirection = "INCOMING"
            routeFingerprint = "proof"
            targetBodyFingerprint = "target"
        }
        assertFalse(ReactionAttemptGatePolicy.canRelease(proved))

        val directionProved = ReactionAttempt().apply { routeDirection = "OUTGOING" }
        assertFalse(ReactionAttemptGatePolicy.canRelease(directionProved))

        val staged = ReactionAttempt().apply {
            transportKey = "stage-v1:sms:1:2:0"
        }
        assertFalse(ReactionAttemptGatePolicy.canRelease(staged))

        ReactionAttemptGatePolicy.blockingStates
            .filterNot { state -> state == ReactionAttempt.State.PREPARING.name }
            .forEach { state ->
                assertFalse(
                    ReactionAttemptGatePolicy.canRelease(ReactionAttempt().apply { this.state = state })
                )
            }
    }
}
