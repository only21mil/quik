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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactionRouteDirectionPolicyTest {
    @Test
    fun `callback rejects one to one SMS direction drift`() {
        assertDirectionDriftRejected(
            OneToOneSmsReactionDirection.INCOMING.name,
            OneToOneSmsReactionDirection.OUTGOING.name,
        )
    }

    @Test
    fun `callback rejects one to one MMS direction drift`() {
        assertDirectionDriftRejected(
            OneToOneMmsReactionDirection.INCOMING.name,
            OneToOneMmsReactionDirection.OUTGOING.name,
        )
    }

    @Test
    fun `callback rejects group MMS direction drift`() {
        assertDirectionDriftRejected(
            GroupMmsReactionDirection.INCOMING.name,
            GroupMmsReactionDirection.OUTGOING.name,
        )
    }

    @Test
    fun `missing persisted direction always fails closed`() {
        assertFalse(ReactionRouteDirectionPolicy.matches("", "INCOMING"))
    }

    private fun assertDirectionDriftRejected(persisted: String, drifted: String) {
        assertTrue(ReactionRouteDirectionPolicy.matches(persisted, persisted))
        assertFalse(ReactionRouteDirectionPolicy.matches(persisted, drifted))
    }
}
