/*
 * Copyright (C) 2026
 *
 * This file is part of QUIK.
 *
 * QUIK is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package dev.octoshrimpy.quik.feature.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReactionUiPolicyTest {
    private val eligible = ReactionMessageCandidate(
        id = 42L,
        isReaction = false,
        isFailed = false,
        hasText = true,
        hasNonTextParts = false,
    )

    @Test
    fun `one eligible visible selection returns its stable id`() {
        assertEquals(
            42L,
            ReactionUiPolicy.targetId(listOf(42L), listOf(eligible), true, true),
        )
    }

    @Test
    fun `multiple selection and stale selection are rejected`() {
        assertNull(ReactionUiPolicy.targetId(listOf(42L, 43L), listOf(eligible), true, true))
        assertNull(ReactionUiPolicy.targetId(listOf(41L), listOf(eligible), true, true))
    }

    @Test
    fun `default role and send permission are required`() {
        assertNull(ReactionUiPolicy.targetId(listOf(42L), listOf(eligible), false, true))
        assertNull(ReactionUiPolicy.targetId(listOf(42L), listOf(eligible), true, false))
    }

    @Test
    fun `reaction failed blank and media messages are rejected`() {
        val ineligible = listOf(
            eligible.copy(isReaction = true),
            eligible.copy(isFailed = true),
            eligible.copy(hasText = false),
            eligible.copy(hasNonTextParts = true),
        )

        ineligible.forEach { message ->
            assertNull(ReactionUiPolicy.targetId(listOf(42L), listOf(message), true, true))
        }
    }

    @Test
    fun `picker exposes exactly six distinct reactions`() {
        assertEquals(6, ReactionUiPolicy.options.size)
        assertEquals(6, ReactionUiPolicy.options.map { it.emoji }.distinct().size)
        assertEquals(6, ReactionUiPolicy.options.map { it.label }.distinct().size)
    }

    @Test
    fun `intent gate consumes a current choice once`() {
        val gate = ReactionIntentGate()
        gate.open(42L)

        assertEquals(42L to "👍", gate.consume(42L, "👍"))
        assertNull(gate.consume(42L, "👍"))
    }

    @Test
    fun `intent gate rejects stale ids and unsupported emoji`() {
        val gate = ReactionIntentGate()
        gate.open(42L)

        assertNull(gate.consume(41L, "👍"))
        assertNull(gate.consume(42L, "🚀"))
    }
}
