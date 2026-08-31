package com.moez.QKSMS.repository

import com.moez.QKSMS.repository.ReactionWireCodec.Carrier
import com.moez.QKSMS.repository.ReactionWireCodec.ClassicReaction
import com.moez.QKSMS.repository.ReactionWireCodec.DecodeResult
import com.moez.QKSMS.repository.ReactionWireCodec.EncodeResult
import com.moez.QKSMS.repository.ReactionWireCodec.InboundPattern
import com.moez.QKSMS.repository.ReactionWireCodec.LocalizedPatterns
import com.moez.QKSMS.repository.ReactionWireCodec.Operation
import com.moez.QKSMS.repository.ReactionWireCodec.Reaction
import com.moez.QKSMS.repository.ReactionWireCodec.RejectionReason
import com.moez.QKSMS.repository.ReactionWireCodec.TargetCandidate
import com.moez.QKSMS.repository.ReactionWireCodec.TargetResolution
import com.moez.QKSMS.repository.ReactionWireCodec.Transport
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.model.MmsPart
import dev.octoshrimpy.quik.repository.ReactionCarrierSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactionWireCodecTest {
    @Test
    fun `encodes the twelve Apple English classic fixtures byte for byte`() {
        val fixtures = listOf(
            Fixture(Operation.ADD, ClassicReaction.HEART, "Loved “hello”"),
            Fixture(Operation.REMOVE, ClassicReaction.HEART, "Removed a heart from “hello”"),
            Fixture(Operation.ADD, ClassicReaction.LIKE, "Liked “hello”"),
            Fixture(Operation.REMOVE, ClassicReaction.LIKE, "Removed a like from “hello”"),
            Fixture(Operation.ADD, ClassicReaction.DISLIKE, "Disliked “hello”"),
            Fixture(Operation.REMOVE, ClassicReaction.DISLIKE, "Removed a dislike from “hello”"),
            Fixture(Operation.ADD, ClassicReaction.LAUGH, "Laughed at “hello”"),
            Fixture(Operation.REMOVE, ClassicReaction.LAUGH, "Removed a laugh from “hello”"),
            Fixture(Operation.ADD, ClassicReaction.EMPHASIS, "Emphasized “hello”"),
            Fixture(Operation.REMOVE, ClassicReaction.EMPHASIS, "Removed an exclamation from “hello”"),
            Fixture(Operation.ADD, ClassicReaction.QUESTION, "Questioned “hello”"),
            Fixture(Operation.REMOVE, ClassicReaction.QUESTION, "Removed a question mark from “hello”"),
        )

        fixtures.forEach { fixture ->
            val result = ReactionWireCodec.encode(
                fixture.operation,
                fixture.reaction,
                sms("hello"),
            ) as EncodeResult.Encoded

            assertEquals(fixture.expectedBody, result.body)
            assertArrayEquals(
                fixture.expectedBody.toByteArray(Charsets.UTF_8),
                result.body.toByteArray(Charsets.UTF_8),
            )
        }
    }

    @Test
    fun `accepts exact SMS and text-only MMS targets`() {
        val exact = "  keep whitespace  "

        assertEquals(
            EncodeResult.Encoded("Liked “  keep whitespace  ”"),
            ReactionWireCodec.encode(Operation.ADD, ClassicReaction.LIKE, sms(exact)),
        )
        assertEquals(
            EncodeResult.Encoded("Liked “  keep whitespace  ”"),
            ReactionWireCodec.encode(Operation.ADD, ClassicReaction.LIKE, mms(exact)),
        )
        assertEquals(
            EncodeResult.Rejected(RejectionReason.MEDIA_PARTS_PRESENT),
            ReactionWireCodec.encode(
                Operation.ADD,
                ClassicReaction.LIKE,
                mms(exact, mediaPartCount = 1),
            ),
        )
        assertEquals(
            EncodeResult.Rejected(RejectionReason.EMPTY_BODY),
            ReactionWireCodec.encode(Operation.ADD, ClassicReaction.LIKE, sms("")),
        )
    }

    @Test
    fun `enforces the inbound 70 UTF-16 unit target limit`() {
        val seventyUnits = "😀" + "a".repeat(68)

        assertEquals(70, seventyUnits.length)
        assertEquals(
            DecodeResult.Decoded(Reaction(Operation.ADD, "❤️", seventyUnits)),
            ReactionWireCodec.decode(sms("Loved “$seventyUnits”")),
        )

        val inbound = "Loved “${"a".repeat(71)}”"
        assertEquals(
            DecodeResult.Rejected(RejectionReason.TARGET_TOO_LONG),
            ReactionWireCodec.decode(sms(inbound)),
        )
    }

    @Test
    fun `validates the complete UCS-2 carrier before creating an attempt`() {
        ClassicReaction.values().forEach { reaction ->
            Operation.values().forEach { operation ->
                val prefix = when (operation) {
                    Operation.ADD -> reaction.addedPrefix
                    Operation.REMOVE -> reaction.removedPrefix
                }
                val exactTargetLength = ReactionWireCodec.MAX_SINGLE_SEGMENT_UCS2_UNITS -
                    prefix.length - 2
                val exactTarget = "a".repeat(exactTargetLength)

                val encoded = ReactionWireCodec.encode(
                    operation,
                    reaction,
                    sms(exactTarget),
                ) as EncodeResult.Encoded
                assertEquals(
                    "$operation $reaction",
                    ReactionWireCodec.MAX_SINGLE_SEGMENT_UCS2_UNITS,
                    encoded.body.length,
                )
                assertEquals(
                    "$operation $reaction",
                    EncodeResult.Rejected(RejectionReason.CARRIER_EXCEEDS_ONE_SEGMENT),
                    ReactionWireCodec.encode(
                        operation,
                        reaction,
                        sms("a".repeat(exactTargetLength + 1)),
                    ),
                )
            }
        }

        assertEquals(
            EncodeResult.Rejected(RejectionReason.CARRIER_EXCEEDS_ONE_SEGMENT),
            ReactionWireCodec.encode(
                Operation.ADD,
                ClassicReaction.LIKE,
                sms("😀" + "a".repeat(61)),
            ),
        )
    }

    @Test
    fun `rejects ill-formed UTF-16 and accepts paired surrogates`() {
        val loneHigh = String(charArrayOf('\uD83D'))
        val loneLow = String(charArrayOf('\uDE00'))

        assertTrue(
            ReactionWireCodec.encode(
                Operation.ADD,
                ClassicReaction.HEART,
                sms("paired 😀"),
            ) is EncodeResult.Encoded
        )
        assertEquals(
            EncodeResult.Rejected(RejectionReason.ILL_FORMED_UTF16),
            ReactionWireCodec.encode(Operation.ADD, ClassicReaction.HEART, sms(loneHigh)),
        )
        assertEquals(
            DecodeResult.Rejected(RejectionReason.ILL_FORMED_UTF16),
            ReactionWireCodec.decode(sms("Liked “ok”$loneLow")),
        )
    }

    @Test
    fun `round trips internal curly quotes and newlines without normalization`() {
        val target = "first “quoted” line\nsecond line"
        val encoded = ReactionWireCodec.encode(
            Operation.REMOVE,
            ClassicReaction.QUESTION,
            sms(target),
        ) as EncodeResult.Encoded

        assertEquals("Removed a question mark from “first “quoted” line\nsecond line”", encoded.body)
        assertEquals(
            DecodeResult.Decoded(Reaction(Operation.REMOVE, "❓", target)),
            ReactionWireCodec.decode(mms(encoded.body)),
        )
    }

    @Test
    fun `rejects malformed Apple delimiters and leaves ordinary text alone`() {
        listOf(
            "Liked \"hello\"",
            "Liked “hello",
            "Liked hello”",
            "Liked “”",
        ).forEach { body ->
            assertEquals(
                body,
                DecodeResult.Rejected(RejectionReason.MALFORMED_REACTION),
                ReactionWireCodec.decode(sms(body)),
            )
        }
        assertEquals(DecodeResult.NotReaction, ReactionWireCodec.decode(sms("We liked hello")))
    }

    @Test
    fun `uses explicit generic group order for ordinary and zh_Hant locales`() {
        val english = ReactionWireCodec.localizedInboundPatterns(
            "en",
            LocalizedPatterns(genericAdded = "Reacted (.+?) to “(.+?)”"),
        )
        val traditionalChinese = ReactionWireCodec.localizedInboundPatterns(
            "zh_Hant",
            LocalizedPatterns(genericAdded = "對「(.+?)」做出 (.+?) 反應"),
        )

        assertEquals(
            DecodeResult.Decoded(Reaction(Operation.ADD, "👍", "lunch")),
            ReactionWireCodec.decode(sms("Reacted 👍 to “lunch”"), english),
        )
        assertEquals(
            DecodeResult.Decoded(Reaction(Operation.ADD, "👍", "午餐")),
            ReactionWireCodec.decode(sms("對「午餐」做出 👍 反應"), traditionalChinese),
        )
        assertEquals(ReactionWireCodec.GroupOrder(1, 2), ReactionWireCodec.groupOrderForLocale("zh_Hant"))
    }

    @Test
    fun `classic English removals stay unambiguous with localized patterns loaded`() {
        val english = ReactionWireCodec.localizedInboundPatterns(
            "en",
            LocalizedPatterns(
                genericRemoved = "^(?s)Removed (.+?) from “(.+?)”$",
                heartRemoved = "^(?s)Removed a heart from “(.+?)”$",
                likeRemoved = "^(?s)Removed a like from “(.+?)”$",
                dislikeRemoved = "^(?s)Removed a dislike from “(.+?)”$",
                laughRemoved = "^(?s)Removed a laugh from “(.+?)”$",
                emphasisRemoved = "^(?s)Removed an exclamation from “(.+?)”$",
                questionRemoved = "^(?s)Removed a question mark from “(.+?)”$",
            ),
        )

        ClassicReaction.values().forEach { reaction ->
            val body = reaction.removedPrefix + "“hello”"
            assertEquals(
                body,
                DecodeResult.Decoded(Reaction(Operation.REMOVE, reaction.emoji, "hello")),
                ReactionWireCodec.decode(sms(body), english),
            )
        }
    }

    @Test
    fun `fails closed when a French fixture maps one phrase to two classics`() {
        val french = ReactionWireCodec.localizedInboundPatterns(
            "fr",
            LocalizedPatterns(
                heartAdded = "^(?s)Aimé “(.+?)”$",
                likeAdded = "^(?s)Aimé “(.+?)”$",
            ),
        )

        assertEquals(
            DecodeResult.Rejected(RejectionReason.AMBIGUOUS_REACTION),
            ReactionWireCodec.decode(sms("Aimé “bonjour”"), french),
        )
    }

    @Test
    fun `corrected French heart and like phrases map to distinct classics`() {
        val french = ReactionWireCodec.localizedInboundPatterns(
            "fr",
            LocalizedPatterns(
                heartAdded = "^(?s)A adoré “(.+?)”$",
                likeAdded = "^(?s)Aimé “(.+?)”$",
            ),
        )

        assertEquals(
            DecodeResult.Decoded(Reaction(Operation.ADD, "❤️", "bonjour")),
            ReactionWireCodec.decode(sms("A adoré “bonjour”"), french),
        )
        assertEquals(
            DecodeResult.Decoded(Reaction(Operation.ADD, "👍", "bonjour")),
            ReactionWireCodec.decode(sms("Aimé “bonjour”"), french),
        )
    }

    @Test
    fun `rejects stickers and reactions outside the six classic choices`() {
        val english = ReactionWireCodec.localizedInboundPatterns(
            "en",
            LocalizedPatterns(genericAdded = "^(?s)Reacted (.+?) to “(.+?)”$"),
        )

        listOf("🔥", "with a sticker", "thumbs up").forEach { captured ->
            assertEquals(
                captured,
                DecodeResult.Rejected(RejectionReason.UNSUPPORTED_REACTION),
                ReactionWireCodec.decode(sms("Reacted $captured to “lunch”"), english),
            )
        }
    }

    @Test
    fun `requires a full pattern match`() {
        val localized = ReactionWireCodec.localizedInboundPatterns(
            "en",
            LocalizedPatterns(genericAdded = "Reacted (.+?) to “(.+?)”"),
        )

        assertEquals(
            DecodeResult.NotReaction,
            ReactionWireCodec.decode(sms("prefix Reacted 🔥 to “lunch” suffix"), localized),
        )
    }

    @Test
    fun `deduplicates equal semantics and rejects conflicting semantics`() {
        val same = InboundPattern(
            regex = Regex("Liked “(.+)”"),
            operation = Operation.ADD,
            targetGroup = 1,
            fixedEmoji = "👍",
        )
        val conflict = same.copy(fixedEmoji = "👎")

        assertEquals(
            DecodeResult.Decoded(Reaction(Operation.ADD, "👍", "hello")),
            ReactionWireCodec.decode(sms("Liked “hello”"), listOf(same)),
        )
        assertEquals(
            DecodeResult.Rejected(RejectionReason.AMBIGUOUS_REACTION),
            ReactionWireCodec.decode(sms("Liked “hello”"), listOf(conflict)),
        )
    }

    @Test
    fun `decodes supported Google reaction carriers but never emits a Google body`() {
        val google = "\u200aadded\u200b👍\u200bto\u200alunch\u200amessage\u200a"

        assertEquals(
            DecodeResult.Decoded(Reaction(Operation.ADD, "👍", "lunch")),
            ReactionWireCodec.decode(sms(google)),
        )
        assertEquals(
            DecodeResult.Rejected(RejectionReason.UNSUPPORTED_REACTION),
            ReactionWireCodec.decode(
                sms("\u200aadded\u200b🔥\u200bto\u200alunch\u200amessage\u200a")
            ),
        )
        val emitted = ReactionWireCodec.encode(Operation.ADD, ClassicReaction.LIKE, sms("lunch"))
            as EncodeResult.Encoded
        assertEquals("Liked “lunch”", emitted.body)
    }

    @Test
    fun `rejects malformed MMS reaction carriers`() {
        assertEquals(
            DecodeResult.Rejected(RejectionReason.MEDIA_PARTS_PRESENT),
            ReactionWireCodec.decode(mms("Liked “hello”", mediaPartCount = 2)),
        )
        assertEquals(
            DecodeResult.Rejected(RejectionReason.INVALID_MEDIA_PART_COUNT),
            ReactionWireCodec.decode(mms("Liked “hello”", mediaPartCount = -1)),
        )
    }

    @Test
    fun `resolves only one exact same-thread earlier target`() {
        val reaction = Reaction(Operation.ADD, "👍", "same body")
        val exact = candidate(1, threadId = 7, timestamp = 99, body = "same body")
        val wrongThread = candidate(2, threadId = 8, timestamp = 98, body = "same body")
        val later = candidate(3, threadId = 7, timestamp = 101, body = "same body")
        val changedWhitespace = candidate(4, threadId = 7, timestamp = 97, body = "same body ")

        assertEquals(
            TargetResolution.NotFound,
            ReactionWireCodec.resolveTarget(reaction.targetBody, 7, 100, emptyList()),
        )
        assertEquals(
            TargetResolution.Resolved(exact),
            ReactionWireCodec.resolveTarget(
                reaction.targetBody,
                7,
                100,
                listOf(exact, wrongThread, later, changedWhitespace),
            ),
        )
        assertEquals(
            TargetResolution.Ambiguous(listOf(1, 5)),
            ReactionWireCodec.resolveTarget(
                reaction.targetBody,
                7,
                100,
                listOf(exact, candidate(5, threadId = 7, timestamp = 96, body = "same body")),
            ),
        )
    }

    @Test
    fun `resolves a terminal-ellipsis prefix only when exactly one earlier target matches`() {
        val unique = candidate(
            1,
            threadId = 7,
            timestamp = 90,
            body = "a long message after prefix " + "x".repeat(70),
        )
        val duplicate = candidate(2, threadId = 7, timestamp = 91, body = "a long message before suffix")

        assertEquals(
            TargetResolution.Resolved(unique),
            ReactionWireCodec.resolveTarget("a long message aft…", 7, 100, listOf(unique)),
        )
        assertEquals(
            TargetResolution.Ambiguous(listOf(1, 2)),
            ReactionWireCodec.resolveTarget("a long message …", 7, 100, listOf(unique, duplicate)),
        )
        assertEquals(
            TargetResolution.NotFound,
            ReactionWireCodec.resolveTarget("a long… message", 7, 100, listOf(unique)),
        )
        assertEquals(
            TargetResolution.NotFound,
            ReactionWireCodec.resolveTarget("…", 7, 100, listOf(unique)),
        )
    }

    @Test
    fun `carrier source counts MMS media and keeps SMS media-free`() {
        val sms = Message().apply {
            type = Message.TYPE_SMS
            body = "Liked “hello”"
        }
        val textAndMediaMms = Message().apply {
            type = Message.TYPE_MMS
            parts.add(MmsPart().apply { type = "application/smil" })
            parts.add(MmsPart().apply { type = "text/plain" })
            parts.add(MmsPart().apply { type = "image/jpeg" })
        }

        assertEquals(
            ReactionCarrierSource(ReactionCarrierSource.Transport.SMS, 0),
            ReactionCarrierSource.from(sms),
        )
        assertEquals(
            ReactionCarrierSource(ReactionCarrierSource.Transport.MMS, 1),
            ReactionCarrierSource.from(textAndMediaMms),
        )
    }

    private fun sms(body: String?) = Carrier(Transport.SMS, body)

    private fun mms(body: String?, mediaPartCount: Int = 0) =
        Carrier(Transport.MMS, body, mediaPartCount)

    private fun candidate(
        id: Long,
        threadId: Long,
        timestamp: Long,
        body: String,
    ) = TargetCandidate(id, threadId, timestamp, sms(body))

    private data class Fixture(
        val operation: Operation,
        val reaction: ClassicReaction,
        val expectedBody: String,
    )
}
