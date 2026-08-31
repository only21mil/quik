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

class OneToOneSmsReactionRouteResolutionTest {

    @Test
    fun incomingTargetResolvesFromExactProviderAndCanonicalThreadEvidence() {
        val provider = validProvider().apply {
            target = listOf(
                ProviderSmsTarget(
                    SMS_ID,
                    THREAD_ID,
                    SUBSCRIPTION_ID,
                    "+1 (312) 555-0101",
                    TARGET_BODY,
                    ProviderSmsDirection.INCOMING,
                )
            )
            thread = listOf(ProviderSmsThread(THREAD_ID, "  11  "))
            canonicalAddresses = listOf(ProviderSmsCanonicalAddress(11, "312.555.0101"))
            subscriptions = listOf(
                ProviderSmsSubscription(9, "+13125550999", "US"),
                ProviderSmsSubscription(SUBSCRIPTION_ID, "312-555-0100", "us"),
            )
        }

        val route = resolver(provider).resolvedRoute()

        assertEquals(SMS_ID, route.providerSmsId)
        assertEquals(THREAD_ID, route.threadId)
        assertEquals(SUBSCRIPTION_ID, route.subscriptionId)
        assertEquals("US", route.region)
        assertEquals(OneToOneSmsReactionDirection.INCOMING, route.direction)
        assertEquals("+13125550101", route.remoteRecipientE164)
        assertEquals(
            "a01d339bf50b05b39e77e4dfac151657e8f27850aa503ad58c5ed4fa7eafe0e4",
            route.participantFingerprint,
        )
        assertEquals(
            "ac39ccac08abb04bdc62ca79cedcb52ec83638588209aca3ebe2fa11f44e3795",
            route.targetBodyFingerprint,
        )
        assertEquals(listOf(SMS_ID), provider.targetRequests)
        assertEquals(listOf(THREAD_ID), provider.threadRequests)
        assertEquals(listOf(listOf(11L)), provider.canonicalRequests)
    }

    @Test
    fun outgoingSentTargetIsSupportedButDraftAndOutboxAreRejected() {
        val provider = validProvider().apply {
            target = listOf(target.single().copy(direction = ProviderSmsDirection.OUTGOING))
        }
        assertEquals(
            OneToOneSmsReactionDirection.OUTGOING,
            resolver(provider).resolvedRoute().direction,
        )

        provider.target = listOf(
            provider.target.single().copy(direction = ProviderSmsDirection.UNSUPPORTED)
        )
        assertRejected(
            provider,
            OneToOneSmsReactionRouteRejection.TARGET_DIRECTION_UNSUPPORTED,
        )
    }

    @Test
    fun missingDuplicateAndWrongProviderTargetsAreRejected() {
        val provider = validProvider().apply { target = emptyList() }
        assertRejected(provider, OneToOneSmsReactionRouteRejection.TARGET_MISSING_OR_AMBIGUOUS)

        provider.target = listOf(validTarget(), validTarget())
        assertRejected(provider, OneToOneSmsReactionRouteRejection.TARGET_MISSING_OR_AMBIGUOUS)

        provider.target = listOf(validTarget().copy(id = SMS_ID + 1))
        assertRejected(provider, OneToOneSmsReactionRouteRejection.TARGET_MISSING_OR_AMBIGUOUS)
    }

    @Test
    fun navigationThreadAndSubscriptionDriftAreRejected() {
        val provider = validProvider().apply {
            target = listOf(validTarget().copy(threadId = THREAD_ID + 1))
        }
        assertRejected(provider, OneToOneSmsReactionRouteRejection.STALE_NAVIGATION)

        provider.target = listOf(validTarget().copy(subscriptionId = SUBSCRIPTION_ID + 1))
        assertRejected(provider, OneToOneSmsReactionRouteRejection.SUBSCRIPTION_MISMATCH)

        provider.target = listOf(validTarget())
        provider.thread = listOf(ProviderSmsThread(THREAD_ID + 1, "11"))
        assertRejected(provider, OneToOneSmsReactionRouteRejection.THREAD_MISSING_OR_AMBIGUOUS)
    }

    @Test
    fun threadMustBeExactAndContainOneUniqueRecipientId() {
        val provider = validProvider().apply { thread = emptyList() }
        assertRejected(provider, OneToOneSmsReactionRouteRejection.THREAD_MISSING_OR_AMBIGUOUS)

        provider.thread = listOf(ProviderSmsThread(THREAD_ID, "11"), ProviderSmsThread(THREAD_ID, "11"))
        assertRejected(provider, OneToOneSmsReactionRouteRejection.THREAD_MISSING_OR_AMBIGUOUS)

        listOf(null, "", "0", "email", "11 -2").forEach { recipientIds ->
            provider.thread = listOf(ProviderSmsThread(THREAD_ID, recipientIds))
            assertRejected(
                provider,
                OneToOneSmsReactionRouteRejection.RECIPIENT_IDS_MISSING_OR_INVALID,
            )
        }

        provider.thread = listOf(ProviderSmsThread(THREAD_ID, "11 11"))
        assertRejected(provider, OneToOneSmsReactionRouteRejection.DUPLICATE_PARTICIPANT)

        provider.thread = listOf(ProviderSmsThread(THREAD_ID, "11 12"))
        assertRejected(provider, OneToOneSmsReactionRouteRejection.NOT_ONE_TO_ONE)
    }

    @Test
    fun canonicalMembershipMustMatchTheOneThreadRecipientExactly() {
        val provider = validProvider().apply { canonicalAddresses = emptyList() }
        assertRejected(
            provider,
            OneToOneSmsReactionRouteRejection.CANONICAL_ADDRESS_MISSING_OR_AMBIGUOUS,
        )

        provider.canonicalAddresses = listOf(
            ProviderSmsCanonicalAddress(11, "+13125550101"),
            ProviderSmsCanonicalAddress(11, "+13125550101"),
        )
        assertRejected(
            provider,
            OneToOneSmsReactionRouteRejection.CANONICAL_ADDRESS_MISSING_OR_AMBIGUOUS,
        )

        provider.canonicalAddresses = listOf(ProviderSmsCanonicalAddress(12, "+13125550101"))
        assertRejected(
            provider,
            OneToOneSmsReactionRouteRejection.CANONICAL_ADDRESS_MISSING_OR_AMBIGUOUS,
        )
    }

    @Test
    fun shortcodeEmailAndMissingAddressesNeverBecomeRoutes() {
        val provider = validProvider()
        listOf("12345", "person@example.com", "", null).forEach { address ->
            provider.canonicalAddresses = listOf(ProviderSmsCanonicalAddress(11, address))
            assertRejected(provider, OneToOneSmsReactionRouteRejection.UNSUPPORTED_PARTICIPANT)
        }

        provider.canonicalAddresses = validProvider().canonicalAddresses
        listOf("12345", "person@example.com", "", null).forEach { address ->
            provider.target = listOf(validTarget().copy(address = address))
            assertRejected(
                provider,
                OneToOneSmsReactionRouteRejection.TARGET_ADDRESS_MISSING_OR_UNSUPPORTED,
            )
        }
    }

    @Test
    fun selfAddressCannotBeTheThreadOrTargetRecipient() {
        val provider = validProvider().apply {
            canonicalAddresses = listOf(ProviderSmsCanonicalAddress(11, "+13125550100"))
            target = listOf(validTarget().copy(address = "+13125550100"))
        }
        assertRejected(provider, OneToOneSmsReactionRouteRejection.SELF_RECIPIENT)

        provider.canonicalAddresses = validProvider().canonicalAddresses
        assertRejected(provider, OneToOneSmsReactionRouteRejection.SELF_RECIPIENT)
    }

    @Test
    fun targetAddressMustWitnessCanonicalThreadRecipient() {
        val provider = validProvider().apply {
            target = listOf(validTarget().copy(address = "+13125550102"))
        }

        assertRejected(provider, OneToOneSmsReactionRouteRejection.THREAD_RECIPIENT_MISMATCH)
    }

    @Test
    fun onlyTheExplicitActiveSubscriptionAndProviderRegionAreAccepted() {
        val provider = validProvider().apply {
            subscriptions = listOf(ProviderSmsSubscription(9, "+13125550999", "US"))
        }
        assertRejected(
            provider,
            OneToOneSmsReactionRouteRejection.SUBSCRIPTION_NOT_ACTIVE_OR_AMBIGUOUS,
        )

        provider.subscriptions = listOf(
            ProviderSmsSubscription(SUBSCRIPTION_ID, "+13125550100", "US"),
            ProviderSmsSubscription(SUBSCRIPTION_ID, "+13125550100", "US"),
        )
        assertRejected(
            provider,
            OneToOneSmsReactionRouteRejection.SUBSCRIPTION_NOT_ACTIVE_OR_AMBIGUOUS,
        )

        provider.subscriptions = validProvider().subscriptions
        listOf("", "ZZ").forEach { region ->
            assertRejected(
                provider,
                OneToOneSmsReactionRouteRejection.INVALID_REQUEST,
                request().copy(region = region),
            )
        }
        assertEquals("US", resolver(provider).resolvedRoute(request().copy(region = " us ")).region)

        assertRejected(
            provider,
            OneToOneSmsReactionRouteRejection.REGION_MISMATCH,
            request().copy(region = "CA"),
        )
    }

    @Test
    fun providerCountryMustBePresentAndSupported() {
        val provider = validProvider()
        listOf(null, "", "AQ").forEach { countryIso ->
            provider.subscriptions = listOf(
                ProviderSmsSubscription(SUBSCRIPTION_ID, "+13125550100", countryIso)
            )
            assertRejected(
                provider,
                OneToOneSmsReactionRouteRejection.SUBSCRIPTION_REGION_MISSING_OR_UNSUPPORTED,
            )
        }
    }

    @Test
    fun wrongCallerRegionCannotReinterpretAnAmbiguousLocalNumber() {
        val provider = validProvider().apply {
            target = listOf(validTarget().copy(address = "020 7946 0958"))
            canonicalAddresses = listOf(ProviderSmsCanonicalAddress(11, "020 7946 0958"))
            subscriptions = listOf(ProviderSmsSubscription(SUBSCRIPTION_ID, null, "GB"))
        }
        val canonicalizer = RecordingRegionalCanonicalizer()
        val resolution = resolver(provider, canonicalizer)

        assertEquals(
            OneToOneSmsReactionRouteResult.Rejected(
                OneToOneSmsReactionRouteRejection.REGION_MISMATCH
            ),
            resolution.resolve(request().copy(region = "US")),
        )
        assertTrue(canonicalizer.calls.isEmpty())

        val route = resolution.resolvedRoute(request().copy(region = "GB"))
        assertEquals("GB", route.region)
        assertEquals("+442079460958", route.remoteRecipientE164)
        assertTrue(canonicalizer.calls.all { (_, region) -> region == "GB" })
    }

    @Test
    fun subscriptionLineNumberIsOnlyBestEffortSelfExclusion() {
        val provider = validProvider()
        val resolution = resolver(provider)
        val expected = resolution.resolvedRoute()

        listOf(null, "", "carrier-redacted").forEach { selfAddress ->
            provider.subscriptions = listOf(
                ProviderSmsSubscription(SUBSCRIPTION_ID, selfAddress, "US")
            )
            assertEquals(expected, resolution.resolvedRoute())
            assertEquals(
                OneToOneSmsReactionRouteResult.Resolved(expected),
                resolution.reverify(expected),
            )
        }

        provider.subscriptions = listOf(
            ProviderSmsSubscription(SUBSCRIPTION_ID, "+13125550101", "US")
        )
        assertRejected(provider, OneToOneSmsReactionRouteRejection.SELF_RECIPIENT)
    }

    @Test
    fun nonPositiveProviderOrNavigationIdentityAndNegativeSubscriptionAreRejected() {
        val provider = validProvider()
        listOf(
            request().copy(providerSmsId = 0),
            request().copy(navigationThreadId = 0),
            request().copy(activeSubscriptionId = -1),
        ).forEach { invalidRequest ->
            assertRejected(
                provider,
                OneToOneSmsReactionRouteRejection.INVALID_REQUEST,
                invalidRequest,
            )
        }
        assertTrue(provider.targetRequests.isEmpty())
    }

    @Test
    fun persistedRouteReverificationRejectsAnyProviderOrProofDrift() {
        val provider = validProvider()
        val resolution = resolver(provider)
        val route = resolution.resolvedRoute()

        assertEquals(OneToOneSmsReactionRouteResult.Resolved(route), resolution.reverify(route))

        provider.target = listOf(validTarget().copy(threadId = THREAD_ID + 1))
        assertEquals(
            OneToOneSmsReactionRouteResult.Rejected(
                OneToOneSmsReactionRouteRejection.STALE_NAVIGATION
            ),
            resolution.reverify(route),
        )

        provider.target = listOf(validTarget())
        provider.subscriptions = listOf(
            ProviderSmsSubscription(SUBSCRIPTION_ID, "+13125550100", "CA")
        )
        assertEquals(
            OneToOneSmsReactionRouteResult.Rejected(
                OneToOneSmsReactionRouteRejection.REGION_MISMATCH
            ),
            resolution.reverify(route),
        )

        provider.subscriptions = validProvider().subscriptions
        val alteredProof = route.copy(participantFingerprint = "0".repeat(64))
        assertEquals(
            OneToOneSmsReactionRouteResult.Rejected(
                OneToOneSmsReactionRouteRejection.ROUTE_CHANGED
            ),
            resolution.reverify(alteredProof),
        )
    }

    @Test
    fun exactTargetBodyIsRequiredAndCallbackReverificationRejectsBodyMutation() {
        val provider = validProvider()
        val resolution = resolver(provider)
        val route = resolution.resolvedRoute()

        assertRejected(
            provider,
            OneToOneSmsReactionRouteRejection.TARGET_BODY_MISSING_OR_MISMATCH,
            request().copy(targetBody = "$TARGET_BODY "),
        )

        provider.target = listOf(validTarget().copy(body = null))
        assertRejected(
            provider,
            OneToOneSmsReactionRouteRejection.TARGET_BODY_MISSING_OR_MISMATCH,
        )

        provider.target = listOf(validTarget().copy(body = "mutated"))
        assertEquals(
            OneToOneSmsReactionRouteResult.Rejected(
                OneToOneSmsReactionRouteRejection.ROUTE_CHANGED
            ),
            resolution.reverify(route),
        )
    }

    @Test
    fun participantFingerprintIsCanonicalAcrossFormatting() {
        val first = resolver(validProvider()).resolvedRoute()
        val secondProvider = validProvider().apply {
            target = listOf(validTarget().copy(address = "(312) 555-0101"))
            canonicalAddresses = listOf(ProviderSmsCanonicalAddress(11, "1 312 555 0101"))
            subscriptions = listOf(
                ProviderSmsSubscription(SUBSCRIPTION_ID, "+1 312 555 0100", "US")
            )
        }
        val second = resolver(secondProvider).resolvedRoute()

        assertEquals(first.participantFingerprint, second.participantFingerprint)
        assertEquals(first, second)
    }

    @Test
    fun providerFailureIsRejectedWithoutLeakingException() {
        val provider = validProvider().apply {
            targetFailure = IllegalStateException("query failed")
        }

        assertRejected(provider, OneToOneSmsReactionRouteRejection.PROVIDER_UNAVAILABLE)
    }

    @Test
    fun permissionDenialAndRuntimeRevocationYieldNoRoute() {
        val provider = validProvider()
        val resolution = resolver(provider)
        val route = resolution.resolvedRoute()

        provider.subscriptions = emptyList()

        assertEquals(
            OneToOneSmsReactionRouteResult.Rejected(
                OneToOneSmsReactionRouteRejection.SUBSCRIPTION_NOT_ACTIVE_OR_AMBIGUOUS
            ),
            resolution.resolve(request()),
        )
        assertEquals(
            OneToOneSmsReactionRouteResult.Rejected(
                OneToOneSmsReactionRouteRejection.SUBSCRIPTION_NOT_ACTIVE_OR_AMBIGUOUS
            ),
            resolution.reverify(route),
        )
    }

    private fun resolver(
        provider: FakeProvider,
        canonicalizer: OneToOneSmsPhoneCanonicalizer = TestUsCanonicalizer,
    ): OneToOneSmsReactionRouteResolution =
        OneToOneSmsReactionRouteResolution(provider, canonicalizer)

    private fun OneToOneSmsReactionRouteResolution.resolvedRoute(
        request: OneToOneSmsReactionRouteRequest = request(),
    ): OneToOneSmsReactionRoute {
        val result = resolve(request)
        assertTrue("Expected a resolved route but was $result", result is OneToOneSmsReactionRouteResult.Resolved)
        return (result as OneToOneSmsReactionRouteResult.Resolved).route
    }

    private fun assertRejected(
        provider: FakeProvider,
        expected: OneToOneSmsReactionRouteRejection,
        request: OneToOneSmsReactionRouteRequest = request(),
    ) {
        assertEquals(
            OneToOneSmsReactionRouteResult.Rejected(expected),
            resolver(provider).resolve(request),
        )
    }

    private fun validProvider(): FakeProvider = FakeProvider(
        target = listOf(validTarget()),
        thread = listOf(ProviderSmsThread(THREAD_ID, "11")),
        canonicalAddresses = listOf(ProviderSmsCanonicalAddress(11, "+13125550101")),
        subscriptions = listOf(
            ProviderSmsSubscription(SUBSCRIPTION_ID, "+13125550100", "US")
        ),
    )

    private fun validTarget(): ProviderSmsTarget = ProviderSmsTarget(
        id = SMS_ID,
        threadId = THREAD_ID,
        subscriptionId = SUBSCRIPTION_ID,
        address = "+13125550101",
        body = TARGET_BODY,
        direction = ProviderSmsDirection.INCOMING,
    )

    private fun request(): OneToOneSmsReactionRouteRequest = OneToOneSmsReactionRouteRequest(
        providerSmsId = SMS_ID,
        navigationThreadId = THREAD_ID,
        activeSubscriptionId = SUBSCRIPTION_ID,
        region = "US",
        targetBody = TARGET_BODY,
    )

    private class FakeProvider(
        var target: List<ProviderSmsTarget>,
        var thread: List<ProviderSmsThread>,
        var canonicalAddresses: List<ProviderSmsCanonicalAddress>,
        var subscriptions: List<ProviderSmsSubscription>,
        var targetFailure: RuntimeException? = null,
    ) : OneToOneSmsReactionRouteProvider {
        val targetRequests = mutableListOf<Long>()
        val threadRequests = mutableListOf<Long>()
        val canonicalRequests = mutableListOf<List<Long>>()

        override fun loadTarget(providerSmsId: Long): List<ProviderSmsTarget> {
            targetRequests += providerSmsId
            targetFailure?.let { throw it }
            return target
        }

        override fun loadThread(threadId: Long): List<ProviderSmsThread> {
            threadRequests += threadId
            return thread
        }

        override fun loadCanonicalAddresses(
            recipientIds: List<Long>,
        ): List<ProviderSmsCanonicalAddress> {
            canonicalRequests += recipientIds.toList()
            return canonicalAddresses
        }

        override fun loadActiveSubscriptions(): List<ProviderSmsSubscription> = subscriptions
    }

    private object TestUsCanonicalizer : OneToOneSmsPhoneCanonicalizer {
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

    private class RecordingRegionalCanonicalizer : OneToOneSmsPhoneCanonicalizer {
        val calls = mutableListOf<Pair<String, String>>()

        override fun supportsRegion(region: String): Boolean = region == "GB" || region == "US"

        override fun toE164(address: String, region: String): String? {
            calls += address to region
            val digits = address.filter(Char::isDigit)
            return when {
                region == "GB" && digits.length == 11 && digits.startsWith("0") ->
                    "+44${digits.drop(1)}"

                region == "US" && digits.length == 10 -> "+1$digits"
                else -> null
            }
        }
    }

    private companion object {
        const val SMS_ID = 701L
        const val THREAD_ID = 81L
        const val SUBSCRIPTION_ID = 4
        const val TARGET_BODY = "hello"
    }
}
