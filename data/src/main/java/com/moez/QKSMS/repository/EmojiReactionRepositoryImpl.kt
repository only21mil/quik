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

import android.content.Context
import com.moez.QKSMS.repository.ReactionWireCodec
import com.squareup.moshi.Moshi
import dev.octoshrimpy.quik.model.EmojiReaction
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.model.ReactionAttempt
import dev.octoshrimpy.quik.util.EmojiPatternStrings
import io.realm.Realm
import io.realm.Sort
import timber.log.Timber
import javax.inject.Inject

class EmojiReactionRepositoryImpl @Inject constructor(
    private val context: Context,
    private val moshi: Moshi,
) : EmojiReactionRepository {
    private val inboundPatterns = mutableListOf<ReactionWireCodec.InboundPattern>()

    init {
        val assetEntries = loadEmojiPatternEntriesFromAssets()
        assetEntries.forEach { (localeTag, strings) ->
            try {
                addPatternsForLocaleStrings(localeTag, strings)
            } catch (e: Exception) {
                Timber.w(e, "Failed to load asset patterns for locale: $localeTag")
            }
        }
        Timber.i("Loaded ${assetEntries.size} emoji reaction pattern sets")
    }

    private fun addPatternsForLocaleStrings(
        localeTag: String,
        strings: EmojiPatternStrings,
    ) {
        inboundPatterns += ReactionWireCodec.localizedInboundPatterns(
            localeTag,
            ReactionWireCodec.LocalizedPatterns(
                genericAdded = strings.iosGenericAdded,
                genericRemoved = strings.iosGenericRemoved,
                heartAdded = strings.iosHeartAdded,
                heartRemoved = strings.iosHeartRemoved,
                likeAdded = strings.iosLikeAdded,
                likeRemoved = strings.iosLikeRemoved,
                dislikeAdded = strings.iosDislikeAdded,
                dislikeRemoved = strings.iosDislikeRemoved,
                laughAdded = strings.iosLaughAdded,
                laughRemoved = strings.iosLaughRemoved,
                emphasisAdded = strings.iosExclamationAdded,
                emphasisRemoved = strings.iosExclamationRemoved,
                questionAdded = strings.iosQuestionMarkAdded,
                questionRemoved = strings.iosQuestionMarkRemoved,
            ),
        )

        Timber.d("Loaded emoji regex patterns for $localeTag from assets")
    }

    private fun loadEmojiPatternEntriesFromAssets(): List<Pair<String, EmojiPatternStrings>> {
        val dir = "emojis"
        val files = context.assets.list(dir) ?: emptyArray()
        return files.filter { it.endsWith(".json", ignoreCase = true) }
            .mapNotNull { filename ->
                val localeTag = filename.removeSuffix(".json")
                try {
                    val json = context.assets.open("$dir/$filename")
                        .bufferedReader().use {
                            it.readText()
                        }
                    val data = parseEmojiPatternsJson(json)
                    localeTag to data
                } catch (e: Exception) {
                    Timber.w(e, "Failed parsing emoji patterns asset: $filename")
                    null
                }
            }
    }

    private fun parseEmojiPatternsJson(json: String): EmojiPatternStrings {
        val adapter = moshi.adapter(EmojiPatternStrings::class.java)
        return requireNotNull(adapter.fromJson(json)) { "Invalid emoji patterns JSON" }
    }

    override fun parseEmojiReaction(
        body: String,
        source: ReactionCarrierSource,
    ): ParsedEmojiReaction? {
        return when (
            val result = ReactionWireCodec.decode(
                ReactionWireCodec.Carrier(
                    transport = when (source.transport) {
                        ReactionCarrierSource.Transport.SMS -> ReactionWireCodec.Transport.SMS
                        ReactionCarrierSource.Transport.MMS -> ReactionWireCodec.Transport.MMS
                    },
                    body = body,
                    mediaPartCount = source.mediaPartCount,
                ),
                inboundPatterns,
            )
        ) {
            is ReactionWireCodec.DecodeResult.Decoded -> {
                Timber.d("Reaction carrier decoded")
                ParsedEmojiReaction(
                    emoji = result.reaction.emoji,
                    originalMessage = result.reaction.targetBody,
                    isRemoval = result.reaction.operation == ReactionWireCodec.Operation.REMOVE,
                )
            }
            is ReactionWireCodec.DecodeResult.Rejected -> {
                Timber.w("Rejected reaction carrier: ${result.reason}")
                null
            }
            ReactionWireCodec.DecodeResult.NotReaction -> null
        }
    }

    override fun processEmojiReaction(reactionMessage: Message, realm: Realm): Boolean {
        // Re-establish quarantine at the parser boundary too. Full sync deliberately clears the
        // derived isEmojiReaction flag before reparsing, and ambiguous pre-stage carriers do not
        // have an exact transport key yet.
        ReactionAttemptReconciler.reconcileCarrier(realm, reactionMessage)
        val transportKey = ReactionTransportPolicy.ProviderIdentity.from(reactionMessage)?.encode()
        val attempt = transportKey?.let { key ->
            realm.where(ReactionAttempt::class.java)
                .equalTo("transportKey", key)
                .findFirst()
        }
        if (attempt != null) {
            reactionMessage.isEmojiReaction = true
            realm.insertOrUpdate(reactionMessage)
            if (attempt.state != ReactionAttempt.State.SENT.name) return true

            val resolved = ReactionAttemptReconciler.resolve(realm, attempt) ?: return true
            val parsed = parseEmojiReaction(
                reactionMessage.getText(false),
                ReactionCarrierSource.from(reactionMessage),
            ) ?: return true
            if (
                reactionMessage.getText(false) != attempt.body ||
                parsed.originalMessage != resolved.target.getText(false)
            ) {
                return true
            }
            saveEmojiReaction(reactionMessage, parsed, resolved.target, realm)
            return true
        }
        if (reactionMessage.isEmojiReaction) return true

        val parsed = parseEmojiReaction(
            reactionMessage.getText(false),
            ReactionCarrierSource.from(reactionMessage),
        ) ?: return false
        saveEmojiReaction(
            reactionMessage = reactionMessage,
            parsedReaction = parsed,
            targetMessage = findTargetMessage(reactionMessage, parsed.originalMessage, realm),
            realm = realm,
        )
        return true
    }

    /**
     * Search for messages in the same thread with matching text content
     * We'll search recent messages first
     */
    override fun findTargetMessage(
        reactionMessage: Message,
        originalMessageText: String,
        realm: Realm
    ): Message? {
        val startTime = System.currentTimeMillis()
        val messages = realm.where(Message::class.java)
            .equalTo("threadId", reactionMessage.threadId)
            .lessThan("date", reactionMessage.date)
            .sort("date", Sort.DESCENDING)
            .findAll()
        val endTime = System.currentTimeMillis()
        Timber.d("Found ${messages.size} messages as potential emoji targets in ${endTime - startTime}ms")

        val candidates = messages.map { message ->
            val carrier = if (message.isMms()) {
                ReactionWireCodec.Carrier(
                    transport = ReactionWireCodec.Transport.MMS,
                    body = message.getText(false),
                    mediaPartCount = message.parts.count { part ->
                        part.type.lowercase().let { type ->
                            type != "text/plain" && type != "application/smil"
                        }
                    },
                )
            } else {
                ReactionWireCodec.Carrier(
                    transport = ReactionWireCodec.Transport.SMS,
                    body = message.body,
                )
            }
            ReactionWireCodec.TargetCandidate(
                id = message.id,
                threadId = message.threadId,
                timestamp = message.date,
                carrier = carrier,
            )
        }
        val resolution = ReactionWireCodec.resolveTarget(
            targetBody = originalMessageText,
            reactionThreadId = reactionMessage.threadId,
            reactionTimestamp = reactionMessage.date,
            candidates = candidates,
        )
        return when (resolution) {
            is ReactionWireCodec.TargetResolution.Resolved -> {
                val match = messages.single { it.id == resolution.candidate.id }
                Timber.d("Found reaction target")
                match
            }
            is ReactionWireCodec.TargetResolution.Ambiguous -> {
                Timber.w("Ambiguous reaction target")
                null
            }
            ReactionWireCodec.TargetResolution.NotFound -> {
                Timber.w("No target message found for reaction carrier")
                null
            }
        }
    }

    private fun removeEmojiReaction(
        reactionMessage: Message,
        reaction: ParsedEmojiReaction,
        targetMessage: Message?,
        realm: Realm,
    ) {
        if (targetMessage == null) {
            Timber.w("Cannot remove reaction: no target message found")
            return
        }

        val fromMe = EmojiReaction.fromMeForCarrier(reactionMessage.type, reactionMessage.boxId)
        val senderAddress = if (fromMe == true) "" else reactionMessage.address
        val existingReaction = targetMessage.emojiReactions.find { candidate ->
            candidate.senderAddress == senderAddress && candidate.fromMe == fromMe &&
                candidate.emoji == reaction.emoji
        }

        if (existingReaction != null) {
            existingReaction.deleteFromRealm()
            Timber.d("Removed reaction")
        } else {
            Timber.w("No existing reaction found to remove")
            return
        }

        reactionMessage.isEmojiReaction = true
        realm.insertOrUpdate(reactionMessage)
    }

    override fun saveEmojiReaction(
        reactionMessage: Message,
        parsedReaction: ParsedEmojiReaction,
        targetMessage: Message?,
        realm: Realm,
    ) {
        if (parsedReaction.isRemoval) {
            removeEmojiReaction(reactionMessage, parsedReaction, targetMessage, realm)
            return
        }

        if (targetMessage == null) {
            Timber.w("No target message, cannot save reaction")
            return
        }

        val fromMe = EmojiReaction.fromMeForCarrier(reactionMessage.type, reactionMessage.boxId)
        val reaction = EmojiReaction().apply {
            id = EmojiReaction.idForReactionMessage(reactionMessage.id)
            reactionMessageId = reactionMessage.id
            senderAddress = if (fromMe == true) "" else reactionMessage.address
            emoji = parsedReaction.emoji
            originalMessageText = parsedReaction.originalMessage
            threadId = reactionMessage.threadId
            this.fromMe = fromMe
        }
        reactionMessage.isEmojiReaction = true
        realm.insertOrUpdate(reactionMessage)

        // Overwrite any previous reaction from this sender for this target
        val priorFromSender = targetMessage.emojiReactions.filter { candidate ->
            candidate.senderAddress == reaction.senderAddress && candidate.fromMe == reaction.fromMe
        }
        priorFromSender.forEach { it.deleteFromRealm() }

        val managedReaction = realm.copyToRealmOrUpdate(reaction)
        targetMessage.emojiReactions.add(managedReaction)

        Timber.i("Saved reaction")
    }

    override fun deleteAndReparseAllEmojiReactions(realm: Realm, onProgress: (SyncRepository.SyncProgress) -> Unit) {
        val startTime = System.currentTimeMillis()

        realm.delete(EmojiReaction::class.java)
        realm.where(Message::class.java).findAll().map {
            it.isEmojiReaction = false
        }

        val allMessages = realm.where(Message::class.java)
            .beginGroup()
                .beginGroup()
                    .equalTo("type", "sms")
                    .isNotEmpty("body")
                .endGroup()
                .or()
                .beginGroup()
                    .equalTo("type", "mms")
                    .notEqualTo("messageType", 130.toLong())
                    .isNotEmpty("parts.text")
                .endGroup()
            .endGroup()
            .sort("date", Sort.ASCENDING) // parse oldest to newest to handle reactions & removals properly
            .findAll()

        val max = allMessages?.count() ?: 0
        var progress = 0

        allMessages.forEach { message ->
            if (processEmojiReaction(message, realm)) {
                progress++
                // Update the progress every 25 messages, and then at completion
                // that way we don't spam the UI
                if (progress % 25 == 0 || progress == max) {
                    onProgress(
                        SyncRepository.SyncProgress.ParsingEmojis(
                            max = max,
                            progress = progress,
                            indeterminate = false
                        )
                    )
                }
            }
        }

        val endTime = System.currentTimeMillis()
        Timber.d("Deleted and reparsed all emoji reactions in ${endTime - startTime}ms")
    }

}
