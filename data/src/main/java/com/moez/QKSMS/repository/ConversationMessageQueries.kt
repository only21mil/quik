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

import dev.octoshrimpy.quik.model.Conversation
import dev.octoshrimpy.quik.model.Message
import io.realm.Realm
import io.realm.Sort

internal fun Realm.findLatestVisibleMessage(threadId: Long): Message? =
    where(Message::class.java)
        .equalTo("threadId", threadId)
        .equalTo("isEmojiReaction", false)
        .sort("date", Sort.DESCENDING)
        .findFirst()

internal fun Realm.refreshConversationPreviews(threadIds: Collection<Long>? = null) {
    val conversations = where(Conversation::class.java)
        .let { query ->
            when {
                threadIds == null -> query
                threadIds.isEmpty() -> query.equalTo("id", -1L)
                else -> query.`in`("id", threadIds.toTypedArray())
            }
        }
        .findAll()

    val refresh = {
        conversations.forEach { conversation ->
            conversation.lastMessage = findLatestVisibleMessage(conversation.id)
        }
    }

    if (isInTransaction) {
        refresh()
    } else {
        executeTransaction { refresh() }
    }
}

internal fun Realm.repairHiddenConversationPreviews(threadIds: Collection<Long>? = null) {
    val hiddenPreviews = where(Conversation::class.java)
        .equalTo("lastMessage.isEmojiReaction", true)
        .let { query ->
            when {
                threadIds == null -> query
                threadIds.isEmpty() -> query.equalTo("id", -1L)
                else -> query.`in`("id", threadIds.toTypedArray())
            }
        }
        .findAll()

    if (hiddenPreviews.isEmpty()) return

    refreshConversationPreviews(hiddenPreviews.map { conversation -> conversation.id })
}
