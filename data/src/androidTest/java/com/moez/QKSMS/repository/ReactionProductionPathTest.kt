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

import android.app.Activity
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import androidx.test.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import com.f2prateek.rx.preferences2.RxSharedPreferences
import com.moez.QKSMS.repository.ReactionWireCodec
import com.squareup.moshi.Moshi
import dev.octoshrimpy.quik.manager.ActiveConversationManager
import dev.octoshrimpy.quik.manager.KeyManager
import dev.octoshrimpy.quik.manager.ReactionRuntimeAuthority
import dev.octoshrimpy.quik.mapper.CursorToContact
import dev.octoshrimpy.quik.mapper.CursorToContactGroup
import dev.octoshrimpy.quik.mapper.CursorToContactGroupMember
import dev.octoshrimpy.quik.mapper.CursorToConversation
import dev.octoshrimpy.quik.mapper.CursorToMessage
import dev.octoshrimpy.quik.mapper.CursorToPart
import dev.octoshrimpy.quik.mapper.CursorToRecipient
import dev.octoshrimpy.quik.model.EmojiReaction
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.model.MmsPart
import dev.octoshrimpy.quik.model.ReactionAttempt
import dev.octoshrimpy.quik.util.PhoneNumberUtils
import dev.octoshrimpy.quik.util.Preferences
import io.realm.Realm
import io.realm.RealmConfiguration
import io.realm.RealmList
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Provider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock

@RunWith(AndroidJUnit4::class)
class ReactionProductionPathTest {
    private lateinit var context: Context
    private lateinit var configuration: RealmConfiguration
    private lateinit var reactions: EmojiReactionRepositoryImpl
    private lateinit var cursorToMessage: ProviderCursorToMessage
    private lateinit var cursorToPart: ProviderCursorToPart
    private lateinit var syncRepository: SyncRepositoryImpl
    private lateinit var messageRepository: MessageRepositoryImpl

    @Before
    fun setUp() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = ContentResolver.wrap(WritableProvider())
        context = ResolverContext(targetContext, resolver)
        Realm.init(context)
        configuration = RealmConfiguration.Builder()
            .name("reaction-production-${UUID.randomUUID()}.realm")
            .build()
        Realm.setDefaultConfiguration(configuration)

        reactions = EmojiReactionRepositoryImpl(context, Moshi.Builder().build())
        cursorToMessage = ProviderCursorToMessage()
        val sharedPreferences = context.getSharedPreferences(
            "reaction-production-${UUID.randomUUID()}",
            Context.MODE_PRIVATE,
        )
        val rxPreferences = RxSharedPreferences.create(sharedPreferences)
        val phoneNumberUtils = PhoneNumberUtils(context)
        val conversationRepository = mock(ConversationRepository::class.java)
        val keyManager = mock(KeyManager::class.java)
        cursorToPart = ProviderCursorToPart()

        syncRepository = SyncRepositoryImpl(
            contentResolver = resolver,
            conversationRepo = conversationRepository,
            cursorToConversation = mock(CursorToConversation::class.java),
            cursorToMessage = cursorToMessage,
            cursorToPart = cursorToPart,
            cursorToRecipient = mock(CursorToRecipient::class.java),
            cursorToContact = mock(CursorToContact::class.java),
            cursorToContactGroup = mock(CursorToContactGroup::class.java),
            cursorToContactGroupMember = mock(CursorToContactGroupMember::class.java),
            keys = keyManager,
            phoneNumberUtils = phoneNumberUtils,
            messageRepo = Provider { mock(MessageRepository::class.java) },
            rxPrefs = rxPreferences,
            reactions = reactions,
        )
        messageRepository = MessageRepositoryImpl(
            activeConversationManager = mock(ActiveConversationManager::class.java),
            context = context,
            messageIds = keyManager,
            phoneNumberUtils = phoneNumberUtils,
            prefs = Preferences(context, rxPreferences, sharedPreferences),
            syncRepository = syncRepository,
            reactions = reactions,
            cursorToMessage = cursorToMessage,
            cursorToPart = cursorToPart,
            smsReactionRouteResolver = ProviderBackedOneToOneSmsReactionRouteResolver(context),
            oneToOneMmsReactionRouteResolver =
                ProviderBackedOneToOneMmsReactionRouteResolver(context),
            groupReactionRouteResolver = ProviderBackedGroupMmsReactionRouteResolver(context),
            reactionRuntimeAuthority = object : ReactionRuntimeAuthority {
                override fun canSendReaction(): Boolean = true
            },
        )
    }

    @After
    fun tearDown() {
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction { it.deleteAll() }
        }
        Realm.deleteRealm(configuration)
    }

    @Test
    fun fullSyncAndStartupNeverInferSuccessFromProviderState() {
        val target = message(Message.TYPE_SMS, realmId = 11, contentId = 501, body = "target")
        val body = encodedAddBody(Message.TYPE_SMS, "target")
        val carrier = message(
            Message.TYPE_SMS,
            realmId = 12,
            contentId = 601,
            body = body,
            boxId = Sms.MESSAGE_TYPE_SENT,
        )
        seed(target, carrier, attempt(target, carrier, body, ReactionAttempt.State.SUBMITTED))

        cursorToMessage.messages = listOf(
            message(Message.TYPE_SMS, 101, 501, "target"),
            message(Message.TYPE_SMS, 102, 601, body, Sms.MESSAGE_TYPE_SENT),
        )
        val finished = CountDownLatch(1)
        var running = false
        val subscription = syncRepository.syncProgress.subscribe { progress ->
            if (progress is SyncRepository.SyncProgress.Running) running = true
            if (running && progress is SyncRepository.SyncProgress.Idle) finished.countDown()
        }
        syncRepository.syncMessages()
        assertTrue("full sync did not finish", finished.await(10, TimeUnit.SECONDS))
        subscription.dispose()

        assertSubmittedAndHidden(expectedCarrierRealmId = 102)

        // Exercise the startup entry point against the same provider-sent row.
        syncRepository.reconcileReactionAttempts()
        assertSubmittedAndHidden(expectedCarrierRealmId = 102)

        // A callback that arrives after restart and full sync still completes the exact attempt.
        assertEquals(
            ReactionCallbackResult.SENT,
            messageRepository.completeReaction(
                attemptId = "attempt-sms-601",
                transportKey = identity(Message.TYPE_SMS, 601).encode(),
                resultCode = Activity.RESULT_OK,
            ),
        )
        Realm.getDefaultInstance().use { realm ->
            assertEquals(ReactionAttempt.State.SENT.name, realm.where(ReactionAttempt::class.java).findFirst()?.state)
            assertEquals(1L, realm.where(EmojiReaction::class.java).count())
        }
    }

    @Test
    fun crashAfterProviderStagingIsQuarantinedByRestartAndFullSync() {
        val target = message(Message.TYPE_SMS, realmId = 11, contentId = 501, body = "target")
        val body = encodedAddBody(Message.TYPE_SMS, target.getText(false))
        val carrier = message(
            Message.TYPE_SMS,
            realmId = 102,
            contentId = 601,
            body = body,
            boxId = Sms.MESSAGE_TYPE_OUTBOX,
        )
        val stagingKey = ReactionTransportPolicy.StagingIdentity(
            type = Message.TYPE_SMS,
            stagedAt = carrier.date,
            threadId = carrier.threadId,
            subId = carrier.subId,
        ).encode()
        val crashAttempt = ReactionAttempt().apply {
            id = "crash-after-provider-stage"
            targetKey = requireNotNull(ReactionTransportPolicy.ProviderIdentity.from(target)).encode()
            transportKey = stagingKey
            threadId = carrier.threadId
            this.body = body
            state = ReactionAttempt.State.PREPARING.name
            createdAt = carrier.date
        }
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction {
                it.copyToRealmOrUpdate(target)
                it.copyToRealmOrUpdate(crashAttempt)
            }
        }

        // Startup keeps the durable pre-stage marker fail closed until provider import can
        // correlate it to an exact carrier. It must not infer failure from the missing row.
        syncRepository.reconcileReactionAttempts()
        Realm.getDefaultInstance().use { realm ->
            assertEquals(
                ReactionAttempt.State.PREPARING.name,
                realm.where(ReactionAttempt::class.java).findFirst()?.state,
            )
        }

        cursorToMessage.messages = listOf(
            message(Message.TYPE_SMS, 101, 501, "target"),
            carrier,
        )
        runFullSync()

        Realm.getDefaultInstance().use { realm ->
            val durableAttempt = requireNotNull(realm.where(ReactionAttempt::class.java).findFirst())
            val stableKey = identity(Message.TYPE_SMS, 601).encode()
            assertEquals(ReactionAttempt.State.FAILED.name, durableAttempt.state)
            assertEquals(stableKey, durableAttempt.transportKey)
            assertTrue(requireNotNull(ReactionAttemptReconciler.findMessage(realm, identity(Message.TYPE_SMS, 601))).isEmojiReaction)
            assertEquals(0L, realm.where(EmojiReaction::class.java).count())
        }
        assertEquals(
            ReactionCallbackResult.ALREADY_TERMINAL,
            messageRepository.completeReaction(
                attemptId = crashAttempt.id,
                transportKey = identity(Message.TYPE_SMS, 601).encode(),
                resultCode = Activity.RESULT_OK,
            ),
        )
    }

    @Test
    fun identicalSmsAndMmsStagingGroupsStayHiddenUntilExactLateCallbacks() {
        listOf(Message.TYPE_SMS, Message.TYPE_MMS).forEachIndexed { index, type ->
            Realm.getDefaultInstance().use { realm ->
                realm.executeTransaction { it.deleteAll() }
            }
            val threadId = 82L + index
            val target = message(type, 101, 501, "target-$type", threadId = threadId)
            val body = encodedAddBody(type, target.getText(false))
            val firstCarrier = message(
                type,
                realmId = 102,
                contentId = 601,
                body = body,
                threadId = threadId,
            )
            val secondCarrier = message(
                type,
                realmId = 103,
                contentId = 602,
                body = body,
                threadId = threadId,
            )
            assertEquals(firstCarrier.date, secondCarrier.date)
            val stagingKey = ReactionTransportPolicy.StagingIdentity(
                type = type,
                stagedAt = firstCarrier.date,
                threadId = threadId,
                subId = firstCarrier.subId,
            ).encode()
            val attempts = listOf("first", "second").map { suffix ->
                ReactionAttempt().apply {
                    id = "collision-$type-$suffix"
                    targetKey = requireNotNull(
                        ReactionTransportPolicy.ProviderIdentity.from(target)
                    ).encode()
                    transportKey = stagingKey
                    this.threadId = threadId
                    this.body = body
                    state = ReactionAttempt.State.PREPARING.name
                    createdAt = firstCarrier.date
                }
            }
            Realm.getDefaultInstance().use { realm ->
                realm.executeTransaction { transactionRealm ->
                    attempts.forEach(transactionRealm::copyToRealmOrUpdate)
                }
            }

            cursorToMessage.messages = listOf(target, firstCarrier, secondCarrier)
            cursorToPart.parts = cursorToMessage.messages.flatMap { message ->
                message.parts.map(::copyPart)
            }
            runFullSync()
            syncRepository.reconcileReactionAttempts()

            Realm.getDefaultInstance().use { realm ->
                val durableAttempts = realm.where(ReactionAttempt::class.java).findAll()
                assertEquals(2, durableAttempts.size)
                assertTrue(
                    durableAttempts.all { attempt ->
                        attempt.state == ReactionAttempt.State.QUARANTINED.name &&
                            attempt.transportKey == stagingKey
                    }
                )
                listOf(601L, 602L).forEach { contentId ->
                    assertTrue(
                        requireNotNull(
                            ReactionAttemptReconciler.findMessage(
                                realm,
                                identity(type, contentId, threadId),
                            )
                        ).isEmojiReaction
                    )
                }
                assertEquals(0L, realm.where(EmojiReaction::class.java).count())
            }

            // No retry or provider state can commit a badge. Each exact callback identifies one
            // provider row through its attempt id and URI, after which the normal callback-only
            // path may commit SENT.
            listOf(601L, 602L).forEachIndexed { callbackIndex, contentId ->
                assertEquals(
                    ReactionCallbackResult.SENT,
                    messageRepository.completeReaction(
                        attemptId = attempts[callbackIndex].id,
                        transportKey = identity(type, contentId, threadId).encode(),
                        resultCode = Activity.RESULT_OK,
                    ),
                )
            }
            Realm.getDefaultInstance().use { realm ->
                assertTrue(
                    realm.where(ReactionAttempt::class.java).findAll()
                        .all { attempt -> attempt.state == ReactionAttempt.State.SENT.name }
                )
                assertEquals(1L, realm.where(EmojiReaction::class.java).count())
                listOf(601L, 602L).forEach { contentId ->
                    assertTrue(
                        requireNotNull(
                            ReactionAttemptReconciler.findMessage(
                                realm,
                                identity(type, contentId, threadId),
                            )
                        ).isEmojiReaction
                    )
                }
            }
        }
    }

    @Test
    fun oneCarrierCannotBeClaimedByTwoSmsOrMmsAttemptsAfterRestartAndFullSync() {
        listOf(Message.TYPE_SMS, Message.TYPE_MMS).forEachIndexed { index, type ->
            Realm.getDefaultInstance().use { realm ->
                realm.executeTransaction { it.deleteAll() }
            }
            val threadId = 92L + index
            val target = message(type, 101, 501, "target-$type", threadId = threadId)
            val body = encodedAddBody(type, target.getText(false))
            val carrier = message(
                type,
                realmId = 102,
                contentId = 601,
                body = body,
                threadId = threadId,
            )
            val stagingKey = ReactionTransportPolicy.StagingIdentity(
                type = type,
                stagedAt = carrier.date,
                threadId = threadId,
                subId = carrier.subId,
            ).encode()
            val attempts = listOf("a", "b").map { suffix ->
                ReactionAttempt().apply {
                    id = "single-carrier-$type-$suffix"
                    targetKey = requireNotNull(
                        ReactionTransportPolicy.ProviderIdentity.from(target)
                    ).encode()
                    transportKey = stagingKey
                    this.threadId = threadId
                    this.body = body
                    state = ReactionAttempt.State.PREPARING.name
                    createdAt = carrier.date
                }
            }
            Realm.getDefaultInstance().use { realm ->
                realm.executeTransaction { transactionRealm ->
                    attempts.forEach(transactionRealm::copyToRealmOrUpdate)
                }
            }

            cursorToMessage.messages = listOf(target, carrier)
            cursorToPart.parts = cursorToMessage.messages.flatMap { message ->
                message.parts.map(::copyPart)
            }
            runFullSync()
            syncRepository.reconcileReactionAttempts()

            Realm.getDefaultInstance().use { realm ->
                val durableAttempts = realm.where(ReactionAttempt::class.java).findAll()
                assertEquals(2, durableAttempts.size)
                assertTrue(
                    durableAttempts.all { attempt ->
                        attempt.state == ReactionAttempt.State.QUARANTINED.name &&
                            attempt.transportKey == stagingKey
                    }
                )
            }

            val carrierKey = identity(type, 601, threadId).encode()
            assertEquals(
                ReactionCallbackResult.SENT,
                messageRepository.completeReaction(
                    attemptId = attempts[0].id,
                    transportKey = carrierKey,
                    resultCode = Activity.RESULT_OK,
                ),
            )

            // Simulate process startup and a provider rebuild after A has claimed the only row.
            syncRepository.reconcileReactionAttempts()
            runFullSync()
            syncRepository.reconcileReactionAttempts()

            assertEquals(
                ReactionCallbackResult.CORRELATION_MISMATCH,
                messageRepository.completeReaction(
                    attemptId = attempts[1].id,
                    transportKey = carrierKey,
                    resultCode = Activity.RESULT_OK,
                ),
            )
            Realm.getDefaultInstance().use { realm ->
                val durableAttempts = realm.where(ReactionAttempt::class.java).findAll()
                    .associateBy { attempt -> attempt.id }
                assertEquals(
                    ReactionAttempt.State.SENT.name,
                    durableAttempts.getValue(attempts[0].id).state,
                )
                assertEquals(
                    ReactionAttempt.State.QUARANTINED.name,
                    durableAttempts.getValue(attempts[1].id).state,
                )
                assertEquals(carrierKey, durableAttempts.getValue(attempts[0].id).transportKey)
                assertEquals(stagingKey, durableAttempts.getValue(attempts[1].id).transportKey)
                assertTrue(
                    requireNotNull(
                        ReactionAttemptReconciler.findMessage(
                            realm,
                            identity(type, 601, threadId),
                        )
                    ).isEmojiReaction
                )
                assertTrue(realm.where(EmojiReaction::class.java).count() <= 1L)
                assertTrue(
                    requireNotNull(
                        ReactionAttemptReconciler.findMessage(
                            realm,
                            identity(type, 501, threadId),
                        )
                    ).emojiReactions.size <= 1
                )
            }
        }
    }

    @Test
    fun failedLegacyAttemptsQuarantineOldAndV2Carriers() {
        val target = message(Message.TYPE_SMS, realmId = 11, contentId = 501, body = "target")
        val body = encodedAddBody(Message.TYPE_SMS, target.getText(false))
        val oldCarrier = message(Message.TYPE_SMS, realmId = 12, contentId = 601, body = body)
        val v2Carrier = message(Message.TYPE_SMS, realmId = 13, contentId = 602, body = body)
        val oldAttempt = attempt(target, oldCarrier, body, ReactionAttempt.State.FAILED).apply {
            id = "legacy-failed"
            transportKey = "content://sms/601"
        }
        val v2Attempt = attempt(target, v2Carrier, body, ReactionAttempt.State.FAILED).apply {
            id = "legacy-failed-v2"
        }
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction {
                it.copyToRealmOrUpdate(target)
                it.copyToRealmOrUpdate(oldCarrier)
                it.copyToRealmOrUpdate(v2Carrier)
                it.copyToRealmOrUpdate(oldAttempt)
                it.copyToRealmOrUpdate(v2Attempt)
            }
        }

        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction {
                ReactionAttemptReconciler.reconcileAll(it)
                listOf(601L, 602L).forEach { contentId ->
                    reactions.processEmojiReaction(
                        requireNotNull(
                            ReactionAttemptReconciler.findMessage(
                                it,
                                identity(Message.TYPE_SMS, contentId),
                            )
                        ),
                        it,
                    )
                }
            }
            val attempts = realm.where(ReactionAttempt::class.java).findAll().associateBy { it.id }
            assertEquals(ReactionAttempt.State.FAILED.name, attempts.getValue("legacy-failed").state)
            assertEquals(ReactionAttempt.State.FAILED.name, attempts.getValue("legacy-failed-v2").state)
            assertEquals(
                identity(Message.TYPE_SMS, 601).encode(),
                attempts.getValue("legacy-failed").transportKey,
            )
            assertTrue(requireNotNull(ReactionAttemptReconciler.resolve(realm, attempts.getValue("legacy-failed"))).carrier.isEmojiReaction)
            assertTrue(requireNotNull(ReactionAttemptReconciler.resolve(realm, attempts.getValue("legacy-failed-v2"))).carrier.isEmojiReaction)
            assertEquals(0L, realm.where(EmojiReaction::class.java).count())
        }
    }

    @Test
    fun androidCallbackIsTheOnlySmsAndMmsBadgeCommitPath() {
        listOf(Message.TYPE_SMS, Message.TYPE_MMS).forEachIndexed { index, type ->
            Realm.getDefaultInstance().use { realm ->
                realm.executeTransaction { it.deleteAll() }
            }
            val threadId = 72L + index
            val target = message(type, 100L + index, 500L + index, "target-$type", threadId = threadId)
            val body = encodedAddBody(type, target.getText(false))
            val carrier = message(
                type,
                realmId = 200L + index,
                contentId = 600L + index,
                body = body,
                boxId = if (type == Message.TYPE_SMS) Sms.MESSAGE_TYPE_OUTBOX else Mms.MESSAGE_BOX_OUTBOX,
                threadId = threadId,
            ).apply { isEmojiReaction = true }
            val callbackState = if (type == Message.TYPE_SMS) {
                ReactionAttempt.State.HANDOFF_FAILED
            } else {
                ReactionAttempt.State.SUBMITTED
            }
            val durableAttempt = attempt(target, carrier, body, callbackState).apply {
                id = "callback-$type"
            }
            seed(target, carrier, durableAttempt)

            Realm.getDefaultInstance().use { realm ->
                realm.executeTransaction { ReactionAttemptReconciler.reconcileAll(it) }
                assertEquals(0L, realm.where(EmojiReaction::class.java).count())
            }

            assertEquals(
                ReactionCallbackResult.SENT,
                messageRepository.completeReaction(
                    attemptId = durableAttempt.id,
                    transportKey = durableAttempt.transportKey.orEmpty(),
                    resultCode = Activity.RESULT_OK,
                ),
            )

            Realm.getDefaultInstance().use { realm ->
                val persistedAttempt = requireNotNull(
                    realm.where(ReactionAttempt::class.java).equalTo("id", durableAttempt.id).findFirst()
                )
                val persistedTarget = requireNotNull(
                    ReactionAttemptReconciler.findMessage(
                        realm,
                        requireNotNull(ReactionTransportPolicy.ProviderIdentity.from(target)),
                    )
                )
                assertEquals(ReactionAttempt.State.SENT.name, persistedAttempt.state)
                assertEquals(1, persistedTarget.emojiReactions.size)
                assertEquals(-(200L + index), persistedTarget.emojiReactions.single()?.id)
                assertEquals(true, persistedTarget.emojiReactions.single()?.fromMe)
            }
        }
    }

    private fun assertSubmittedAndHidden(expectedCarrierRealmId: Long) {
        Realm.getDefaultInstance().use { realm ->
            val persistedAttempt = requireNotNull(realm.where(ReactionAttempt::class.java).findFirst())
            val resolved = requireNotNull(ReactionAttemptReconciler.resolve(realm, persistedAttempt))
            assertEquals(ReactionAttempt.State.SUBMITTED.name, persistedAttempt.state)
            assertEquals(expectedCarrierRealmId, resolved.carrier.id)
            assertTrue(resolved.carrier.isEmojiReaction)
            assertEquals(0L, realm.where(EmojiReaction::class.java).count())
        }
    }

    private fun runFullSync() {
        val finished = CountDownLatch(1)
        var running = false
        val subscription = syncRepository.syncProgress.subscribe { progress ->
            if (progress is SyncRepository.SyncProgress.Running) running = true
            if (running && progress is SyncRepository.SyncProgress.Idle) finished.countDown()
        }
        syncRepository.syncMessages()
        assertTrue("full sync did not finish", finished.await(10, TimeUnit.SECONDS))
        subscription.dispose()
    }

    private fun seed(target: Message, carrier: Message, attempt: ReactionAttempt) {
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction {
                it.copyToRealmOrUpdate(target)
                it.copyToRealmOrUpdate(carrier)
                it.copyToRealmOrUpdate(attempt)
            }
        }
    }

    private fun attempt(
        target: Message,
        carrier: Message,
        body: String,
        state: ReactionAttempt.State,
    ) = ReactionAttempt().apply {
        id = "attempt-${carrier.type}-${carrier.contentId}"
        targetKey = requireNotNull(ReactionTransportPolicy.ProviderIdentity.from(target)).encode()
        transportKey = requireNotNull(ReactionTransportPolicy.ProviderIdentity.from(carrier)).encode()
        threadId = carrier.threadId
        this.body = body
        this.state = state.name
        createdAt = 1
        handoffAt = 1
    }

    private fun identity(type: String, contentId: Long, threadId: Long = 72) =
        ReactionTransportPolicy.ProviderIdentity(type, contentId, threadId, 3)

    private fun message(
        type: String,
        realmId: Long,
        contentId: Long,
        body: String,
        boxId: Int = if (type == Message.TYPE_SMS) Sms.MESSAGE_TYPE_INBOX else Mms.MESSAGE_BOX_INBOX,
        threadId: Long = 72,
    ) = Message().apply {
        id = realmId
        this.type = type
        this.contentId = contentId
        this.threadId = threadId
        subId = 3
        this.boxId = boxId
        date = if (contentId >= 600) 200 else 100
        if (type == Message.TYPE_SMS) {
            this.body = body
        } else {
            parts = RealmList(MmsPart().apply {
                id = contentId * 10
                messageId = contentId
                this.type = "text/plain"
                text = body
            })
        }
    }

    private fun encodedAddBody(type: String, targetBody: String): String = requireNotNull(
        ReactionWireCodec.encode(
            operation = ReactionWireCodec.Operation.ADD,
            reaction = ReactionWireCodec.ClassicReaction.HEART,
            target = ReactionWireCodec.Carrier(
                transport = if (type == Message.TYPE_MMS) {
                    ReactionWireCodec.Transport.MMS
                } else {
                    ReactionWireCodec.Transport.SMS
                },
                body = targetBody,
            ),
        ) as? ReactionWireCodec.EncodeResult.Encoded
    ).body

    private fun copyPart(part: MmsPart) = MmsPart().apply {
        id = part.id
        messageId = part.messageId
        type = part.type
        text = part.text
    }

    private class ResolverContext(
        base: Context,
        private val resolver: ContentResolver,
    ) : ContextWrapper(base) {
        override fun getContentResolver(): ContentResolver = resolver
    }

    private class WritableProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri = uri

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 1

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor = MatrixCursor(arrayOf("_id")).apply {
            addRow(arrayOf(ContentUris.parseId(uri)))
        }

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 1
    }

    private class ProviderCursorToMessage : CursorToMessage {
        var messages: List<Message> = emptyList()

        override fun getMessagesCursor(): Cursor = MatrixCursor(arrayOf("_id")).apply {
            messages.forEach { message -> addRow(arrayOf(message.contentId)) }
        }

        override fun getMessageCursor(id: Long): Cursor? = null

        override fun map(from: Pair<Cursor, CursorToMessage.MessageColumns>): Message {
            val contentId = from.first.getLong(from.first.getColumnIndexOrThrow("_id"))
            val template = requireNotNull(messages.singleOrNull { it.contentId == contentId })
            return Message().apply {
                id = template.id
                threadId = template.threadId
                this.contentId = template.contentId
                address = template.address
                boxId = template.boxId
                type = template.type
                date = template.date
                subId = template.subId
                body = template.body
                parts = RealmList<MmsPart>().apply {
                    addAll(template.parts.map { part ->
                        MmsPart().apply {
                            id = part.id
                            messageId = part.messageId
                            type = part.type
                            text = part.text
                        }
                    })
                }
            }
        }
    }

    private class ProviderCursorToPart : CursorToPart {
        var parts: List<MmsPart> = emptyList()

        override fun getPartsCursor(messageId: Long?): Cursor = MatrixCursor(arrayOf("_id")).apply {
            parts.filter { part -> messageId == null || part.messageId == messageId }
                .forEach { part -> addRow(arrayOf(part.id)) }
        }

        override fun map(from: Cursor): MmsPart {
            val id = from.getLong(from.getColumnIndexOrThrow("_id"))
            val template = requireNotNull(parts.singleOrNull { part -> part.id == id })
            return copyPart(template)
        }

        private fun copyPart(part: MmsPart) = MmsPart().apply {
            id = part.id
            messageId = part.messageId
            type = part.type
            text = part.text
        }
    }
}
