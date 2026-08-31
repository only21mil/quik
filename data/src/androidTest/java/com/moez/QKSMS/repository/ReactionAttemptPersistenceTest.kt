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

import android.content.Context
import android.provider.Telephony.Sms
import androidx.test.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import com.moez.QKSMS.repository.ReactionWireCodec
import com.squareup.moshi.Moshi
import dev.octoshrimpy.quik.model.EmojiReaction
import dev.octoshrimpy.quik.manager.ReactionRuntimeAuthority
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.model.ReactionAttempt
import io.realm.Realm
import io.realm.RealmConfiguration
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReactionAttemptPersistenceTest {
    private lateinit var context: Context
    private lateinit var configuration: RealmConfiguration
    private lateinit var reactions: EmojiReactionRepositoryImpl

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        Realm.init(context)
        configuration = RealmConfiguration.Builder()
            .name("reaction-attempt-${UUID.randomUUID()}.realm")
            .build()
        Realm.setDefaultConfiguration(configuration)
        reactions = EmojiReactionRepositoryImpl(context, Moshi.Builder().build())
    }

    @After
    fun tearDown() {
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction { it.deleteAll() }
        }
        Realm.deleteRealm(configuration)
    }

    @Test
    fun closeReopenAndFullSyncBeforeCallbackPreserveExactCorrelation() {
        val body = encodedAddBody("target body")
        val targetKey = identity(contentId = 501).encode()
        val transportKey = identity(contentId = 601).encode()

        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction {
                it.copyToRealmOrUpdate(target(realmId = 11, contentId = 501))
                it.copyToRealmOrUpdate(carrier(realmId = 12, contentId = 601, body = body))
                it.copyToRealmOrUpdate(attempt(targetKey, transportKey, body))
            }
        }

        // A full sync deletes Realm messages and recreates them with unrelated local IDs.
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction {
                it.delete(Message::class.java)
                it.copyToRealmOrUpdate(target(realmId = 101, contentId = 501))
                it.copyToRealmOrUpdate(carrier(realmId = 102, contentId = 601, body = body))
                ReactionAttemptReconciler.reconcileAll(it)
                val syncedCarrier = requireNotNull(
                    ReactionAttemptReconciler.findMessage(it, identity(contentId = 601))
                )
                reactions.processEmojiReaction(syncedCarrier, it)
            }
        }

        Realm.getDefaultInstance().use { realm ->
            val durableAttempt = requireNotNull(
                realm.where(ReactionAttempt::class.java).findFirst()
            )
            val resolved = ReactionAttemptReconciler.resolve(realm, durableAttempt)
            assertNotNull(resolved)
            assertEquals(101L, resolved?.target?.id)
            assertEquals(102L, resolved?.carrier?.id)
            assertEquals(ReactionAttempt.State.SUBMITTED.name, durableAttempt.state)
            assertTrue(resolved?.carrier?.isEmojiReaction == true)
            assertEquals(0L, realm.where(EmojiReaction::class.java).count())

            assertEquals(
                ReactionTransportPolicy.CallbackDecision.COMMIT_SENT,
                ReactionTransportPolicy.decideCallback(
                    ReactionTransportPolicy.CallbackFacts(
                        currentState = durableAttempt.state,
                        expectedTransportKey = durableAttempt.transportKey,
                        receivedTransportKey = transportKey,
                        expectedIdentity = ReactionTransportPolicy.ProviderIdentity.decode(targetKey),
                        carrierThreadId = resolved?.carrier?.threadId ?: 0,
                        carrierSubscriptionId = resolved?.carrier?.subId ?: -1,
                        targetThreadId = resolved?.target?.threadId ?: 0,
                        successful = true,
                    )
                ),
            )
        }

        // Provider box state is never delivery evidence. Only the Android sent callback may
        // complete the attempt and expose a badge.
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction {
                val durableAttempt = requireNotNull(
                    it.where(ReactionAttempt::class.java).findFirst()
                )
                val resolved = requireNotNull(ReactionAttemptReconciler.resolve(it, durableAttempt))
                resolved.carrier.boxId = Sms.MESSAGE_TYPE_SENT
                ReactionAttemptReconciler.reconcileAll(it)
                reactions.processEmojiReaction(resolved.carrier, it)
            }

            val durableAttempt = requireNotNull(
                realm.where(ReactionAttempt::class.java).findFirst()
            )
            val exactTarget = requireNotNull(
                ReactionAttemptReconciler.findMessage(realm, identity(contentId = 501))
            )
            assertEquals(ReactionAttempt.State.SUBMITTED.name, durableAttempt.state)
            assertEquals(0, exactTarget.emojiReactions.size)
        }
    }

    @Test
    fun nonterminalReconciliationHidesCarriersWithoutResubmission() {
        val body = encodedAddBody("target body")
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction {
                it.copyToRealmOrUpdate(target(realmId = 1, contentId = 501))
                it.copyToRealmOrUpdate(carrier(realmId = 2, contentId = 601, body = body))
                it.copyToRealmOrUpdate(
                    attempt(
                        targetKey = identity(501).encode(),
                        transportKey = identity(601).encode(),
                        body = body,
                        id = "preparing",
                    ).apply {
                        state = ReactionAttempt.State.PREPARING.name
                    }
                )
                it.copyToRealmOrUpdate(
                    carrier(realmId = 3, contentId = 603, body = body).apply {
                        boxId = Sms.MESSAGE_TYPE_FAILED
                    }
                )
                it.copyToRealmOrUpdate(
                    attempt(
                        targetKey = identity(501).encode(),
                        transportKey = identity(603).encode(),
                        body = body,
                        id = "provider-failed",
                    )
                )
                ReactionAttemptReconciler.reconcileAll(it)
            }

            assertEquals(
                ReactionAttempt.State.FAILED.name,
                realm.where(ReactionAttempt::class.java).equalTo("id", "preparing").findFirst()?.state,
            )
            assertEquals(
                ReactionAttempt.State.SUBMITTED.name,
                realm.where(ReactionAttempt::class.java)
                    .equalTo("id", "provider-failed")
                    .findFirst()
                    ?.state,
            )
            assertTrue(
                ReactionAttemptReconciler.findMessage(realm, identity(601))?.isEmojiReaction == true
            )
            assertTrue(
                ReactionAttemptReconciler.findMessage(realm, identity(603))?.isEmojiReaction == true
            )
            assertEquals(0L, realm.where(EmojiReaction::class.java).count())
        }
    }

    @Test
    fun crashHandoffExpiresAtTheFixedBoundaryWithoutCreatingABadge() {
        val handoffAt = 1_000L
        val body = encodedAddBody("target body")
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction {
                it.copyToRealmOrUpdate(target(realmId = 1, contentId = 501))
                it.copyToRealmOrUpdate(carrier(realmId = 2, contentId = 601, body = body))
                it.copyToRealmOrUpdate(
                    attempt(
                        targetKey = identity(501).encode(),
                        transportKey = identity(601).encode(),
                        body = body,
                        id = "crash-handoff",
                    ).apply {
                        state = ReactionAttempt.State.HANDOFF.name
                        this.handoffAt = handoffAt
                    }
                )
                ReactionAttemptReconciler.reconcileAll(
                    it,
                    handoffAt + ReactionAttemptReconciler.HANDOFF_TIMEOUT_MILLIS - 1,
                )
            }
            assertEquals(
                ReactionAttempt.State.HANDOFF.name,
                realm.where(ReactionAttempt::class.java)
                    .equalTo("id", "crash-handoff")
                    .findFirst()
                    ?.state,
            )

            realm.executeTransaction {
                ReactionAttemptReconciler.reconcileAll(
                    it,
                    handoffAt + ReactionAttemptReconciler.HANDOFF_TIMEOUT_MILLIS,
                )
            }
            assertEquals(
                ReactionAttempt.State.HANDOFF_FAILED.name,
                realm.where(ReactionAttempt::class.java)
                    .equalTo("id", "crash-handoff")
                    .findFirst()
                    ?.state,
            )
            assertTrue(
                ReactionAttemptReconciler.findMessage(realm, identity(601))?.isEmojiReaction == true
            )
            assertEquals(0L, realm.where(EmojiReaction::class.java).count())
        }
    }

    @Test
    fun restartGateRejectsTheSameProviderTargetUntilTheAttemptIsTerminal() {
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction {
                it.copyToRealmOrUpdate(target(realmId = 11, contentId = 501))
            }
        }
        val pendingTarget = ReactionPendingTarget(messageId = 11, threadId = 72, subscriptionId = 3)
        val firstLease = RealmReactionPendingAttemptGate(GrantedReactionAuthority)
            .tryAcquire(pendingTarget)

        assertNotNull(firstLease)
        assertNull(RealmReactionPendingAttemptGate(GrantedReactionAuthority).tryAcquire(pendingTarget))

        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction {
                requireNotNull(it.where(ReactionAttempt::class.java).findFirst()).state =
                    ReactionAttempt.State.FAILED.name
            }
        }

        assertNotNull(RealmReactionPendingAttemptGate(GrantedReactionAuthority).tryAcquire(pendingTarget))
    }

    private fun identity(contentId: Long) = ReactionTransportPolicy.ProviderIdentity(
        type = Message.TYPE_SMS,
        contentId = contentId,
        threadId = 72,
        subId = 3,
    )

    private fun target(realmId: Long, contentId: Long) = Message().apply {
        id = realmId
        type = Message.TYPE_SMS
        this.contentId = contentId
        threadId = 72
        subId = 3
        boxId = Sms.MESSAGE_TYPE_INBOX
        body = "target body"
        date = 100
    }

    private fun carrier(realmId: Long, contentId: Long, body: String) = Message().apply {
        id = realmId
        type = Message.TYPE_SMS
        this.contentId = contentId
        threadId = 72
        subId = 3
        boxId = Sms.MESSAGE_TYPE_OUTBOX
        this.body = body
        date = 200
    }

    private fun attempt(
        targetKey: String,
        transportKey: String,
        body: String,
        id: String = "attempt-1",
    ) =
        ReactionAttempt().apply {
            this.id = id
            this.targetKey = targetKey
            this.transportKey = transportKey
            threadId = 72
            this.body = body
            state = ReactionAttempt.State.SUBMITTED.name
            createdAt = 1
        }

    private fun encodedAddBody(targetBody: String): String = requireNotNull(
        ReactionWireCodec.encode(
            operation = ReactionWireCodec.Operation.ADD,
            reaction = ReactionWireCodec.ClassicReaction.HEART,
            target = ReactionWireCodec.Carrier(
                transport = ReactionWireCodec.Transport.SMS,
                body = targetBody,
            ),
        ) as? ReactionWireCodec.EncodeResult.Encoded
    ).body

    private object GrantedReactionAuthority : ReactionRuntimeAuthority {
        override fun canSendReaction(): Boolean = true
    }
}
