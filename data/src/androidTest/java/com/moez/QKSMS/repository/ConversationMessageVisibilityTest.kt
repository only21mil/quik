/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.octoshrimpy.quik.repository

import android.content.Context
import androidx.test.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import dev.octoshrimpy.quik.filter.ContactFilter
import dev.octoshrimpy.quik.filter.ConversationFilter
import dev.octoshrimpy.quik.filter.PhoneNumberFilter
import dev.octoshrimpy.quik.filter.RecipientFilter
import dev.octoshrimpy.quik.mapper.CursorToConversation
import dev.octoshrimpy.quik.mapper.CursorToRecipient
import dev.octoshrimpy.quik.model.Conversation
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.model.Recipient
import dev.octoshrimpy.quik.util.PhoneNumberUtils
import io.realm.Realm
import io.realm.RealmConfiguration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ConversationMessageVisibilityTest {

    private lateinit var configuration: RealmConfiguration
    private lateinit var repository: ConversationRepositoryImpl

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Realm.init(context)
        configuration = RealmConfiguration.Builder()
            .inMemory()
            .name("conversation-visibility-${UUID.randomUUID()}")
            .build()
        Realm.setDefaultConfiguration(configuration)

        val phoneNumberUtils = PhoneNumberUtils(context)
        val phoneNumberFilter = PhoneNumberFilter(phoneNumberUtils)
        val conversationFilter = ConversationFilter(
            RecipientFilter(ContactFilter(phoneNumberFilter), phoneNumberFilter)
        )
        repository = ConversationRepositoryImpl(
            context,
            conversationFilter,
            mock(CursorToConversation::class.java),
            mock(CursorToRecipient::class.java),
            phoneNumberUtils,
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
    fun newestHiddenRowDoesNotBecomePreview() {
        seedConversation(threadId = 1, hiddenCarrier = true)

        val conversation = repository.getConversationsSnapshot(unreadAtTop = false).single()

        assertEquals(11L, conversation.lastMessage?.id)
    }

    @Test
    fun hiddenRowIsExcludedFromUnreadUnseenAndSearch() {
        seedConversation(threadId = 1, hiddenCarrier = true)

        assertTrue(repository.getUnreadIds().isEmpty())
        assertTrue(repository.getUnseenIds().isEmpty())
        assertTrue(repository.searchConversations("reaction carrier").isEmpty())

        val visibleResults = repository.searchConversations("visible message")
        assertEquals(1, visibleResults.size)
        assertEquals(1, visibleResults.single().messages)
    }

    @Test
    fun liveSyncRefreshRepairsOnlyTheClassifiedThread() {
        seedConversation(threadId = 1, hiddenCarrier = true)
        seedConversation(threadId = 2, hiddenCarrier = true)

        Realm.getDefaultInstance().use { realm ->
            realm.refreshConversationPreviews(listOf(1))
            assertEquals(11L, realm.where(Conversation::class.java).equalTo("id", 1L).findFirst()?.lastMessage?.id)
            assertTrue(
                realm.where(Conversation::class.java)
                    .equalTo("id", 2L)
                    .findFirst()
                    ?.lastMessage
                    ?.isEmojiReaction == true
            )
        }
    }

    @Test
    fun fullSyncRefreshRepairsEveryClassifiedThread() {
        seedConversation(threadId = 1, hiddenCarrier = true)
        seedConversation(threadId = 2, hiddenCarrier = true)

        Realm.getDefaultInstance().use { realm ->
            realm.refreshConversationPreviews()
            assertFalse(
                realm.where(Conversation::class.java)
                    .findAll()
                    .any { conversation -> conversation.lastMessage?.isEmojiReaction == true }
            )
        }
    }

    @Test
    fun unresolvedCarrierRemainsVisibleUnreadAndSearchable() {
        seedConversation(threadId = 1, hiddenCarrier = false)

        repository.updateConversations(listOf(1))

        Realm.getDefaultInstance().use { realm ->
            assertEquals(12L, realm.where(Conversation::class.java).findFirst()?.lastMessage?.id)
        }
        assertEquals(listOf(1L), repository.getUnreadIds())
        assertEquals(listOf(1L), repository.getUnseenIds())
        assertEquals(1, repository.searchConversations("reaction carrier").single().messages)
    }

    private fun seedConversation(threadId: Long, hiddenCarrier: Boolean) {
        val visibleMessage = Message().apply {
            id = threadId * 10 + 1
            this.threadId = threadId
            type = Message.TYPE_SMS
            body = "visible message"
            date = 100
            read = true
            seen = true
        }
        val carrier = Message().apply {
            id = threadId * 10 + 2
            this.threadId = threadId
            type = Message.TYPE_SMS
            body = "reaction carrier"
            date = 200
            read = false
            seen = false
            isEmojiReaction = hiddenCarrier
        }
        val recipient = Recipient(id = threadId, address = "+1555000000$threadId")

        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction {
                val managedRecipient = it.copyToRealmOrUpdate(recipient)
                it.copyToRealmOrUpdate(visibleMessage)
                val managedCarrier = it.copyToRealmOrUpdate(carrier)
                it.copyToRealmOrUpdate(Conversation(id = threadId).apply {
                    recipients.add(managedRecipient)
                    lastMessage = managedCarrier
                })
            }
        }
    }
}
