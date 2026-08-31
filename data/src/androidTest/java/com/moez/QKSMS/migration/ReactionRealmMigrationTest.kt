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
package dev.octoshrimpy.quik.migration

import android.content.Context
import android.content.SharedPreferences
import androidx.test.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import com.f2prateek.rx.preferences2.RxSharedPreferences
import dev.octoshrimpy.quik.manager.PermissionManagerImpl
import dev.octoshrimpy.quik.mapper.CursorToContactImpl
import dev.octoshrimpy.quik.model.EmojiReaction
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.model.ReactionAttempt
import dev.octoshrimpy.quik.repository.ReactionTransportPolicy
import dev.octoshrimpy.quik.util.Preferences
import io.realm.DynamicRealm
import io.realm.DynamicRealmObject
import io.realm.Realm
import io.realm.RealmConfiguration
import io.realm.RealmMigration
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReactionRealmMigrationTest {
    private lateinit var context: Context
    private val configurations = mutableListOf<RealmConfiguration>()
    private val preferenceStores = mutableListOf<SharedPreferences>()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        Realm.init(context)
    }

    @After
    fun tearDown() {
        configurations.asReversed().forEach { configuration ->
            Realm.deleteRealm(configuration)
        }
        preferenceStores.forEach { preferences -> preferences.edit().clear().commit() }
    }

    @Test
    fun version15RealPredecessorSurvivesFullChainAndCreatesFailClosedAttemptSchema() {
        val name = "reaction-v15-real-predecessor.realm"
        val carrier = identity(Message.TYPE_SMS, 401, 31, 1)
        val legacyBody = "schema-15 message survives"

        createVersion15Fixture(name) { realm ->
            createMessage(realm, realmId = 10, identity = carrier).apply {
                setInt("boxId", 2)
                setString("body", legacyBody)
                setLong("date", 1_723_000_000_000)
            }
            realm.createObject("EmojiReaction", 20L).apply {
                setLong("reactionMessageId", 10)
                setString("senderAddress", "+15551234567")
                setString("emoji", "👍")
                setString("originalMessageText", "legacy target")
                setLong("threadId", 31)
            }
        }

        openMigratedRealm(name).use { realm ->
            val message = requireNotNull(
                realm.where(Message::class.java).equalTo("id", 10L).findFirst(),
            )
            assertEquals(carrier.type, message.type)
            assertEquals(carrier.contentId, message.contentId)
            assertEquals(carrier.threadId, message.threadId)
            assertEquals(carrier.subId, message.subId)
            assertEquals(2, message.boxId)
            assertEquals(legacyBody, message.body)
            assertEquals(1_723_000_000_000, message.date)

            val reaction = requireNotNull(
                realm.where(EmojiReaction::class.java).equalTo("id", 20L).findFirst(),
            )
            assertEquals(true, reaction.fromMe)
            assertEquals(0L, realm.where(ReactionAttempt::class.java).count())

            realm.executeTransaction { transaction ->
                transaction.createObject(ReactionAttempt::class.java, "attempt-after-v15").apply {
                    targetKey = carrier.encode()
                    threadId = carrier.threadId
                    body = "post-migration attempt"
                    state = ReactionAttempt.State.FAILED.name
                    createdAt = 1_723_000_000_111
                }
            }

            val attempt = requireNotNull(
                realm.where(ReactionAttempt::class.java)
                    .equalTo("id", "attempt-after-v15")
                    .findFirst(),
            )
            assertEquals(ReactionAttempt.State.FAILED.name, attempt.state)
            assertNull(attempt.transportKey)
            assertEquals(0L, attempt.handoffAt)
            assertEquals("", attempt.ownerSessionId)
            assertEquals("", attempt.routeKind)
            assertEquals("", attempt.routeDirection)
            assertEquals("", attempt.routeFingerprint)
            assertEquals("", attempt.routeRegion)
            assertEquals("", attempt.targetBodyFingerprint)
            assertEquals("", attempt.bodyFingerprint)
        }
    }

    @Test
    fun version17AttemptRetainsIdentityAndFailsClosedAtCurrentSchema() {
        val name = "reaction-v17-${UUID.randomUUID()}.realm"
        val target = identity(Message.TYPE_SMS, 501, 41, 2)
        val carrier = identity(Message.TYPE_SMS, 601, 41, 2)
        val body = "Liked “provider target”"

        createLegacyFixture(name, 17) { realm ->
            realm.createObject("ReactionAttempt", "attempt-v17").apply {
                setString("targetKey", target.encode())
                setString("transportKey", carrier.encode())
                setLong("threadId", 41)
                setString("body", body)
                setString("state", ReactionAttempt.State.FAILED.name)
                setLong("createdAt", 1_725_000_000_000)
                setLong("handoffAt", 1_725_000_000_111)
            }
        }

        openMigratedRealm(name).use { realm ->
            val attempt = requireNotNull(
                realm.where(ReactionAttempt::class.java)
                    .equalTo("id", "attempt-v17")
                    .findFirst(),
            )

            assertEquals(target.encode(), attempt.targetKey)
            assertEquals(carrier.encode(), attempt.transportKey)
            assertEquals(41L, attempt.threadId)
            assertEquals(body, attempt.body)
            assertEquals(1_725_000_000_000, attempt.createdAt)
            assertEquals(1_725_000_000_111, attempt.handoffAt)
            assertEquals(ReactionAttempt.State.FAILED.name, attempt.state)
            assertEquals("", attempt.ownerSessionId)
            assertEquals("", attempt.routeKind)
            assertEquals("", attempt.routeDirection)
            assertEquals("", attempt.routeFingerprint)
            assertEquals("", attempt.routeRegion)
            assertEquals("", attempt.targetBodyFingerprint)
            assertEquals(QkRealmMigration.bodyFingerprint(body), attempt.bodyFingerprint)
        }
    }

    @Test
    fun version16RunsLegacyNormalizationBeforeFailClosedCurrentFields() {
        val name = "reaction-v16-${UUID.randomUUID()}.realm"
        val target = identity(Message.TYPE_SMS, 501, 41, 2)
        val carrier = identity(Message.TYPE_SMS, 601, 41, 2)
        val body = "Loved “legacy target”"

        createLegacyFixture(name, 16) { realm ->
            createMessage(realm, realmId = 11, identity = target)
            createMessage(realm, realmId = 12, identity = carrier)
            realm.createObject("ReactionAttempt", "attempt-v16").apply {
                setString("targetKey", "v1:11:41:2")
                setString("transportKey", "content://sms/601")
                setLong("threadId", 41)
                setString("body", body)
                setString("state", ReactionAttempt.State.SENT.name)
                setLong("createdAt", 1_724_000_000_000)
            }
        }

        openMigratedRealm(name).use { realm ->
            val attempt = requireNotNull(
                realm.where(ReactionAttempt::class.java)
                    .equalTo("id", "attempt-v16")
                    .findFirst(),
            )

            assertEquals(target.encode(), attempt.targetKey)
            assertEquals(carrier.encode(), attempt.transportKey)
            assertEquals(ReactionAttempt.State.FAILED.name, attempt.state)
            assertEquals(0L, attempt.handoffAt)
            assertEquals("", attempt.ownerSessionId)
            assertEquals("", attempt.routeKind)
            assertEquals("", attempt.routeDirection)
            assertEquals("", attempt.routeFingerprint)
            assertEquals("", attempt.routeRegion)
            assertEquals("", attempt.targetBodyFingerprint)
            assertEquals(QkRealmMigration.bodyFingerprint(body), attempt.bodyFingerprint)
        }
    }

    @Test
    fun version18AttemptRetainsDurableProofButDefaultsDirectionFailClosed() {
        val name = "reaction-v18-${UUID.randomUUID()}.realm"
        val target = identity(Message.TYPE_MMS, 701, 51, 3)
        val carrier = identity(Message.TYPE_MMS, 801, 51, 3)
        val body = "Laughed at “provider target”"
        val bodyFingerprint = QkRealmMigration.bodyFingerprint(body)

        createLegacyFixture(name, 18) { realm ->
            realm.createObject("ReactionAttempt", "attempt-v18").apply {
                setString("targetKey", target.encode())
                setString("transportKey", carrier.encode())
                setLong("threadId", 51)
                setString("body", body)
                setString("state", ReactionAttempt.State.HANDOFF.name)
                setLong("createdAt", 1_726_000_000_000)
                setString("ownerSessionId", "session-v18")
                setString("routeKind", "ONE_TO_ONE_MMS")
                setString("routeFingerprint", "route-proof-v18")
                setString("routeRegion", "US")
                setString("targetBodyFingerprint", "target-body-proof-v18")
                setString("bodyFingerprint", bodyFingerprint)
                setLong("handoffAt", 1_726_000_000_111)
            }
        }

        openMigratedRealm(name).use { realm ->
            val attempt = requireNotNull(
                realm.where(ReactionAttempt::class.java)
                    .equalTo("id", "attempt-v18")
                    .findFirst(),
            )

            assertEquals(target.encode(), attempt.targetKey)
            assertEquals(carrier.encode(), attempt.transportKey)
            assertEquals(51L, attempt.threadId)
            assertEquals(body, attempt.body)
            assertEquals(1_726_000_000_000, attempt.createdAt)
            assertEquals("session-v18", attempt.ownerSessionId)
            assertEquals("ONE_TO_ONE_MMS", attempt.routeKind)
            assertEquals("route-proof-v18", attempt.routeFingerprint)
            assertEquals("US", attempt.routeRegion)
            assertEquals("target-body-proof-v18", attempt.targetBodyFingerprint)
            assertEquals(bodyFingerprint, attempt.bodyFingerprint)
            assertEquals(1_726_000_000_111, attempt.handoffAt)
            assertEquals("", attempt.routeDirection)
            assertEquals(ReactionAttempt.State.FAILED.name, attempt.state)
        }
    }

    private fun createLegacyFixture(
        name: String,
        version: Long,
        seed: (DynamicRealm) -> Unit,
    ) {
        require(version in 16L..18L)

        // Realm creates a new file with the current model schema. A one-time fixture migration
        // removes fields that did not exist at the requested historical version, leaving a real
        // on-disk Realm file for the production migration to consume.
        Realm.getInstance(configuration(name, version - 1)).close()

        val fixtureConfiguration = configuration(
            name = name,
            version = version,
            migration = RealmMigration { realm, _, _ ->
                val attempts = requireNotNull(realm.schema.get("ReactionAttempt"))
                attempts.removeField("routeDirection")
                if (version < 18L) VERSION_18_FIELDS.forEach(attempts::removeField)
                if (version == 16L) attempts.removeField("handoffAt")
            },
        )
        DynamicRealm.getInstance(fixtureConfiguration).use { realm ->
            realm.executeTransaction(seed)
        }
    }

    private fun createVersion15Fixture(
        name: String,
        seed: (DynamicRealm) -> Unit,
    ) {
        val startingConfiguration = configuration(name, 14)
        Realm.deleteRealm(startingConfiguration)
        Realm.getInstance(startingConfiguration).close()

        val fixtureConfiguration = configuration(
            name = name,
            version = 15,
            migration = RealmMigration { realm, oldVersion, newVersion ->
                require(oldVersion == 14L)
                require(newVersion == 15L)
                requireNotNull(realm.schema.get("EmojiReaction")).removeField("fromMe")
                requireNotNull(realm.schema.get("ReactionAttempt"))
                realm.schema.remove("ReactionAttempt")
            },
        )
        DynamicRealm.getInstance(fixtureConfiguration).use { realm ->
            require(realm.schema.get("ReactionAttempt") == null)
            require(requireNotNull(realm.schema.get("EmojiReaction")).hasField("fromMe").not())
            realm.executeTransaction(seed)
        }
    }

    private fun openMigratedRealm(name: String): Realm {
        val configuration = configuration(
            name = name,
            version = QkRealmMigration.SCHEMA_VERSION,
            migration = QkRealmMigration(
                CursorToContactImpl(context, PermissionManagerImpl(context)),
                preferences(name),
            ),
        )
        return Realm.getInstance(configuration)
    }

    private fun preferences(name: String): Preferences {
        val preferenceName = "$name.preferences"
        val sharedPreferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        preferenceStores += sharedPreferences
        return Preferences(
            context,
            RxSharedPreferences.create(sharedPreferences),
            sharedPreferences,
        )
    }

    private fun configuration(
        name: String,
        version: Long,
        migration: RealmMigration? = null,
    ): RealmConfiguration = RealmConfiguration.Builder()
        .name(name)
        .schemaVersion(version)
        .apply { if (migration != null) migration(migration) }
        .build()
        .also(configurations::add)

    private fun createMessage(
        realm: DynamicRealm,
        realmId: Long,
        identity: ReactionTransportPolicy.ProviderIdentity,
    ): DynamicRealmObject =
        realm.createObject("Message", realmId).apply {
            setString("type", identity.type)
            setLong("contentId", identity.contentId)
            setLong("threadId", identity.threadId)
            setInt("subId", identity.subId)
        }

    private fun identity(
        type: String,
        contentId: Long,
        threadId: Long,
        subscriptionId: Int,
    ) = ReactionTransportPolicy.ProviderIdentity(type, contentId, threadId, subscriptionId)

    private companion object {
        val VERSION_18_FIELDS = listOf(
            "ownerSessionId",
            "routeKind",
            "routeFingerprint",
            "routeRegion",
            "targetBodyFingerprint",
            "bodyFingerprint",
        )
    }
}
