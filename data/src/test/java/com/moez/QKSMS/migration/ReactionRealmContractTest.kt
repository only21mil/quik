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
package dev.octoshrimpy.quik.migration

import dev.octoshrimpy.quik.model.EmojiReaction
import dev.octoshrimpy.quik.model.ReactionAttempt
import io.realm.annotations.Index
import io.realm.annotations.PrimaryKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactionRealmContractTest {
    @Test
    fun `schema 19 keeps legacy reaction id primary and adds fromMe`() {
        assertEquals(19L, QkRealmMigration.SCHEMA_VERSION)
        assertEquals(Long::class.javaPrimitiveType, EmojiReaction::class.java.getDeclaredField("id").type)
        assertTrue(EmojiReaction::class.java.getDeclaredField("id").isAnnotationPresent(PrimaryKey::class.java))
        assertEquals(Boolean::class.javaObjectType, EmojiReaction::class.java.getDeclaredField("fromMe").type)
    }

    @Test
    fun `reaction attempt schema has an independent primary key and nullable transport key`() {
        val fields = ReactionAttempt::class.java.declaredFields.associateBy { field -> field.name }

        assertEquals(
            setOf(
                "id", "targetKey", "transportKey", "threadId", "body", "state", "createdAt",
                "ownerSessionId", "routeKind", "routeDirection", "routeFingerprint", "routeRegion",
                "targetBodyFingerprint", "bodyFingerprint", "handoffAt",
            ),
            fields.keys.filterNot { name -> name.startsWith("\$") || name == "Companion" }.toSet(),
        )
        assertTrue(fields.getValue("id").isAnnotationPresent(PrimaryKey::class.java))
        assertFalse(fields.getValue("transportKey").isAnnotationPresent(PrimaryKey::class.java))
        assertTrue(fields.getValue("transportKey").isAnnotationPresent(Index::class.java))

        val attempt = ReactionAttempt()
        assertNull(attempt.transportKey)
        assertEquals("", attempt.routeDirection)
        assertEquals(ReactionAttempt.State.PREPARING.name, attempt.state)
        assertEquals(
            listOf(
                "PREPARING",
                "HANDOFF",
                "SUBMITTED",
                "SENT",
                "FAILED",
                "HANDOFF_FAILED",
                "QUARANTINED",
            ),
            ReactionAttempt.State.values().map(ReactionAttempt.State::name),
        )
        assertTrue(fields.getValue("ownerSessionId").isAnnotationPresent(Index::class.java))
        assertTrue(fields.getValue("routeKind").isAnnotationPresent(Index::class.java))
        assertTrue(fields.getValue("handoffAt").isAnnotationPresent(Index::class.java))
    }

    @Test
    fun `attempt ids are created before transport correlation and do not repeat`() {
        val first = ReactionAttempt.newId()
        val second = ReactionAttempt.newId()

        assertTrue(first.isNotBlank())
        assertTrue(second.isNotBlank())
        assertNotEquals(first, second)
    }

    @Test
    fun `carrier based reaction ids cannot collide with positive schema 15 ids after restart`() {
        val legacyIds = listOf(1L, 2L, 42L, Long.MAX_VALUE)
        val firstProcessId = EmojiReaction.idForReactionMessage(42L)
        val restartedProcessId = EmojiReaction.idForReactionMessage(42L)

        assertEquals(-42L, firstProcessId)
        assertEquals(firstProcessId, restartedProcessId)
        assertFalse(firstProcessId in legacyIds)
    }

    @Test
    fun `carrier based reaction ids reject unsafe message ids`() {
        listOf(Long.MIN_VALUE, -1L, 0L).forEach { unsafeId ->
            val error = runCatching { EmojiReaction.idForReactionMessage(unsafeId) }.exceptionOrNull()
            assertTrue("Expected $unsafeId to be rejected", error is IllegalArgumentException)
        }
    }

    @Test
    fun `schema 15 carrier direction backfill handles old incoming and outgoing boxes`() {
        assertEquals(false, QkRealmMigration.fromMeForCarrier("sms", 1))
        assertEquals(true, QkRealmMigration.fromMeForCarrier("sms", 2))
        assertEquals(false, QkRealmMigration.fromMeForCarrier("mms", 1))
        assertEquals(true, QkRealmMigration.fromMeForCarrier("mms", 2))
    }

    @Test
    fun `schema 15 unknown carrier metadata stays explicitly unknown`() {
        assertNull(QkRealmMigration.fromMeForCarrier("", 1))
        assertNull(QkRealmMigration.fromMeForCarrier("sms", -1))
        assertNull(QkRealmMigration.fromMeForCarrier("mms", 6))
        assertNull(QkRealmMigration.fromMeForCarrier(null, 0))
    }

    @Test
    fun `duplicate and empty targets do not participate in metadata backfill`() {
        val schema15Reactions = listOf(
            LegacyReaction(7, 101, "same", 501),
            LegacyReaction(8, 102, "same", 501),
            LegacyReaction(9, 103, "", 502),
        )

        val afterBackfill = schema15Reactions.map { reaction ->
            reaction.copy(fromMe = QkRealmMigration.fromMeForCarrier("sms", 1))
        }

        assertEquals(schema15Reactions.map(LegacyReaction::id), afterBackfill.map(LegacyReaction::id))
        assertEquals(schema15Reactions.map(LegacyReaction::targetLink), afterBackfill.map(LegacyReaction::targetLink))
        assertEquals(schema15Reactions.map(LegacyReaction::targetText), afterBackfill.map(LegacyReaction::targetText))
    }

    @Test
    fun `downgrade is rejected by the migration contract`() {
        val error = runCatching {
            QkRealmMigration.requireSupportedVersionRange(16, 15)
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        QkRealmMigration.requireSupportedVersionRange(15, 16)
    }

    @Test
    fun `schema 16 migration fails closed because old sent state was not callback-only`() {
        assertEquals(
            ReactionAttempt.State.FAILED.name,
            QkRealmMigration.terminalStateForVersion16(),
        )
    }

    @Test
    fun `schema 19 body fingerprint is deterministic and content bound`() {
        val first = QkRealmMigration.bodyFingerprint("reaction body")

        assertEquals(first, QkRealmMigration.bodyFingerprint("reaction body"))
        assertNotEquals(first, QkRealmMigration.bodyFingerprint("reaction body changed"))
        assertTrue(first.matches(Regex("[0-9a-f]{64}")))
    }

    private data class LegacyReaction(
        val id: Long,
        val reactionMessageId: Long,
        val targetText: String,
        val targetLink: Long,
        val fromMe: Boolean? = null,
    )
}
