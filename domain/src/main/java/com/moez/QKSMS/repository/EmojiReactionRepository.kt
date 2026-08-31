/*
 * Copyright (C) 2025
 *
 * This file is part of QUIK.
 *
 * QUIK is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QUIK is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QUIK.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.octoshrimpy.quik.repository

import dev.octoshrimpy.quik.model.Message
import io.realm.Realm

data class ParsedEmojiReaction(val emoji: String, val originalMessage: String, val isRemoval: Boolean = false)

data class ReactionCarrierSource(
    val transport: Transport,
    val mediaPartCount: Int,
) {
    enum class Transport {
        SMS,
        MMS,
    }

    companion object {
        fun from(message: Message): ReactionCarrierSource = ReactionCarrierSource(
            transport = if (message.isMms()) Transport.MMS else Transport.SMS,
            mediaPartCount = if (message.isMms()) {
                message.parts.count { part ->
                    !part.type.equals("text/plain", ignoreCase = true) &&
                        !part.type.equals("application/smil", ignoreCase = true)
                }
            } else {
                0
            },
        )
    }
}

interface EmojiReactionRepository {
    fun parseEmojiReaction(body: String, source: ReactionCarrierSource): ParsedEmojiReaction?

    /** Classifies one carrier, consulting any durable outbound attempt before parsing its body. */
    fun processEmojiReaction(reactionMessage: Message, realm: Realm): Boolean

    fun findTargetMessage(reactionMessage: Message, originalMessageText: String, realm: Realm): Message?

    fun saveEmojiReaction(
        reactionMessage: Message,
        parsedReaction: ParsedEmojiReaction,
        targetMessage: Message?,
        realm: Realm,
    )

    fun deleteAndReparseAllEmojiReactions(
        realm: Realm,
        onProgress: (SyncRepository.SyncProgress) -> Unit
    )
}
