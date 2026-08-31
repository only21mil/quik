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

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactionProductionSeamSourceContractTest {
    @Test
    fun `production submission uses every provider route resolver and no conversation recipients`() {
        val source = source("data/src/main/java/com/moez/QKSMS/repository/MessageRepositoryImpl.kt")
        val submission = source.substring(
            source.indexOf("override fun sendReaction"),
            source.indexOf("override fun completeReaction"),
        )

        assertTrue(source.contains("smsReactionRouteResolver"))
        assertTrue(source.contains("oneToOneMmsReactionRouteResolver"))
        assertTrue(source.contains("groupReactionRouteResolver"))
        assertTrue(submission.contains("persistReactionProof"))
        assertTrue(submission.contains("reverifyReactionRoute"))
        assertFalse(submission.contains("Conversation"))
        assertFalse(submission.contains(".recipients"))
        assertFalse(submission.contains("Locale.getDefault"))
    }

    @Test
    fun `production gate and callback both recheck runtime authority`() {
        val module = source("presentation/src/main/java/com/moez/QKSMS/injection/AppModule.kt")
        val gate = source("data/src/main/java/com/moez/QKSMS/repository/RealmReactionPendingAttemptGate.kt")
        val repository = source("data/src/main/java/com/moez/QKSMS/repository/MessageRepositoryImpl.kt")
        val callback = repository.substring(
            repository.indexOf("override fun completeReaction"),
            repository.indexOf("private fun loadReactionSnapshot"),
        )

        assertTrue(module.contains("RealmReactionPendingAttemptGate"))
        assertFalse(module.contains("FailClosedReactionPendingAttemptGate"))
        assertTrue(gate.contains("runtimeAuthority.canSendReaction()"))
        assertTrue(callback.contains("reactionRuntimeAuthority.canSendReaction()"))
        assertTrue(callback.contains("reverifyPersistedReactionRoute"))
    }

    @Test
    fun `compose routing region comes from selected subscription`() {
        val source = source(
            "presentation/src/main/java/com/moez/QKSMS/feature/compose/ComposeViewModel.kt"
        )
        val reaction = source.substring(
            source.indexOf("view.reactionSelectedIntent"),
            source.indexOf("// Set the current conversation"),
        )

        assertTrue(reaction.contains("countryIso"))
        assertFalse(reaction.contains("Locale.getDefault"))
    }

    private fun source(relativePath: String): String {
        var root = File(requireNotNull(System.getProperty("user.dir")))
        while (!File(root, "settings.gradle").isFile) {
            root = requireNotNull(root.parentFile) { "Could not locate repository root" }
        }
        return File(root, relativePath).readText()
    }
}
