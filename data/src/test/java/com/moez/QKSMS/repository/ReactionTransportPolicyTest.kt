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

import dev.octoshrimpy.quik.model.ReactionAttempt
import dev.octoshrimpy.quik.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactionTransportPolicyTest {
    @Test
    fun `staging identity round trips but cannot decode as callback identity`() {
        val identity = ReactionTransportPolicy.StagingIdentity(
            type = Message.TYPE_SMS,
            stagedAt = 1_234L,
            threadId = 72L,
            subId = 3,
        )

        assertEquals(identity, ReactionTransportPolicy.StagingIdentity.decode(identity.encode()))
        assertEquals(null, ReactionTransportPolicy.ProviderIdentity.decode(identity.encode()))
        assertEquals(null, ReactionTransportPolicy.StagingIdentity.decode("stage-v1:sms:0:72:3"))
    }

    @Test
    fun `legacy provider locator accepts only exact sms or mms content uri`() {
        assertEquals(
            ReactionTransportPolicy.LegacyProviderIdentity(Message.TYPE_SMS, 9),
            ReactionTransportPolicy.LegacyProviderIdentity.decode("content://sms/9"),
        )
        assertEquals(null, ReactionTransportPolicy.LegacyProviderIdentity.decode("content://sms/0"))
        assertEquals(null, ReactionTransportPolicy.LegacyProviderIdentity.decode("content://other/9"))
    }
    @Test
    fun `one recipient selects one raw sms`() {
        assertEquals(
            ReactionTransportPolicy.RouteDecision.Selected(
                ReactionTransportPolicy.Route.ONE_TO_ONE_SMS
            ),
            ReactionTransportPolicy.selectRoute(1, targetIsMms = false, providerProvedGroup = false),
        )
    }

    @Test
    fun `provider-proven true group selects one group mms`() {
        assertEquals(
            ReactionTransportPolicy.RouteDecision.Selected(
                ReactionTransportPolicy.Route.TRUE_GROUP_MMS
            ),
            ReactionTransportPolicy.selectRoute(2, targetIsMms = true, providerProvedGroup = true),
        )
    }

    @Test
    fun `multiple recipients never degrade to sms fan-out`() {
        assertEquals(
            ReactionTransportPolicy.RouteDecision.Rejected(
                ReactionTransportPolicy.RouteRejection.FAN_OUT_FORBIDDEN
            ),
            ReactionTransportPolicy.selectRoute(2, targetIsMms = false, providerProvedGroup = false),
        )
        assertEquals(
            ReactionTransportPolicy.RouteDecision.Rejected(
                ReactionTransportPolicy.RouteRejection.GROUP_ROUTE_UNAVAILABLE
            ),
            ReactionTransportPolicy.selectRoute(3, targetIsMms = true, providerProvedGroup = false),
        )
        assertEquals(
            ReactionTransportPolicy.RouteDecision.Rejected(
                ReactionTransportPolicy.RouteRejection.FAN_OUT_FORBIDDEN
            ),
            ReactionTransportPolicy.selectRoute(
                3,
                targetIsMms = true,
                providerProvedGroup = true,
                sendAsGroup = false,
            ),
        )
    }

    @Test
    fun `target identity round trips exact thread and subscription`() {
        val identity = ReactionTransportPolicy.ProviderIdentity("sms", 41, 72, 3)
        assertEquals(identity, ReactionTransportPolicy.ProviderIdentity.decode(identity.encode()))
        assertEquals(null, ReactionTransportPolicy.ProviderIdentity.decode("41"))
    }

    @Test
    fun `provider identity is unchanged when realm message id changes`() {
        fun message(realmId: Long) = Message().apply {
            id = realmId
            type = Message.TYPE_SMS
            contentId = 41
            threadId = 72
            subId = 3
        }

        val beforeReset = ReactionTransportPolicy.ProviderIdentity.from(message(9001))
        val afterReset = ReactionTransportPolicy.ProviderIdentity.from(message(17))

        assertEquals(beforeReset, afterReset)
        assertEquals("v2:sms:41:72:3", beforeReset?.encode())
        assertFalse(beforeReset?.encode()?.contains("9001") == true)
    }

    @Test
    fun `success and failure callbacks select terminal transitions`() {
        assertEquals(
            ReactionTransportPolicy.CallbackDecision.COMMIT_SENT,
            ReactionTransportPolicy.decideCallback(facts(successful = true)),
        )
        assertEquals(
            ReactionTransportPolicy.CallbackDecision.COMMIT_FAILED,
            ReactionTransportPolicy.decideCallback(facts(successful = false)),
        )
    }

    @Test
    fun `duplicate and late callbacks are idempotent`() {
        assertEquals(
            ReactionTransportPolicy.CallbackDecision.ALREADY_TERMINAL,
            ReactionTransportPolicy.decideCallback(
                facts(successful = false).copy(currentState = ReactionAttempt.State.SENT.name)
            ),
        )
        assertEquals(
            ReactionTransportPolicy.CallbackDecision.ALREADY_TERMINAL,
            ReactionTransportPolicy.decideCallback(
                facts(successful = true).copy(currentState = ReactionAttempt.State.FAILED.name)
            ),
        )
    }

    @Test
    fun `handoff and quarantined attempts remain eligible for an exact late callback`() {
        listOf(
            ReactionAttempt.State.HANDOFF.name,
            ReactionAttempt.State.HANDOFF_FAILED.name,
            ReactionAttempt.State.QUARANTINED.name,
        ).forEach { state ->
            assertEquals(
                ReactionTransportPolicy.CallbackDecision.COMMIT_SENT,
                ReactionTransportPolicy.decideCallback(
                    facts(successful = true).copy(currentState = state)
                ),
            )
        }
    }

    @Test
    fun `handoff reconciliation has a fixed terminal bound`() {
        val handoffAt = 1_000L
        assertFalse(
            ReactionAttemptReconciler.isHandoffExpired(
                handoffAt,
                handoffAt + ReactionAttemptReconciler.HANDOFF_TIMEOUT_MILLIS - 1,
            )
        )
        assertTrue(
            ReactionAttemptReconciler.isHandoffExpired(
                handoffAt,
                handoffAt + ReactionAttemptReconciler.HANDOFF_TIMEOUT_MILLIS,
            )
        )
        assertTrue(ReactionAttemptReconciler.isHandoffExpired(0, handoffAt))
    }

    @Test
    fun `thread subscription and transport races fail correlation`() {
        val mismatches = listOf(
            facts(true).copy(carrierThreadId = 73),
            facts(true).copy(targetThreadId = 73),
            facts(true).copy(carrierSubscriptionId = 4),
            facts(true).copy(receivedTransportKey = "content://sms/10"),
            facts(true).copy(currentState = ReactionAttempt.State.PREPARING.name),
        )
        assertTrue(
            mismatches.all { facts ->
                ReactionTransportPolicy.decideCallback(facts) ==
                    ReactionTransportPolicy.CallbackDecision.CORRELATION_MISMATCH
            }
        )
    }

    @Test
    fun `preparing precedes staging and hidden sync precedes submission`() {
        val events = mutableListOf<String>()
        val result = ReactionSubmissionCoordinator().run(
            hooks(
                events = events,
                stage = { events += "stage"; "content://sms/9" },
                sync = { events += "hidden-handoff"; "v2:sms:9:72:3" },
                submit = { _, _ -> events += "platform-submit"; true },
            )
        )
        assertEquals(ReactionSubmissionCoordinator.Outcome.Submitted, result)
        assertEquals(
            listOf("preparing", "stage", "hidden-handoff", "platform-submit", "submitted"),
            events,
        )
        assertFalse(events.contains("badge"))
    }

    @Test
    fun `sync exception discards carrier and fails without submission`() {
        val events = mutableListOf<String>()
        val result = ReactionSubmissionCoordinator().run(
            hooks(
                events = events,
                stage = { events += "stage"; "content://sms/9" },
                sync = { events += "sync"; error("Realm sync failed") },
                submit = { _, _ -> events += "platform-submit"; true },
            )
        )
        assertEquals(
            ReactionSubmissionCoordinator.Outcome.Failed(
                ReactionSubmissionCoordinator.Failure.SYNC
            ),
            result,
        )
        assertEquals(listOf("preparing", "stage", "sync", "discard", "failed"), events)
    }

    @Test
    fun `accepted platform handoff survives a submitted marker crash without resubmission`() {
        val events = mutableListOf<String>()
        val result = ReactionSubmissionCoordinator().run(
            hooks(
                events = events,
                stage = { events += "stage"; "content://sms/9" },
                sync = { events += "hidden-handoff"; "v2:sms:9:72:3" },
                submit = { _, _ -> events += "platform-submit"; true },
                markSubmitted = { events += "submitted-crash"; error("Realm unavailable") },
            )
        )

        assertEquals(ReactionSubmissionCoordinator.Outcome.Submitted, result)
        assertEquals(
            listOf(
                "preparing",
                "stage",
                "hidden-handoff",
                "platform-submit",
                "submitted-crash",
            ),
            events,
        )
        assertEquals(1, events.count { it == "platform-submit" })
        assertFalse(events.contains("failed"))
        assertFalse(events.contains("discard"))
    }

    @Test
    fun `missing realm carrier never releases a staging reservation after restart`() {
        val source = java.io.File(
            requireNotNull(System.getProperty("user.dir")),
            "src/main/java/com/moez/QKSMS/repository/ReactionAttemptReconciler.kt",
        ).readText()
        val staging = source.substring(
            source.indexOf("private fun reconcileStagingGroup"),
            source.indexOf("private fun quarantine"),
        )

        assertFalse(staging.contains("reconcileResolvedAttempt(attempts.single(), null"))
        assertTrue(staging.contains("Absence from Realm does not prove absence from Telephony"))
    }

    private fun facts(successful: Boolean) = ReactionTransportPolicy.CallbackFacts(
        currentState = ReactionAttempt.State.SUBMITTED.name,
        expectedTransportKey = "v2:sms:9:72:3",
        receivedTransportKey = "v2:sms:9:72:3",
        expectedIdentity = ReactionTransportPolicy.ProviderIdentity("sms", 41, 72, 3),
        carrierThreadId = 72,
        carrierSubscriptionId = 3,
        targetThreadId = 72,
        successful = successful,
    )

    private fun hooks(
        events: MutableList<String>,
        stage: () -> String?,
        sync: (String) -> String?,
        submit: (String, String) -> Boolean,
        markSubmitted: (String) -> Unit = { events += "submitted" },
    ) = ReactionSubmissionCoordinator.Hooks(
        prepare = { events += "preparing" },
        stage = stage,
        syncHiddenAndMarkHandoff = sync,
        submit = submit,
        markSubmitted = markSubmitted,
        discard = { events += "discard" },
        markFailed = { events += "failed" },
    )
}
