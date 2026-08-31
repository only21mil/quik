/*
 * Copyright (C) 2025
 *
 * This file is part of QUIK.
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
package dev.octoshrimpy.quik.model

import io.realm.RealmObject
import io.realm.annotations.Index
import io.realm.annotations.PrimaryKey

open class EmojiReaction : RealmObject() {
    companion object {
        /**
         * Reaction IDs occupy the negative half of the Long range while message IDs and legacy
         * reaction IDs occupy the positive half. Deriving the ID from the carrier also makes an
         * already-persisted carrier idempotent across process restarts.
         */
        fun idForReactionMessage(reactionMessageId: Long): Long {
            require(reactionMessageId != Long.MIN_VALUE) { "Reaction message ID is out of range" }
            require(reactionMessageId > 0) { "Reaction message ID must be positive" }
            return -reactionMessageId
        }

        /** Derives carrier direction when the provider box has an unambiguous meaning. */
        fun fromMeForCarrier(type: String?, boxId: Int): Boolean? = when (type) {
            Message.TYPE_SMS -> when (boxId) {
                0, 1 -> false
                2, 3, 4, 5, 6 -> true
                else -> null
            }

            Message.TYPE_MMS -> when (boxId) {
                0, 1 -> false
                2, 3, 4, 5 -> true
                else -> null
            }

            else -> null
        }
    }

    @PrimaryKey var id: Long = 0

    /** The reaction message ID itself */
    @Index var reactionMessageId: Long = 0

    /** The sender's address (phone number) */
    var senderAddress: String = ""

    /** The emoji used in the reaction */
    var emoji: String = ""

    /** The original message text that was reacted to */
    var originalMessageText: String = ""

    /** Thread ID for easier querying */
    @Index var threadId: Long = 0

    /** Whether the reaction carrier was sent by this device, or null when legacy data is unknown. */
    var fromMe: Boolean? = null
}
