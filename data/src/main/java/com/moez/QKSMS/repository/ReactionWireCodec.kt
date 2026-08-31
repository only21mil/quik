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
package com.moez.QKSMS.repository

/** Pure codec for the SMS/MMS fallback representation of message reactions. */
object ReactionWireCodec {
    const val MAX_TARGET_UTF16_UNITS = 70
    const val MAX_SINGLE_SEGMENT_UCS2_UNITS = 70

    enum class Transport {
        SMS,
        MMS,
    }

    data class Carrier(
        val transport: Transport,
        val body: String?,
        val mediaPartCount: Int = 0,
    )

    enum class Operation {
        ADD,
        REMOVE,
    }

    enum class ClassicReaction(
        val emoji: String,
        internal val addedPrefix: String,
        internal val removedPrefix: String,
    ) {
        HEART("❤️", "Loved ", "Removed a heart from "),
        LIKE("👍", "Liked ", "Removed a like from "),
        DISLIKE("👎", "Disliked ", "Removed a dislike from "),
        LAUGH("😂", "Laughed at ", "Removed a laugh from "),
        EMPHASIS("‼️", "Emphasized ", "Removed an exclamation from "),
        QUESTION("❓", "Questioned ", "Removed a question mark from "),
    }

    data class Reaction(
        val operation: Operation,
        val emoji: String,
        val targetBody: String,
    )

    enum class RejectionReason {
        EMPTY_BODY,
        MEDIA_PARTS_PRESENT,
        INVALID_MEDIA_PART_COUNT,
        TARGET_TOO_LONG,
        CARRIER_EXCEEDS_ONE_SEGMENT,
        ILL_FORMED_UTF16,
        MALFORMED_REACTION,
        AMBIGUOUS_REACTION,
        UNSUPPORTED_REACTION,
    }

    sealed class EncodeResult {
        data class Encoded(val body: String) : EncodeResult()
        data class Rejected(val reason: RejectionReason) : EncodeResult()
    }

    sealed class DecodeResult {
        data class Decoded(val reaction: Reaction) : DecodeResult()
        object NotReaction : DecodeResult()
        data class Rejected(val reason: RejectionReason) : DecodeResult()
    }

    data class GroupOrder(
        val targetGroup: Int,
        val emojiGroup: Int,
    )

    data class InboundPattern(
        val regex: Regex,
        val operation: Operation,
        val targetGroup: Int,
        val emojiGroup: Int? = null,
        val fixedEmoji: String? = null,
    ) {
        init {
            require(targetGroup > 0) { "targetGroup must name a capture group" }
            require((emojiGroup == null) != (fixedEmoji == null)) {
                "Specify exactly one of emojiGroup or fixedEmoji"
            }
            require(emojiGroup == null || emojiGroup > 0) {
                "emojiGroup must name a capture group"
            }
            require(fixedEmoji == null || fixedEmoji.isNotEmpty()) {
                "fixedEmoji must not be empty"
            }
            require(fixedEmoji == null || fixedEmoji.hasWellFormedUtf16()) {
                "fixedEmoji must contain well-formed UTF-16"
            }
        }
    }

    data class LocalizedPatterns(
        val genericAdded: String? = null,
        val genericRemoved: String? = null,
        val heartAdded: String? = null,
        val heartRemoved: String? = null,
        val likeAdded: String? = null,
        val likeRemoved: String? = null,
        val dislikeAdded: String? = null,
        val dislikeRemoved: String? = null,
        val laughAdded: String? = null,
        val laughRemoved: String? = null,
        val emphasisAdded: String? = null,
        val emphasisRemoved: String? = null,
        val questionAdded: String? = null,
        val questionRemoved: String? = null,
    )

    data class TargetCandidate(
        val id: Long,
        val threadId: Long,
        val timestamp: Long,
        val carrier: Carrier,
    )

    sealed class TargetResolution {
        data class Resolved(val candidate: TargetCandidate) : TargetResolution()
        object NotFound : TargetResolution()
        data class Ambiguous(val candidateIds: List<Long>) : TargetResolution()
    }

    private val classicInboundPatterns = ClassicReaction.values().flatMap { reaction ->
        listOf(
            fixedPattern(reaction.addedPrefix, Operation.ADD, reaction.emoji),
            fixedPattern(reaction.removedPrefix, Operation.REMOVE, reaction.emoji),
        )
    }

    private val googleInboundPatterns = listOf(
        InboundPattern(
            regex = Regex(
                "(?s)\\u200a[^\\u200b\\u200a]*\\u200b([^\\u200b]+)\\u200b" +
                    "[^\\u200b\\u200a]*\\u200a(.+)\\u200a[^\\u200b\\u200a]*\\u200a"
            ),
            operation = Operation.ADD,
            emojiGroup = 1,
            targetGroup = 2,
        ),
        InboundPattern(
            regex = Regex(
                "(?s)\\u200a[^\\u200c\\u200a]*\\u200c([^\\u200c]+)\\u200c" +
                    "[^\\u200c\\u200a]*\\u200a(.+)\\u200a[^\\u200c\\u200a]*\\u200a"
            ),
            operation = Operation.REMOVE,
            emojiGroup = 1,
            targetGroup = 2,
        ),
    )

    private val malformedApplePrefixes = ClassicReaction.values().flatMap { reaction ->
        listOf(reaction.addedPrefix, reaction.removedPrefix)
    }

    fun encode(
        operation: Operation,
        reaction: ClassicReaction,
        target: Carrier,
    ): EncodeResult {
        val targetBody = when (val validation = validateCarrier(target, enforceTargetLimit = true)) {
            is CarrierValidation.Valid -> validation.body
            is CarrierValidation.Invalid -> return EncodeResult.Rejected(validation.reason)
        }
        val prefix = when (operation) {
            Operation.ADD -> reaction.addedPrefix
            Operation.REMOVE -> reaction.removedPrefix
        }

        val encoded = prefix + LEFT_DOUBLE_QUOTE + targetBody + RIGHT_DOUBLE_QUOTE
        // The curly delimiters force UCS-2 encoding. Android allows 70 UTF-16 code units in one
        // UCS-2 SMS segment, so the complete carrier must fit rather than only the quoted target.
        if (encoded.length > MAX_SINGLE_SEGMENT_UCS2_UNITS) {
            return EncodeResult.Rejected(RejectionReason.CARRIER_EXCEEDS_ONE_SEGMENT)
        }
        return EncodeResult.Encoded(encoded)
    }

    fun decode(
        carrier: Carrier,
        localizedPatterns: List<InboundPattern> = emptyList(),
    ): DecodeResult {
        val body = when (val validation = validateCarrier(carrier, enforceTargetLimit = false)) {
            is CarrierValidation.Valid -> validation.body
            is CarrierValidation.Invalid -> return DecodeResult.Rejected(validation.reason)
        }
        val patterns = classicInboundPatterns + googleInboundPatterns + localizedPatterns
        val semanticMatches = linkedSetOf<Reaction>()
        var malformedMatch = false
        var unsupportedMatch = false

        patterns.forEach { pattern ->
            val match = pattern.regex.matchEntire(body) ?: return@forEach
            val target = match.groupValues.getOrNull(pattern.targetGroup)
            val emoji = pattern.fixedEmoji
                ?: pattern.emojiGroup?.let(match.groupValues::getOrNull)
            if (target == null || emoji == null || emoji.isEmpty()) {
                malformedMatch = true
                return@forEach
            }
            if (emoji !in supportedInboundEmoji) {
                unsupportedMatch = true
                return@forEach
            }
            when (val validation = validateTargetBody(target)) {
                null -> semanticMatches += Reaction(pattern.operation, emoji, target)
                else -> return DecodeResult.Rejected(validation)
            }
        }

        return when {
            semanticMatches.size == 1 -> DecodeResult.Decoded(semanticMatches.single())
            semanticMatches.size > 1 -> DecodeResult.Rejected(RejectionReason.AMBIGUOUS_REACTION)
            malformedMatch || looksLikeMalformedAppleReaction(body) ->
                DecodeResult.Rejected(RejectionReason.MALFORMED_REACTION)
            unsupportedMatch -> DecodeResult.Rejected(RejectionReason.UNSUPPORTED_REACTION)
            else -> DecodeResult.NotReaction
        }
    }

    fun resolveTarget(
        targetBody: String,
        reactionThreadId: Long,
        reactionTimestamp: Long,
        candidates: Iterable<TargetCandidate>,
    ): TargetResolution {
        val matching = candidates
            .asSequence()
            .filter { candidate -> candidate.threadId == reactionThreadId }
            .filter { candidate -> candidate.timestamp < reactionTimestamp }
            .filter { candidate ->
                val candidateBody = when (
                    val validation = validateCarrier(candidate.carrier, enforceTargetLimit = false)
                ) {
                    is CarrierValidation.Valid -> validation.body
                    is CarrierValidation.Invalid -> return@filter false
                }
                candidateBody == targetBody || targetBody.matchesTruncatedCandidate(candidateBody)
            }
            .distinctBy(TargetCandidate::id)
            .toList()

        return when (matching.size) {
            0 -> TargetResolution.NotFound
            1 -> TargetResolution.Resolved(matching.single())
            else -> TargetResolution.Ambiguous(matching.map(TargetCandidate::id))
        }
    }

    fun groupOrderForLocale(localeTag: String): GroupOrder = when (localeTag) {
        "zh_Hant" -> GroupOrder(targetGroup = 1, emojiGroup = 2)
        else -> GroupOrder(targetGroup = 2, emojiGroup = 1)
    }

    fun localizedInboundPatterns(
        localeTag: String,
        patterns: LocalizedPatterns,
    ): List<InboundPattern> {
        val groupOrder = groupOrderForLocale(localeTag)
        val result = mutableListOf<InboundPattern>()

        fun addGeneric(source: String?, operation: Operation) {
            source ?: return
            result += InboundPattern(
                regex = Regex(source),
                operation = operation,
                targetGroup = groupOrder.targetGroup,
                emojiGroup = groupOrder.emojiGroup,
            )
        }

        fun addFixed(source: String?, operation: Operation, emoji: String) {
            source ?: return
            result += InboundPattern(
                regex = Regex(source),
                operation = operation,
                targetGroup = 1,
                fixedEmoji = emoji,
            )
        }

        addFixed(patterns.heartAdded, Operation.ADD, ClassicReaction.HEART.emoji)
        addFixed(patterns.heartRemoved, Operation.REMOVE, ClassicReaction.HEART.emoji)
        addFixed(patterns.likeAdded, Operation.ADD, ClassicReaction.LIKE.emoji)
        addFixed(patterns.likeRemoved, Operation.REMOVE, ClassicReaction.LIKE.emoji)
        addFixed(patterns.dislikeAdded, Operation.ADD, ClassicReaction.DISLIKE.emoji)
        addFixed(patterns.dislikeRemoved, Operation.REMOVE, ClassicReaction.DISLIKE.emoji)
        addFixed(patterns.laughAdded, Operation.ADD, ClassicReaction.LAUGH.emoji)
        addFixed(patterns.laughRemoved, Operation.REMOVE, ClassicReaction.LAUGH.emoji)
        addFixed(patterns.emphasisAdded, Operation.ADD, ClassicReaction.EMPHASIS.emoji)
        addFixed(patterns.emphasisRemoved, Operation.REMOVE, ClassicReaction.EMPHASIS.emoji)
        addFixed(patterns.questionAdded, Operation.ADD, ClassicReaction.QUESTION.emoji)
        addFixed(patterns.questionRemoved, Operation.REMOVE, ClassicReaction.QUESTION.emoji)
        addGeneric(patterns.genericAdded, Operation.ADD)
        addGeneric(patterns.genericRemoved, Operation.REMOVE)

        return result
    }

    private fun fixedPattern(
        prefix: String,
        operation: Operation,
        emoji: String,
    ) = InboundPattern(
        regex = Regex(Regex.escape(prefix) + LEFT_DOUBLE_QUOTE + "(.+)" + RIGHT_DOUBLE_QUOTE, RegexOption.DOT_MATCHES_ALL),
        operation = operation,
        targetGroup = 1,
        fixedEmoji = emoji,
    )

    private fun looksLikeMalformedAppleReaction(body: String): Boolean =
        malformedApplePrefixes.any(body::startsWith)

    private fun String.matchesTruncatedCandidate(candidateBody: String): Boolean {
        if (!endsWith(ELLIPSIS) || length == 1) return false
        val prefix = dropLast(1)
        return candidateBody.length > prefix.length && candidateBody.startsWith(prefix)
    }

    private fun validateTargetBody(body: String): RejectionReason? = when {
        body.isEmpty() -> RejectionReason.EMPTY_BODY
        !body.hasWellFormedUtf16() -> RejectionReason.ILL_FORMED_UTF16
        body.length > MAX_TARGET_UTF16_UNITS -> RejectionReason.TARGET_TOO_LONG
        else -> null
    }

    private fun validateCarrier(
        carrier: Carrier,
        enforceTargetLimit: Boolean,
    ): CarrierValidation {
        if (carrier.mediaPartCount < 0) {
            return CarrierValidation.Invalid(RejectionReason.INVALID_MEDIA_PART_COUNT)
        }
        if (carrier.mediaPartCount != 0) {
            return CarrierValidation.Invalid(RejectionReason.MEDIA_PARTS_PRESENT)
        }
        val body = carrier.body ?: return CarrierValidation.Invalid(RejectionReason.EMPTY_BODY)
        if (body.isEmpty()) return CarrierValidation.Invalid(RejectionReason.EMPTY_BODY)
        if (!body.hasWellFormedUtf16()) {
            return CarrierValidation.Invalid(RejectionReason.ILL_FORMED_UTF16)
        }
        if (enforceTargetLimit && body.length > MAX_TARGET_UTF16_UNITS) {
            return CarrierValidation.Invalid(RejectionReason.TARGET_TOO_LONG)
        }
        return CarrierValidation.Valid(body)
    }

    private fun String.hasWellFormedUtf16(): Boolean {
        var index = 0
        while (index < length) {
            val unit = this[index]
            when {
                unit.isHighSurrogate() -> {
                    if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return false
                    index += 2
                }
                unit.isLowSurrogate() -> return false
                else -> index++
            }
        }
        return true
    }

    private sealed class CarrierValidation {
        data class Valid(val body: String) : CarrierValidation()
        data class Invalid(val reason: RejectionReason) : CarrierValidation()
    }

    private const val LEFT_DOUBLE_QUOTE = '\u201C'
    private const val RIGHT_DOUBLE_QUOTE = '\u201D'
    private const val ELLIPSIS = '\u2026'

    private val supportedInboundEmoji = ClassicReaction.values().mapTo(linkedSetOf()) { it.emoji }
}
