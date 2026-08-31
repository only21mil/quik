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
package dev.octoshrimpy.quik.feature.compose

import dev.octoshrimpy.quik.repository.InMemoryReactionPendingAttemptGate
import dev.octoshrimpy.quik.interactor.SendReaction
import dev.octoshrimpy.quik.repository.SendReactionResult
import io.reactivex.Flowable
import io.reactivex.processors.PublishProcessor
import org.junit.Assert.assertEquals
import org.junit.Test

class ReactionSubmissionTest {
    private fun validFacts() = ReactionSubmissionFacts(
        selectedMessageId = 42L,
        selectedEmoji = "👍",
        navigationThreadId = 7L,
        stateThreadId = 7L,
        currentNavigationThreadId = 7L,
        currentStateThreadId = 7L,
        refreshedConversationId = 7L,
        refreshedTargetId = 42L,
        targetThreadId = 7L,
        targetSubscriptionId = 3,
        selectedSubscriptionId = 3,
        currentSelectedSubscriptionId = 3,
        activeSubscriptionIds = setOf(3),
        selectedRegion = "us",
        region = "us",
        isDefaultSms = true,
        hasSendSms = true,
        targetIsReaction = false,
        targetIsFailed = false,
        targetHasText = true,
        targetHasNonTextParts = false,
        localReactionEmojis = emptySet(),
    )

    @Test
    fun `fixed emoji mapping produces exact transport parameters`() {
        val mappings = listOf(
            "❤️" to SendReaction.Reaction.HEART,
            "👍" to SendReaction.Reaction.LIKE,
            "👎" to SendReaction.Reaction.DISLIKE,
            "😂" to SendReaction.Reaction.LAUGH,
            "‼️" to SendReaction.Reaction.EMPHASIS,
            "❓" to SendReaction.Reaction.QUESTION,
        )

        mappings.forEach { (emoji, reaction) ->
            val decision = ReactionSubmissionPolicy.decide(validFacts().copy(selectedEmoji = emoji))
                as ReactionSubmissionDecision.Send

            assertEquals(42L, decision.params.targetMessageId)
            assertEquals(7L, decision.params.expectedThreadId)
            assertEquals(3, decision.params.expectedSubscriptionId)
            assertEquals("US", decision.params.region)
            assertEquals(reaction, decision.params.reaction)
        }
    }

    @Test
    fun `unsupported emoji is rejected`() {
        assertRejected(
            validFacts().copy(selectedEmoji = "🚀"),
            ReactionSubmissionRejection.UNSUPPORTED_REACTION,
        )
    }

    @Test
    fun `default role and send permission are revalidated`() {
        assertRejected(
            validFacts().copy(isDefaultSms = false),
            ReactionSubmissionRejection.DEFAULT_SMS_REQUIRED,
        )
        assertRejected(
            validFacts().copy(hasSendSms = false),
            ReactionSubmissionRejection.SEND_SMS_REQUIRED,
        )
    }

    @Test
    fun `thread identity must match state conversation and target`() {
        assertRejected(
            validFacts().copy(stateThreadId = 8L),
            ReactionSubmissionRejection.NAVIGATION_CHANGED,
        )
        assertRejected(
            validFacts().copy(refreshedConversationId = null),
            ReactionSubmissionRejection.NAVIGATION_CHANGED,
        )
        assertRejected(
            validFacts().copy(targetThreadId = 8L),
            ReactionSubmissionRejection.TARGET_CHANGED,
        )
        assertRejected(
            validFacts().copy(refreshedTargetId = null),
            ReactionSubmissionRejection.TARGET_CHANGED,
        )
    }

    @Test
    fun `navigation changed during refresh is rejected before dispatch`() {
        assertRejected(
            validFacts().copy(currentNavigationThreadId = 8L),
            ReactionSubmissionRejection.NAVIGATION_CHANGED,
        )
        assertRejected(
            validFacts().copy(currentStateThreadId = 8L),
            ReactionSubmissionRejection.NAVIGATION_CHANGED,
        )
    }

    @Test
    fun `subscription must be selected active and exact`() {
        assertRejected(
            validFacts().copy(
                selectedSubscriptionId = null,
                currentSelectedSubscriptionId = null,
                activeSubscriptionIds = emptySet(),
            ),
            ReactionSubmissionRejection.SUBSCRIPTION_REQUIRED,
        )
        assertRejected(
            validFacts().copy(currentSelectedSubscriptionId = 4),
            ReactionSubmissionRejection.SUBSCRIPTION_CHANGED,
        )
        assertRejected(
            validFacts().copy(activeSubscriptionIds = setOf(4)),
            ReactionSubmissionRejection.SUBSCRIPTION_CHANGED,
        )
        assertRejected(
            validFacts().copy(targetSubscriptionId = 4),
            ReactionSubmissionRejection.SUBSCRIPTION_CHANGED,
        )

        val singleActiveSubscription = ReactionSubmissionPolicy.decide(
            validFacts().copy(
                selectedSubscriptionId = null,
                currentSelectedSubscriptionId = null,
            )
        ) as ReactionSubmissionDecision.Send
        assertEquals(3, singleActiveSubscription.params.expectedSubscriptionId)
    }

    @Test
    fun `region and message eligibility fail closed`() {
        assertRejected(
            validFacts().copy(region = ""),
            ReactionSubmissionRejection.REGION_UNAVAILABLE,
        )
        assertRejected(
            validFacts().copy(region = "CA"),
            ReactionSubmissionRejection.REGION_CHANGED,
        )
        listOf(
            validFacts().copy(targetIsReaction = true),
            validFacts().copy(targetIsFailed = true),
            validFacts().copy(targetHasText = false),
            validFacts().copy(targetHasNonTextParts = true),
        ).forEach { facts ->
            assertRejected(facts, ReactionSubmissionRejection.INELIGIBLE_TARGET)
        }
    }

    @Test
    fun `same local reaction removes and a different reaction adds`() {
        val remove = ReactionSubmissionPolicy.decide(
            validFacts().copy(localReactionEmojis = setOf("👍"))
        ) as ReactionSubmissionDecision.Send
        val add = ReactionSubmissionPolicy.decide(
            validFacts().copy(localReactionEmojis = setOf("❤️"))
        ) as ReactionSubmissionDecision.Send

        assertEquals(SendReaction.ReactionOperation.REMOVE, remove.params.operation)
        assertEquals(SendReaction.ReactionOperation.ADD, add.params.operation)
    }

    @Test
    fun `accepted decision invokes transport once and rejected decision never invokes it`() {
        var calls = 0
        val dispatcher = ReactionRequestDispatcher(
            submit = { params ->
                calls += 1
                assertEquals(42L, params.targetMessageId)
                Flowable.just(SendReactionResult.Submitted("attempt-1"))
            }
        )
        val accepted = ReactionSubmissionPolicy.decide(validFacts())

        dispatcher.dispatch(accepted)
            .test()
            .assertValue(ReactionDispatchResult.Submitted("attempt-1"))
            .assertComplete()
        assertEquals(1, calls)

        dispatcher.dispatch(
            ReactionSubmissionDecision.Reject(ReactionSubmissionRejection.TARGET_CHANGED)
        ).test().assertValue(
            ReactionDispatchResult.Rejected(ReactionSubmissionRejection.TARGET_CHANGED)
        ).assertComplete()
        assertEquals(1, calls)
    }

    @Test
    fun `pending target rejects a rapid second submission and remains closed after submit`() {
        val transport = PublishProcessor.create<SendReactionResult>()
        var calls = 0
        val coordinator = ReactionSubmissionCoordinator(
            dispatcher = ReactionRequestDispatcher(
                submit = {
                    calls += 1
                    transport
                }
            ),
            pendingAttemptGate = InMemoryReactionPendingAttemptGate(),
        )
        val decision = ReactionSubmissionPolicy.decide(validFacts())
        val first = coordinator.dispatch(decision).test()

        coordinator.dispatch(decision)
            .test()
            .assertValue(
                ReactionDispatchResult.Rejected(
                    ReactionSubmissionRejection.ATTEMPT_PENDING
                )
            )
            .assertComplete()
        assertEquals(1, calls)

        transport.onNext(SendReactionResult.Submitted("attempt-1"))
        transport.onComplete()
        first.assertValue(ReactionDispatchResult.Submitted("attempt-1")).assertComplete()

        coordinator.dispatch(decision)
            .test()
            .assertValue(
                ReactionDispatchResult.Rejected(
                    ReactionSubmissionRejection.ATTEMPT_PENDING
                )
            )
            .assertComplete()
        assertEquals(1, calls)
    }

    @Test
    fun `pending gate is per target`() {
        var calls = 0
        val coordinator = ReactionSubmissionCoordinator(
            dispatcher = ReactionRequestDispatcher(
                submit = {
                    calls += 1
                    Flowable.never()
                }
            ),
            pendingAttemptGate = InMemoryReactionPendingAttemptGate(),
        )
        val first = ReactionSubmissionPolicy.decide(validFacts())
        val second = ReactionSubmissionPolicy.decide(
            validFacts().copy(selectedMessageId = 43L, refreshedTargetId = 43L)
        )

        coordinator.dispatch(first).test()
        coordinator.dispatch(second).test()

        assertEquals(2, calls)
    }

    @Test
    fun `pre-submission rejection releases reservation`() {
        var calls = 0
        val coordinator = ReactionSubmissionCoordinator(
            dispatcher = ReactionRequestDispatcher(
                submit = {
                    calls += 1
                    Flowable.just(
                        SendReactionResult.Rejected(SendReactionResult.Rejection.TARGET_CHANGED)
                    )
                }
            ),
            pendingAttemptGate = InMemoryReactionPendingAttemptGate(),
        )
        val decision = ReactionSubmissionPolicy.decide(validFacts())

        coordinator.dispatch(decision).test().assertComplete()
        coordinator.dispatch(decision).test().assertComplete()

        assertEquals(2, calls)
    }

    @Test
    fun `transport errors log only throwable classes before deterministic rejection`() {
        var logged: ReactionSubmissionErrorLog? = null
        val dispatcher = ReactionRequestDispatcher(
            submit = {
                Flowable.error(
                    IllegalStateException(
                        "address and message identifiers",
                        IllegalArgumentException("message body"),
                    )
                )
            },
            logError = { summary -> logged = summary },
        )

        dispatcher.dispatch(ReactionSubmissionPolicy.decide(validFacts()))
            .test()
            .assertValue(
                ReactionDispatchResult.Rejected(
                    ReactionSubmissionRejection.TRANSPORT_REJECTED
                )
            )
            .assertComplete()
        assertEquals(
            ReactionSubmissionErrorLog(
                throwableClass = IllegalStateException::class.java.name,
                causeClass = IllegalArgumentException::class.java.name,
            ),
            logged,
        )
    }

    private fun assertRejected(
        facts: ReactionSubmissionFacts,
        reason: ReactionSubmissionRejection,
    ) {
        assertEquals(
            ReactionSubmissionDecision.Reject(reason),
            ReactionSubmissionPolicy.decide(facts),
        )
    }
}
