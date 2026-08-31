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
package dev.octoshrimpy.quik.feature.compose

import androidx.annotation.StringRes
import dev.octoshrimpy.quik.R

internal data class ReactionMessageCandidate(
    val id: Long,
    val isReaction: Boolean,
    val isFailed: Boolean,
    val hasText: Boolean,
    val hasNonTextParts: Boolean,
)

internal data class ReactionOption(
    val emoji: String,
    @StringRes val label: Int,
)

internal object ReactionUiPolicy {
    val options = listOf(
        ReactionOption("❤️", R.string.reaction_choice_love),
        ReactionOption("👍", R.string.reaction_choice_like),
        ReactionOption("👎", R.string.reaction_choice_dislike),
        ReactionOption("😂", R.string.reaction_choice_laugh),
        ReactionOption("‼️", R.string.reaction_choice_emphasize),
        ReactionOption("❓", R.string.reaction_choice_question),
    )

    fun targetId(
        selectedIds: List<Long>,
        visibleMessages: List<ReactionMessageCandidate>,
        isDefaultSms: Boolean,
        hasSendSms: Boolean,
    ): Long? {
        if (!isDefaultSms || !hasSendSms) return null

        val selectedId = selectedIds.singleOrNull() ?: return null
        val message = visibleMessages.singleOrNull { it.id == selectedId } ?: return null

        return selectedId.takeIf {
            !message.isReaction &&
                !message.isFailed &&
                message.hasText &&
                !message.hasNonTextParts
        }
    }
}

internal class ReactionIntentGate {
    var activeMessageId: Long? = null
        private set

    fun open(messageId: Long) {
        activeMessageId = messageId
    }

    fun consume(messageId: Long, emoji: String): Pair<Long, String>? {
        val isSupported = ReactionUiPolicy.options.any { option -> option.emoji == emoji }
        if (activeMessageId != messageId || !isSupported) return null

        activeMessageId = null
        return messageId to emoji
    }

    fun cancel() {
        activeMessageId = null
    }
}
