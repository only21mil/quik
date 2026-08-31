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

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactionTransportSourceContractTest {
    @Test
    fun `reaction repository path bypasses compose preferences and generic send`() {
        val source = source("repository/MessageRepositoryImpl.kt")
        val dedicated = source.substring(
            source.indexOf("override fun sendReaction"),
            source.indexOf("override fun completeReaction"),
        )
        listOf("prefs.", "sendNewMessages(", "sendMessage(", "explodeMessage(", "delayMs").forEach {
            forbidden -> assertFalse("Dedicated path contains $forbidden", dedicated.contains(forbidden))
        }
    }

    @Test
    fun `reaction sms is one raw segment and never multipart`() {
        val source = source("manager/QkTransaction.kt")
        val staging = source.substring(
            source.indexOf("fun stageReactionSms"),
            source.indexOf("fun stageReactionMms"),
        )
        val submission = source.substring(
            source.indexOf("private fun sendSinglePartSms"),
            source.indexOf("private data class ReactionSmsRow"),
        )
        val dedicated = staging + submission
        assertTrue(dedicated.contains("SmsMessage.calculateLength"))
        assertTrue(dedicated.contains("sendTextMessage"))
        assertFalse(dedicated.contains("sendMultipartTextMessage"))
    }

    @Test
    fun `pending send path never creates a badge`() {
        val source = source("repository/MessageRepositoryImpl.kt")
        val pending = source.substring(
            source.indexOf("override fun sendReaction"),
            source.indexOf("override fun completeReaction"),
        )
        assertFalse(pending.contains("EmojiReaction()"))
    }

    @Test
    fun `sent receiver derives pending intent uri from stable transport identity`() {
        val source = source("receiver/MessageSentReceiver.kt")
        assertTrue(source.contains("ProviderIdentity"))
        assertTrue(source.contains("intent.dataString != callbackUri"))
    }

    @Test
    fun `reconciliation never resubmits a durable attempt`() {
        val source = source("repository/ReactionAttemptReconciler.kt")
        assertFalse(source.contains("submitReaction"))
        assertFalse(source.contains("sendTextMessage"))
        assertFalse(source.contains("MESSAGE_TYPE_SENT"))
        assertFalse(source.contains("MESSAGE_BOX_SENT"))
        assertFalse(source.contains("attempt.state = ReactionAttempt.State.SENT.name"))
    }

    @Test
    fun `only callback completion writes sent state`() {
        val source = source("repository/MessageRepositoryImpl.kt")
        val completion = source.substring(
            source.indexOf("override fun completeReaction"),
            source.indexOf("private fun loadReactionSnapshot"),
        )
        assertTrue(completion.contains("attempt.state = ReactionAttempt.State.SENT.name"))
        assertEquals(
            1,
            Regex("attempt\\.state = ReactionAttempt\\.State\\.SENT\\.name")
                .findAll(source)
                .count(),
        )
    }

    @Test
    fun `callback route reverification rejects direction drift for every route kind`() {
        val source = source("repository/MessageRepositoryImpl.kt")
        val sendPath = source.substring(
            source.indexOf("override fun sendReaction"),
            source.indexOf("override fun completeReaction"),
        )
        val persistence = source.substring(
            source.indexOf("private fun persistReactionProof"),
            source.indexOf("private fun verifyPersistedReactionProof"),
        )
        val callbackRouteCheck = source.substring(
            source.indexOf("private fun reverifyPersistedReactionRoute"),
            source.indexOf("private fun sha256ReactionBody"),
        )
        val routeProofs = source.substring(
            source.indexOf("private sealed class ReactionRoute"),
            source.indexOf("private sealed class ReactionRouteResolution"),
        )

        assertTrue(persistence.contains("attempt.routeDirection = route.direction"))
        val proofPersistence = sendPath.indexOf("persistReactionProof(")
        val providerMutation = sendPath.indexOf("stage = {")
        assertTrue(proofPersistence in 0 until providerMutation)
        assertTrue(
            callbackRouteCheck.contains(
                "ReactionRouteDirectionPolicy.matches(attempt.routeDirection, current.direction)"
            )
        )
        val directionBoundRoutes = Regex(
            "data class (Sms|OneToOneMms|GroupMms)[\\s\\S]*?" +
                "override val direction = proof\\.direction\\.name",
        ).findAll(routeProofs).map { match -> match.groupValues[1] }.toSet()
        assertEquals(setOf("Sms", "OneToOneMms", "GroupMms"), directionBoundRoutes)
    }

    @Test
    fun `emoji persistence has no key manager dependency`() {
        val source = source("repository/EmojiReactionRepositoryImpl.kt")
        assertFalse(source.contains("KeyManager"))
        assertTrue(source.contains("EmojiReaction.idForReactionMessage"))
        assertTrue(source.contains("EmojiReaction.fromMeForCarrier"))
    }

    @Test
    fun `subscription lookup requires phone permission and fails closed if it is revoked`() {
        val source = source("repository/ProviderBackedGroupMmsReactionRouteResolver.kt")
        val lookup = source.substring(
            source.indexOf("override fun loadActiveSubscriptions"),
            source.indexOf("private companion object"),
        )

        assertTrue(lookup.contains("ContextCompat.checkSelfPermission"))
        assertTrue(lookup.contains("Manifest.permission.READ_PHONE_STATE"))
        assertTrue(lookup.contains("PackageManager.PERMISSION_GRANTED"))
        assertTrue(lookup.contains("catch (_: SecurityException)"))
        assertTrue(
            lookup.indexOf("ContextCompat.checkSelfPermission") <
                lookup.indexOf("activeSubscriptionInfoList"),
        )
        assertTrue(
            lookup.indexOf("activeSubscriptionInfoList") <
                lookup.indexOf("catch (_: SecurityException)"),
        )
        assertEquals(2, lookup.split("emptyList()").size - 1)
    }

    private fun source(relativePath: String): String {
        val root = File(requireNotNull(System.getProperty("user.dir")))
        val candidates = listOf(
            File(root, "src/main/java/com/moez/QKSMS/$relativePath"),
            File(root, "data/src/main/java/com/moez/QKSMS/$relativePath"),
        )
        val file = requireNotNull(candidates.firstOrNull(File::isFile)) {
            "Could not find source $relativePath from $root"
        }
        return file.readText()
    }
}
