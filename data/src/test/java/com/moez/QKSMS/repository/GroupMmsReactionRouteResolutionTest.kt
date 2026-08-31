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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupMmsReactionRouteResolutionTest {

    @Test
    fun incomingWitness_resolvesShuffledProviderRowsAndFormatting() {
        val provider = validProvider().apply {
            thread = listOf(ProviderThread(THREAD_ID, "  22   11 "))
            canonicalAddresses = listOf(
                ProviderCanonicalAddress(22, "312.555.0102"),
                ProviderCanonicalAddress(11, "+1 (312) 555-0101"),
            )
            mmsAddresses = listOf(
                ProviderMmsAddress(ProviderMmsAddressType.CC, "1-312-555-0102"),
                ProviderMmsAddress(ProviderMmsAddressType.TO, "(312) 555-0100"),
                ProviderMmsAddress(ProviderMmsAddressType.FROM, "312 555 0101"),
            )
        }

        val route = resolver(provider).resolvedRoute()

        assertEquals(GroupMmsReactionDirection.INCOMING, route.direction)
        assertEquals("US", route.region)
        assertEquals("+13125550100", route.selfParticipantKey)
        assertEquals(listOf("+13125550101", "+13125550102"), route.remoteParticipantKeys)
        assertEquals("group message", route.targetBody)
        assertTrue(route.participantFingerprint.matches(Regex("[0-9a-f]{64}")))
        assertTrue(route.bodyFingerprint.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun outgoingWitness_resolvesOnlyWhenFromIsExactSubscriptionSelf() {
        val provider = validProvider().apply {
            target = listOf(
                ProviderMmsTarget(MMS_ID, THREAD_ID, SUB_ID, ProviderMmsDirection.OUTGOING)
            )
            mmsAddresses = listOf(
                ProviderMmsAddress(ProviderMmsAddressType.TO, "+13125550102"),
                ProviderMmsAddress(ProviderMmsAddressType.FROM, "insert-address-token"),
                ProviderMmsAddress(ProviderMmsAddressType.CC, "+13125550101"),
            )
        }

        assertEquals(GroupMmsReactionDirection.OUTGOING, resolver(provider).resolvedRoute().direction)

        provider.mmsAddresses = provider.mmsAddresses.map { row ->
            if (row.type == ProviderMmsAddressType.FROM) row.copy(address = "+13125550999") else row
        }
        assertRejected(provider, GroupMmsReactionRouteRejection.HEADER_SELF_MISMATCH)
    }

    @Test
    fun duplicateRecipientIds_areRejected() {
        val provider = validProvider().apply {
            thread = listOf(ProviderThread(THREAD_ID, "11 11 22"))
        }

        assertRejected(provider, GroupMmsReactionRouteRejection.RECIPIENT_IDS_MISSING_OR_INVALID)
    }

    @Test
    fun duplicateCanonicalRows_areRejected() {
        val provider = validProvider().apply {
            canonicalAddresses += ProviderCanonicalAddress(11, "+13125550101")
        }

        assertRejected(
            provider,
            GroupMmsReactionRouteRejection.CANONICAL_ADDRESS_MISSING_OR_AMBIGUOUS,
        )
    }

    @Test
    fun differentlyFormattedDuplicateParticipants_areRejected() {
        val provider = validProvider().apply {
            canonicalAddresses = listOf(
                ProviderCanonicalAddress(11, "+1 312 555 0101"),
                ProviderCanonicalAddress(22, "(312) 555-0101"),
            )
        }

        assertRejected(provider, GroupMmsReactionRouteRejection.DUPLICATE_PARTICIPANT)
    }

    @Test
    fun missingCanonicalAddress_isRejected() {
        val provider = validProvider().apply {
            canonicalAddresses = canonicalAddresses.dropLast(1)
        }

        assertRejected(
            provider,
            GroupMmsReactionRouteRejection.CANONICAL_ADDRESS_MISSING_OR_AMBIGUOUS,
        )
    }

    @Test
    fun missingOrDuplicateFromWitness_isRejected() {
        val provider = validProvider().apply {
            mmsAddresses = mmsAddresses.filter { it.type != ProviderMmsAddressType.FROM }
        }
        assertRejected(
            provider,
            GroupMmsReactionRouteRejection.HEADER_WITNESS_MISSING_OR_AMBIGUOUS,
        )

        provider.mmsAddresses = validProvider().mmsAddresses +
            ProviderMmsAddress(ProviderMmsAddressType.UNSUPPORTED, "+13125550103")
        assertRejected(
            provider,
            GroupMmsReactionRouteRejection.HEADER_WITNESS_MISSING_OR_AMBIGUOUS,
        )

        provider.mmsAddresses = validProvider().mmsAddresses +
            ProviderMmsAddress(ProviderMmsAddressType.FROM, "+13125550999")
        assertRejected(
            provider,
            GroupMmsReactionRouteRejection.HEADER_WITNESS_MISSING_OR_AMBIGUOUS,
        )
    }

    @Test
    fun incomingWitnessWithoutExactSelf_isRejected() {
        val provider = validProvider().apply {
            mmsAddresses = mmsAddresses.map { row ->
                if (row.type == ProviderMmsAddressType.TO) row.copy(address = "+13125550999") else row
            }
        }

        assertRejected(provider, GroupMmsReactionRouteRejection.HEADER_SELF_MISMATCH)
    }

    @Test
    fun headerAndThreadParticipantMismatch_isRejected() {
        val provider = validProvider().apply {
            mmsAddresses = mmsAddresses.map { row ->
                if (row.type == ProviderMmsAddressType.CC) row.copy(address = "+13125550999") else row
            }
        }

        assertRejected(provider, GroupMmsReactionRouteRejection.HEADER_PARTICIPANT_MISMATCH)
    }

    @Test
    fun staleNavigationAndAmbiguousThread_areRejected() {
        val staleProvider = validProvider().apply {
            target = listOf(
                ProviderMmsTarget(MMS_ID, THREAD_ID + 1, SUB_ID, ProviderMmsDirection.INCOMING)
            )
        }
        assertRejected(staleProvider, GroupMmsReactionRouteRejection.STALE_NAVIGATION)

        val ambiguousProvider = validProvider().apply {
            thread += ProviderThread(THREAD_ID, "11 22")
        }
        assertRejected(
            ambiguousProvider,
            GroupMmsReactionRouteRejection.THREAD_MISSING_OR_AMBIGUOUS,
        )
    }

    @Test
    fun targetMustBeReloadedExactlyOnce() {
        val provider = validProvider().apply { target = emptyList() }
        assertRejected(provider, GroupMmsReactionRouteRejection.TARGET_MISSING_OR_AMBIGUOUS)

        provider.target = listOf(
            ProviderMmsTarget(MMS_ID, THREAD_ID, SUB_ID, ProviderMmsDirection.INCOMING),
            ProviderMmsTarget(MMS_ID, THREAD_ID, SUB_ID, ProviderMmsDirection.INCOMING),
        )
        assertRejected(provider, GroupMmsReactionRouteRejection.TARGET_MISSING_OR_AMBIGUOUS)
    }

    @Test
    fun dualSimUsesOnlyExplicitActiveSubscription() {
        val provider = validProvider().apply {
            subscriptions = listOf(
                ProviderSubscription(9, "+13125550999", "US"),
                ProviderSubscription(SUB_ID, "+1 (312) 555-0100", "US"),
            )
        }

        assertEquals(SUB_ID, resolver(provider).resolvedRoute().subscriptionId)

        assertRejected(
            provider,
            GroupMmsReactionRouteRejection.SUBSCRIPTION_MISMATCH,
            request().copy(activeSubscriptionId = 9),
        )
        assertRejected(
            provider,
            GroupMmsReactionRouteRejection.INVALID_REQUEST,
            request().copy(activeSubscriptionId = -1),
        )
    }

    @Test
    fun inactiveSubscriptionAndMissingSelfNeverFallBack() {
        val provider = validProvider().apply {
            subscriptions = listOf(ProviderSubscription(9, "+13125550999", "US"))
        }
        assertRejected(provider, GroupMmsReactionRouteRejection.SUBSCRIPTION_NOT_ACTIVE)

        provider.subscriptions = listOf(ProviderSubscription(SUB_ID, null, "US"))
        assertRejected(
            provider,
            GroupMmsReactionRouteRejection.SELF_ADDRESS_MISSING_OR_UNSUPPORTED,
        )
    }

    @Test
    fun fanOutAndUnsupportedAddresses_areRejected() {
        val fanOutProvider = validProvider().apply {
            thread = listOf(ProviderThread(THREAD_ID, "11"))
            canonicalAddresses = canonicalAddresses.take(1)
        }
        assertRejected(fanOutProvider, GroupMmsReactionRouteRejection.NOT_TRUE_GROUP)

        val unsupportedProvider = validProvider().apply {
            canonicalAddresses = listOf(
                ProviderCanonicalAddress(11, "person@example.com"),
                canonicalAddresses[1],
            )
        }
        assertRejected(unsupportedProvider, GroupMmsReactionRouteRejection.UNSUPPORTED_PARTICIPANT)
    }

    @Test
    fun fingerprintIsStableAcrossProviderOrderAndFormatting() {
        val first = resolver(validProvider()).resolvedRoute()
        val secondProvider = validProvider().apply {
            thread = listOf(ProviderThread(THREAD_ID, "22 11"))
            canonicalAddresses = listOf(
                ProviderCanonicalAddress(22, "+1 (312) 555-0102"),
                ProviderCanonicalAddress(11, "312-555-0101"),
            )
            mmsAddresses = mmsAddresses.reversed()
        }
        val second = resolver(secondProvider).resolvedRoute()

        assertEquals(first.remoteParticipantKeys, second.remoteParticipantKeys)
        assertEquals(first.participantFingerprint, second.participantFingerprint)
        assertTrue(first.participantFingerprint.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun subscriptionCountryIsTheOnlyAcceptedRegionWitness() {
        val provider = validProvider()

        listOf(null, "", "AQ").forEach { countryIso ->
            provider.subscriptions = listOf(
                ProviderSubscription(SUB_ID, "+13125550100", countryIso)
            )
            assertRejected(
                provider,
                GroupMmsReactionRouteRejection.SUBSCRIPTION_REGION_MISSING_OR_UNSUPPORTED,
            )
        }

        provider.subscriptions = listOf(ProviderSubscription(SUB_ID, "+13125550100", "CA"))
        assertRejected(provider, GroupMmsReactionRouteRejection.REGION_MISMATCH)
        assertRejected(
            provider,
            GroupMmsReactionRouteRejection.INVALID_REQUEST,
            request().copy(region = "ZZ"),
        )
    }

    @Test
    fun exactTextPartTreeIsBoundAndMediaFailsClosed() {
        val provider = validProvider()

        provider.parts = emptyList()
        assertRejected(provider, GroupMmsReactionRouteRejection.PARTS_MISSING_OR_AMBIGUOUS)

        provider.parts = listOf(validTextPart().copy(messageId = MMS_ID + 1))
        assertRejected(provider, GroupMmsReactionRouteRejection.PARTS_MISSING_OR_AMBIGUOUS)

        provider.parts = listOf(validTextPart(), validTextPart())
        assertRejected(provider, GroupMmsReactionRouteRejection.PARTS_MISSING_OR_AMBIGUOUS)

        listOf("image/jpeg", "video/mp4", "audio/ogg", "text/x-vcard", null).forEach { type ->
            provider.parts = listOf(validTextPart().copy(contentType = type))
            assertRejected(provider, GroupMmsReactionRouteRejection.NON_TEXT_PART)
        }

        provider.parts = listOf(validTextPart().copy(dataPath = "/provider/media/100"))
        assertRejected(provider, GroupMmsReactionRouteRejection.NON_TEXT_PART)

        provider.parts = listOf(
            ProviderGroupMmsPart(101, MMS_ID, -1, "application/smil", "<smil/>"),
            validTextPart(),
            ProviderGroupMmsPart(102, MMS_ID, 1, "text/plain", "second line"),
        )
        val route = resolver(provider).resolvedRoute()
        assertEquals("group message\nsecond line", route.targetBody)

        provider.parts = provider.parts.reversed()
        val reordered = resolver(provider).resolvedRoute()
        assertEquals(route.targetBody, reordered.targetBody)
        assertEquals(route.bodyFingerprint, reordered.bodyFingerprint)
    }

    @Test
    fun bodyRequiresNonblankText() {
        val provider = validProvider()

        listOf(null, "", "  ").forEach { text ->
            provider.parts = listOf(validTextPart().copy(text = text))
            assertRejected(provider, GroupMmsReactionRouteRejection.BODY_MISSING_OR_UNSUPPORTED)
        }
        provider.parts = listOf(
            ProviderGroupMmsPart(101, MMS_ID, -1, "application/smil", "<smil/>")
        )
        assertRejected(provider, GroupMmsReactionRouteRejection.BODY_MISSING_OR_UNSUPPORTED)
    }

    @Test
    fun reverifyRejectsPartParticipantRegionAndPersistedProofMutation() {
        val provider = validProvider()
        val resolution = resolver(provider)
        val route = resolution.resolvedRoute()
        assertEquals(GroupMmsReactionRouteResult.Resolved(route), resolution.reverify(route))

        provider.parts = listOf(validTextPart().copy(text = "mutated"))
        assertRouteChanged(resolution, route)

        provider.parts = listOf(
            validTextPart(),
            ProviderGroupMmsPart(101, MMS_ID, 1, "image/jpeg", null, "/provider/media/101"),
        )
        assertEquals(
            GroupMmsReactionRouteResult.Rejected(
                GroupMmsReactionRouteRejection.NON_TEXT_PART
            ),
            resolution.reverify(route),
        )

        provider.parts = listOf(validTextPart())
        provider.canonicalAddresses = listOf(
            ProviderCanonicalAddress(11, "+13125550101"),
            ProviderCanonicalAddress(22, "+13125550103"),
        )
        provider.mmsAddresses = provider.mmsAddresses.map { row ->
            if (row.type == ProviderMmsAddressType.CC) row.copy(address = "+13125550103") else row
        }
        assertRouteChanged(resolution, route)

        provider.canonicalAddresses = validProvider().canonicalAddresses
        provider.mmsAddresses = validProvider().mmsAddresses
        provider.subscriptions = listOf(ProviderSubscription(SUB_ID, "+13125550100", "CA"))
        assertEquals(
            GroupMmsReactionRouteResult.Rejected(
                GroupMmsReactionRouteRejection.REGION_MISMATCH
            ),
            resolution.reverify(route),
        )

        provider.subscriptions = listOf(ProviderSubscription(SUB_ID, "+13125550100", "US"))
        assertRouteChanged(resolution, route.copy(bodyFingerprint = "0".repeat(64)))
    }

    @Test
    fun providerFailure_isRejectedWithoutLeakingAnException() {
        val provider = validProvider().apply { targetFailure = IllegalStateException("query failed") }

        assertRejected(provider, GroupMmsReactionRouteRejection.PROVIDER_UNAVAILABLE)
    }

    private fun resolver(provider: FakeProvider): GroupMmsReactionRouteResolution =
        GroupMmsReactionRouteResolution(provider, TestUsCanonicalizer)

    private fun GroupMmsReactionRouteResolution.resolvedRoute(
        request: GroupMmsReactionRouteRequest = request(),
    ): GroupMmsReactionRoute {
        val result = resolve(request)
        assertTrue("Expected a resolved route but was $result", result is GroupMmsReactionRouteResult.Resolved)
        return (result as GroupMmsReactionRouteResult.Resolved).route
    }

    private fun assertRejected(
        provider: FakeProvider,
        expected: GroupMmsReactionRouteRejection,
        request: GroupMmsReactionRouteRequest = request(),
    ) {
        val result = resolver(provider).resolve(request)
        assertEquals(GroupMmsReactionRouteResult.Rejected(expected), result)
    }

    private fun assertRouteChanged(
        resolution: GroupMmsReactionRouteResolution,
        route: GroupMmsReactionRoute,
    ) {
        assertEquals(
            GroupMmsReactionRouteResult.Rejected(
                GroupMmsReactionRouteRejection.ROUTE_CHANGED
            ),
            resolution.reverify(route),
        )
    }

    private fun validProvider(): FakeProvider = FakeProvider(
        target = listOf(
            ProviderMmsTarget(MMS_ID, THREAD_ID, SUB_ID, ProviderMmsDirection.INCOMING)
        ),
        thread = listOf(ProviderThread(THREAD_ID, "11 22")),
        canonicalAddresses = listOf(
            ProviderCanonicalAddress(11, "+13125550101"),
            ProviderCanonicalAddress(22, "+13125550102"),
        ),
        mmsAddresses = listOf(
            ProviderMmsAddress(ProviderMmsAddressType.FROM, "+13125550101"),
            ProviderMmsAddress(ProviderMmsAddressType.TO, "+13125550100"),
            ProviderMmsAddress(ProviderMmsAddressType.CC, "+13125550102"),
        ),
        parts = listOf(validTextPart()),
        subscriptions = listOf(ProviderSubscription(SUB_ID, "+13125550100", "US")),
    )

    private fun validTextPart() =
        ProviderGroupMmsPart(100, MMS_ID, 0, "text/plain", "group message")

    private fun request(): GroupMmsReactionRouteRequest = GroupMmsReactionRouteRequest(
        targetMmsId = MMS_ID,
        navigationThreadId = THREAD_ID,
        activeSubscriptionId = SUB_ID,
        region = "US",
    )

    private class FakeProvider(
        var target: List<ProviderMmsTarget>,
        var thread: List<ProviderThread>,
        var canonicalAddresses: List<ProviderCanonicalAddress>,
        var mmsAddresses: List<ProviderMmsAddress>,
        var parts: List<ProviderGroupMmsPart>,
        var subscriptions: List<ProviderSubscription>,
        var targetFailure: RuntimeException? = null,
    ) : GroupMmsReactionRouteProvider {
        override fun loadTarget(mmsId: Long): List<ProviderMmsTarget> {
            targetFailure?.let { throw it }
            return target
        }

        override fun loadThread(threadId: Long): List<ProviderThread> = thread

        override fun loadCanonicalAddresses(
            recipientIds: List<Long>,
        ): List<ProviderCanonicalAddress> = canonicalAddresses

        override fun loadMmsAddresses(mmsId: Long): List<ProviderMmsAddress> = mmsAddresses

        override fun loadMmsParts(mmsId: Long): List<ProviderGroupMmsPart> = parts

        override fun loadActiveSubscriptions(): List<ProviderSubscription> = subscriptions
    }

    private object TestUsCanonicalizer : GroupMmsPhoneCanonicalizer {
        override fun supportsRegion(region: String): Boolean = region == "US" || region == "CA"

        override fun toE164(address: String, region: String): String? {
            if (region != "US" || address.any(Char::isLetter)) return null
            val digits = address.filter(Char::isDigit)
            return when {
                digits.length == 10 -> "+1$digits"
                digits.length == 11 && digits.startsWith("1") -> "+$digits"
                else -> null
            }
        }
    }

    private companion object {
        const val MMS_ID = 700L
        const val THREAD_ID = 80L
        const val SUB_ID = 4
    }
}
