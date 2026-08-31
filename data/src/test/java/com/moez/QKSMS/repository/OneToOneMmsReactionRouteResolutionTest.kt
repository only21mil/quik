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

class OneToOneMmsReactionRouteResolutionTest {

    @Test
    fun incomingTextOnlyMmsResolvesFromExactProviderWitnesses() {
        val provider = validProvider().apply {
            thread = listOf(ProviderThread(THREAD_ID, " 11 "))
            canonicalAddresses = listOf(ProviderCanonicalAddress(11, "312.555.0101"))
            addresses = listOf(
                ProviderMmsAddress(ProviderMmsAddressType.TO, "312-555-0100"),
                ProviderMmsAddress(ProviderMmsAddressType.FROM, "+1 (312) 555-0101"),
            )
            parts = listOf(
                ProviderOneToOneMmsPart(102, MMS_ID, 1, "TEXT/PLAIN", "second"),
                ProviderOneToOneMmsPart(100, MMS_ID, -1, "application/smil", "<smil/>"),
                ProviderOneToOneMmsPart(101, MMS_ID, 0, "text/plain", "first"),
            )
            subscriptions = listOf(
                ProviderOneToOneMmsSubscription(8, "+13125550999", "US"),
                ProviderOneToOneMmsSubscription(SUB_ID, "+13125550100", "us"),
            )
        }

        val route = resolver(provider).resolvedRoute()

        assertEquals(MMS_ID, route.providerMmsId)
        assertEquals(THREAD_ID, route.threadId)
        assertEquals(SUB_ID, route.subscriptionId)
        assertEquals("US", route.region)
        assertEquals(OneToOneMmsReactionDirection.INCOMING, route.direction)
        assertEquals("+13125550101", route.remoteRecipientE164)
        assertEquals("first\nsecond", route.targetBody)
        assertEquals(64, route.participantFingerprint.length)
        assertEquals(64, route.bodyFingerprint.length)
        assertEquals(listOf(MMS_ID), provider.targetRequests)
        assertEquals(listOf(THREAD_ID), provider.threadRequests)
        assertEquals(listOf(MMS_ID), provider.addressRequests)
        assertEquals(listOf(MMS_ID), provider.partRequests)
    }

    @Test
    fun exactOutgoingHeaderWitnessIsAccepted() {
        val provider = validProvider().apply {
            target = listOf(validTarget().copy(direction = ProviderMmsDirection.OUTGOING))
            addresses = listOf(
                ProviderMmsAddress(ProviderMmsAddressType.FROM, "insert-address-token"),
                ProviderMmsAddress(ProviderMmsAddressType.TO, "+13125550101"),
            )
        }

        assertEquals(
            OneToOneMmsReactionDirection.OUTGOING,
            resolver(provider).resolvedRoute().direction,
        )
    }

    @Test
    fun outgoingInsertAddressTokenProvesSelfWhenSubscriptionNumberIsRedacted() {
        listOf(null, "", "carrier-redacted").forEach { redactedNumber ->
            val provider = validProvider().apply {
                target = listOf(validTarget().copy(direction = ProviderMmsDirection.OUTGOING))
                subscriptions = listOf(validSubscription().copy(selfAddress = redactedNumber))
                addresses = listOf(
                    ProviderMmsAddress(ProviderMmsAddressType.FROM, "insert-address-token"),
                    ProviderMmsAddress(ProviderMmsAddressType.TO, "+13125550101"),
                )
            }

            val route = resolver(provider).resolvedRoute()

            assertEquals(OneToOneMmsReactionDirection.OUTGOING, route.direction)
            assertEquals("+13125550101", route.remoteRecipientE164)
        }
    }

    @Test
    fun insertAddressTokenMustBeUniqueOutgoingFromWitness() {
        val provider = validProvider().apply {
            target = listOf(validTarget().copy(direction = ProviderMmsDirection.OUTGOING))
            subscriptions = listOf(validSubscription().copy(selfAddress = null))
        }

        provider.addresses = listOf(
            ProviderMmsAddress(ProviderMmsAddressType.FROM, "+13125550100"),
            ProviderMmsAddress(ProviderMmsAddressType.TO, "insert-address-token"),
        )
        assertRejected(provider, OneToOneMmsReactionRouteRejection.HEADER_SELF_MISMATCH)

        provider.addresses = listOf(
            ProviderMmsAddress(ProviderMmsAddressType.FROM, "insert-address-token"),
            ProviderMmsAddress(ProviderMmsAddressType.TO, "insert-address-token"),
        )
        assertRejected(
            provider,
            OneToOneMmsReactionRouteRejection.HEADER_WITNESS_MISSING_OR_AMBIGUOUS,
        )

        provider.target = listOf(validTarget().copy(direction = ProviderMmsDirection.INCOMING))
        provider.addresses = listOf(
            ProviderMmsAddress(ProviderMmsAddressType.FROM, "insert-address-token"),
            ProviderMmsAddress(ProviderMmsAddressType.TO, "+13125550100"),
        )
        assertRejected(provider, OneToOneMmsReactionRouteRejection.HEADER_SELF_MISMATCH)
    }

    @Test
    fun redactedSelfStillRequiresUnambiguousRemoteAndTokenProof() {
        val provider = validProvider().apply {
            target = listOf(validTarget().copy(direction = ProviderMmsDirection.OUTGOING))
            subscriptions = listOf(validSubscription().copy(selfAddress = null))
            addresses = listOf(
                ProviderMmsAddress(ProviderMmsAddressType.FROM, "+13125550100"),
                ProviderMmsAddress(ProviderMmsAddressType.TO, "+13125550101"),
            )
        }
        assertRejected(
            provider,
            OneToOneMmsReactionRouteRejection.SELF_ADDRESS_MISSING_OR_UNSUPPORTED,
        )

        provider.addresses = listOf(
            ProviderMmsAddress(ProviderMmsAddressType.FROM, "insert-address-token"),
            ProviderMmsAddress(ProviderMmsAddressType.TO, "+13125550101"),
        )
        provider.thread = listOf(ProviderThread(THREAD_ID, "11 12"))
        assertRejected(provider, OneToOneMmsReactionRouteRejection.NOT_ONE_TO_ONE)

        provider.thread = listOf(ProviderThread(THREAD_ID, "11"))
        provider.subscriptions = listOf(validSubscription())
        provider.canonicalAddresses = listOf(ProviderCanonicalAddress(11, "+13125550100"))
        assertRejected(provider, OneToOneMmsReactionRouteRejection.SELF_RECIPIENT)
    }

    @Test
    fun targetIdentityNavigationSubscriptionAndDirectionMustRemainExact() {
        val provider = validProvider().apply { target = emptyList() }
        assertRejected(provider, OneToOneMmsReactionRouteRejection.TARGET_MISSING_OR_AMBIGUOUS)

        provider.target = listOf(validTarget(), validTarget())
        assertRejected(provider, OneToOneMmsReactionRouteRejection.TARGET_MISSING_OR_AMBIGUOUS)

        provider.target = listOf(validTarget().copy(id = MMS_ID + 1))
        assertRejected(provider, OneToOneMmsReactionRouteRejection.TARGET_MISSING_OR_AMBIGUOUS)

        provider.target = listOf(validTarget().copy(threadId = THREAD_ID + 1))
        assertRejected(provider, OneToOneMmsReactionRouteRejection.STALE_NAVIGATION)

        provider.target = listOf(validTarget().copy(subscriptionId = SUB_ID + 1))
        assertRejected(provider, OneToOneMmsReactionRouteRejection.SUBSCRIPTION_MISMATCH)

        provider.target = listOf(validTarget().copy(direction = ProviderMmsDirection.UNSUPPORTED))
        assertRejected(provider, OneToOneMmsReactionRouteRejection.TARGET_DIRECTION_UNSUPPORTED)
    }

    @Test
    fun threadMustContainExactlyOneUniquePositiveRecipientId() {
        val provider = validProvider().apply { thread = emptyList() }
        assertRejected(provider, OneToOneMmsReactionRouteRejection.THREAD_MISSING_OR_AMBIGUOUS)

        provider.thread = listOf(ProviderThread(THREAD_ID, "11"), ProviderThread(THREAD_ID, "11"))
        assertRejected(provider, OneToOneMmsReactionRouteRejection.THREAD_MISSING_OR_AMBIGUOUS)

        listOf(null, "", "0", "email", "11 -2").forEach { recipientIds ->
            provider.thread = listOf(ProviderThread(THREAD_ID, recipientIds))
            assertRejected(
                provider,
                OneToOneMmsReactionRouteRejection.RECIPIENT_IDS_MISSING_OR_INVALID,
            )
        }

        provider.thread = listOf(ProviderThread(THREAD_ID, "11 11"))
        assertRejected(provider, OneToOneMmsReactionRouteRejection.DUPLICATE_PARTICIPANT)

        provider.thread = listOf(ProviderThread(THREAD_ID, "11 12"))
        assertRejected(provider, OneToOneMmsReactionRouteRejection.NOT_ONE_TO_ONE)
    }

    @Test
    fun canonicalRemoteMustBeExactSupportedAndDifferentFromSelf() {
        val provider = validProvider().apply { canonicalAddresses = emptyList() }
        assertRejected(
            provider,
            OneToOneMmsReactionRouteRejection.CANONICAL_ADDRESS_MISSING_OR_AMBIGUOUS,
        )

        provider.canonicalAddresses = listOf(
            ProviderCanonicalAddress(11, "+13125550101"),
            ProviderCanonicalAddress(11, "+13125550101"),
        )
        assertRejected(
            provider,
            OneToOneMmsReactionRouteRejection.CANONICAL_ADDRESS_MISSING_OR_AMBIGUOUS,
        )

        provider.canonicalAddresses = listOf(ProviderCanonicalAddress(12, "+13125550101"))
        assertRejected(
            provider,
            OneToOneMmsReactionRouteRejection.CANONICAL_ADDRESS_MISSING_OR_AMBIGUOUS,
        )

        provider.canonicalAddresses = listOf(ProviderCanonicalAddress(11, "not-a-phone"))
        assertRejected(provider, OneToOneMmsReactionRouteRejection.UNSUPPORTED_PARTICIPANT)

        provider.canonicalAddresses = listOf(ProviderCanonicalAddress(11, "+13125550100"))
        assertRejected(provider, OneToOneMmsReactionRouteRejection.SELF_RECIPIENT)
    }

    @Test
    fun activeSubscriptionMustUniquelyProveRegionAndSelf() {
        val provider = validProvider().apply { subscriptions = emptyList() }
        assertRejected(
            provider,
            OneToOneMmsReactionRouteRejection.SUBSCRIPTION_NOT_ACTIVE_OR_AMBIGUOUS,
        )

        provider.subscriptions = listOf(validSubscription(), validSubscription())
        assertRejected(
            provider,
            OneToOneMmsReactionRouteRejection.SUBSCRIPTION_NOT_ACTIVE_OR_AMBIGUOUS,
        )

        listOf(null, "", "AQ").forEach { countryIso ->
            provider.subscriptions = listOf(validSubscription().copy(countryIso = countryIso))
            assertRejected(
                provider,
                OneToOneMmsReactionRouteRejection.SUBSCRIPTION_REGION_MISSING_OR_UNSUPPORTED,
            )
        }

        provider.subscriptions = listOf(validSubscription().copy(selfAddress = null))
        assertRejected(
            provider,
            OneToOneMmsReactionRouteRejection.SELF_ADDRESS_MISSING_OR_UNSUPPORTED,
        )

        provider.subscriptions = listOf(validSubscription())
        assertRejected(
            provider,
            OneToOneMmsReactionRouteRejection.REGION_MISMATCH,
            request().copy(region = "CA"),
        )
        assertEquals("US", resolver(provider).resolvedRoute(request().copy(region = " us ")).region)
    }

    @Test
    fun incomingAndOutgoingHeadersMustProveSelfRemoteAndDirection() {
        val provider = validProvider().apply { addresses = emptyList() }
        assertRejected(
            provider,
            OneToOneMmsReactionRouteRejection.HEADER_WITNESS_MISSING_OR_AMBIGUOUS,
        )

        provider.addresses = listOf(
            ProviderMmsAddress(ProviderMmsAddressType.FROM, "+13125550101"),
            ProviderMmsAddress(ProviderMmsAddressType.TO, "+13125550100"),
            ProviderMmsAddress(ProviderMmsAddressType.CC, "+13125550102"),
        )
        assertRejected(
            provider,
            OneToOneMmsReactionRouteRejection.HEADER_WITNESS_MISSING_OR_AMBIGUOUS,
        )

        provider.addresses = listOf(
            ProviderMmsAddress(ProviderMmsAddressType.FROM, "+13125550100"),
            ProviderMmsAddress(ProviderMmsAddressType.TO, "+13125550101"),
        )
        assertRejected(provider, OneToOneMmsReactionRouteRejection.HEADER_SELF_MISMATCH)

        provider.addresses = listOf(
            ProviderMmsAddress(ProviderMmsAddressType.FROM, "+13125550102"),
            ProviderMmsAddress(ProviderMmsAddressType.TO, "+13125550100"),
        )
        assertRejected(provider, OneToOneMmsReactionRouteRejection.HEADER_PARTICIPANT_MISMATCH)
    }

    @Test
    fun onlyTextPlainAndSmilPartsAreAccepted() {
        val provider = validProvider().apply { parts = emptyList() }
        assertRejected(provider, OneToOneMmsReactionRouteRejection.PARTS_MISSING_OR_AMBIGUOUS)

        provider.parts = listOf(validTextPart().copy(messageId = MMS_ID + 1))
        assertRejected(provider, OneToOneMmsReactionRouteRejection.PARTS_MISSING_OR_AMBIGUOUS)

        provider.parts = listOf(validTextPart(), validTextPart())
        assertRejected(provider, OneToOneMmsReactionRouteRejection.PARTS_MISSING_OR_AMBIGUOUS)

        listOf("image/jpeg", "video/mp4", "audio/ogg", "text/x-vcard", null).forEach { type ->
            provider.parts = listOf(validTextPart().copy(contentType = type))
            assertRejected(provider, OneToOneMmsReactionRouteRejection.NON_TEXT_PART)
        }

        provider.parts = listOf(validTextPart().copy(dataPath = "/provider/media/100"))
        assertRejected(provider, OneToOneMmsReactionRouteRejection.NON_TEXT_PART)
    }

    @Test
    fun bodyMustContainNonblankTextAndFingerprintsBindEveryPart() {
        val provider = validProvider()
        val resolution = resolver(provider)
        val route = resolution.resolvedRoute()

        listOf(null, "", "  ").forEach { text ->
            provider.parts = listOf(validTextPart().copy(text = text))
            assertRejected(provider, OneToOneMmsReactionRouteRejection.BODY_MISSING_OR_UNSUPPORTED)
        }

        provider.parts = listOf(validTextPart())
        assertEquals(OneToOneMmsReactionRouteResult.Resolved(route), resolution.reverify(route))

        provider.parts = listOf(validTextPart().copy(text = "changed"))
        assertEquals(
            OneToOneMmsReactionRouteResult.Rejected(
                OneToOneMmsReactionRouteRejection.ROUTE_CHANGED
            ),
            resolution.reverify(route),
        )

        provider.parts = listOf(
            validTextPart(),
            ProviderOneToOneMmsPart(101, MMS_ID, -1, "application/smil", "<smil/>")
        )
        assertEquals(
            OneToOneMmsReactionRouteResult.Rejected(
                OneToOneMmsReactionRouteRejection.ROUTE_CHANGED
            ),
            resolution.reverify(route),
        )
    }

    @Test
    fun reverifyRejectsRecipientPermissionAndPersistedProofDrift() {
        val provider = validProvider()
        val resolution = resolver(provider)
        val route = resolution.resolvedRoute()

        provider.subscriptions = emptyList()
        assertEquals(
            OneToOneMmsReactionRouteResult.Rejected(
                OneToOneMmsReactionRouteRejection.SUBSCRIPTION_NOT_ACTIVE_OR_AMBIGUOUS
            ),
            resolution.reverify(route),
        )

        provider.subscriptions = listOf(validSubscription())
        provider.canonicalAddresses = listOf(ProviderCanonicalAddress(11, "+13125550102"))
        assertEquals(
            OneToOneMmsReactionRouteResult.Rejected(
                OneToOneMmsReactionRouteRejection.HEADER_PARTICIPANT_MISMATCH
            ),
            resolution.reverify(route),
        )

        provider.canonicalAddresses = validProvider().canonicalAddresses
        val alteredProof = route.copy(bodyFingerprint = "0".repeat(64))
        assertEquals(
            OneToOneMmsReactionRouteResult.Rejected(
                OneToOneMmsReactionRouteRejection.ROUTE_CHANGED
            ),
            resolution.reverify(alteredProof),
        )
    }

    @Test
    fun invalidRequestAndProviderFailureFailClosedBeforeMutation() {
        val provider = validProvider()
        listOf(
            request().copy(providerMmsId = 0),
            request().copy(navigationThreadId = 0),
            request().copy(activeSubscriptionId = -1),
            request().copy(region = "ZZ"),
        ).forEach { invalid ->
            assertRejected(provider, OneToOneMmsReactionRouteRejection.INVALID_REQUEST, invalid)
        }
        assertTrue(provider.targetRequests.isEmpty())

        provider.targetFailure = IllegalStateException("provider unavailable")
        assertRejected(provider, OneToOneMmsReactionRouteRejection.PROVIDER_UNAVAILABLE)
    }

    private fun resolver(
        provider: FakeProvider,
    ): OneToOneMmsReactionRouteResolution =
        OneToOneMmsReactionRouteResolution(provider, TestUsCanonicalizer)

    private fun OneToOneMmsReactionRouteResolution.resolvedRoute(
        request: OneToOneMmsReactionRouteRequest = request(),
    ): OneToOneMmsReactionRoute {
        val result = resolve(request)
        assertTrue("Expected resolved route but was $result", result is OneToOneMmsReactionRouteResult.Resolved)
        return (result as OneToOneMmsReactionRouteResult.Resolved).route
    }

    private fun assertRejected(
        provider: FakeProvider,
        reason: OneToOneMmsReactionRouteRejection,
        request: OneToOneMmsReactionRouteRequest = request(),
    ) {
        assertEquals(
            OneToOneMmsReactionRouteResult.Rejected(reason),
            resolver(provider).resolve(request),
        )
    }

    private fun validProvider(): FakeProvider = FakeProvider(
        target = listOf(validTarget()),
        thread = listOf(ProviderThread(THREAD_ID, "11")),
        canonicalAddresses = listOf(ProviderCanonicalAddress(11, "+13125550101")),
        addresses = listOf(
            ProviderMmsAddress(ProviderMmsAddressType.FROM, "+13125550101"),
            ProviderMmsAddress(ProviderMmsAddressType.TO, "+13125550100"),
        ),
        parts = listOf(validTextPart()),
        subscriptions = listOf(validSubscription()),
    )

    private fun validTarget() = ProviderMmsTarget(
        MMS_ID,
        THREAD_ID,
        SUB_ID,
        ProviderMmsDirection.INCOMING,
    )

    private fun validTextPart() =
        ProviderOneToOneMmsPart(100, MMS_ID, 0, "text/plain", "hello")

    private fun validSubscription() =
        ProviderOneToOneMmsSubscription(SUB_ID, "+13125550100", "US")

    private fun request() = OneToOneMmsReactionRouteRequest(MMS_ID, THREAD_ID, SUB_ID, "US")

    private class FakeProvider(
        var target: List<ProviderMmsTarget>,
        var thread: List<ProviderThread>,
        var canonicalAddresses: List<ProviderCanonicalAddress>,
        var addresses: List<ProviderMmsAddress>,
        var parts: List<ProviderOneToOneMmsPart>,
        var subscriptions: List<ProviderOneToOneMmsSubscription>,
        var targetFailure: RuntimeException? = null,
    ) : OneToOneMmsReactionRouteProvider {
        val targetRequests = mutableListOf<Long>()
        val threadRequests = mutableListOf<Long>()
        val addressRequests = mutableListOf<Long>()
        val partRequests = mutableListOf<Long>()

        override fun loadTarget(providerMmsId: Long): List<ProviderMmsTarget> {
            targetRequests += providerMmsId
            targetFailure?.let { throw it }
            return target
        }

        override fun loadThread(threadId: Long): List<ProviderThread> {
            threadRequests += threadId
            return thread
        }

        override fun loadCanonicalAddresses(
            recipientIds: List<Long>,
        ): List<ProviderCanonicalAddress> = canonicalAddresses

        override fun loadMmsAddresses(providerMmsId: Long): List<ProviderMmsAddress> {
            addressRequests += providerMmsId
            return addresses
        }

        override fun loadMmsParts(providerMmsId: Long): List<ProviderOneToOneMmsPart> {
            partRequests += providerMmsId
            return parts
        }

        override fun loadActiveSubscriptions(): List<ProviderOneToOneMmsSubscription> =
            subscriptions
    }

    private object TestUsCanonicalizer : OneToOneMmsPhoneCanonicalizer {
        override fun supportsRegion(region: String): Boolean = region == "US"

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
