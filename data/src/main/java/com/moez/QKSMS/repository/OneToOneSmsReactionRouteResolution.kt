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

import java.security.MessageDigest
import java.util.Locale

internal enum class ProviderSmsDirection {
    INCOMING,
    OUTGOING,
    UNSUPPORTED,
}

internal data class ProviderSmsTarget(
    val id: Long,
    val threadId: Long,
    val subscriptionId: Int,
    val address: String?,
    val body: String?,
    val direction: ProviderSmsDirection,
)

internal data class ProviderSmsThread(
    val id: Long,
    val recipientIds: String?,
)

internal data class ProviderSmsCanonicalAddress(
    val id: Long,
    val address: String?,
)

internal data class ProviderSmsSubscription(
    val id: Int,
    val selfAddress: String?,
    val countryIso: String?,
)

/** Read-only boundary around Android's SMS, thread, canonical-address, and subscription data. */
internal interface OneToOneSmsReactionRouteProvider {
    fun loadTarget(providerSmsId: Long): List<ProviderSmsTarget>

    fun loadThread(threadId: Long): List<ProviderSmsThread>

    fun loadCanonicalAddresses(recipientIds: List<Long>): List<ProviderSmsCanonicalAddress>

    fun loadActiveSubscriptions(): List<ProviderSmsSubscription>
}

internal interface OneToOneSmsPhoneCanonicalizer {
    fun supportsRegion(region: String): Boolean

    fun toE164(address: String, region: String): String?
}

internal class OneToOneSmsReactionRouteResolution(
    private val provider: OneToOneSmsReactionRouteProvider,
    private val canonicalizer: OneToOneSmsPhoneCanonicalizer,
) : OneToOneSmsReactionRouteResolver {

    override fun resolve(
        request: OneToOneSmsReactionRouteRequest,
    ): OneToOneSmsReactionRouteResult = try {
        resolveSafely(
            providerSmsId = request.providerSmsId,
            navigationThreadId = request.navigationThreadId,
            activeSubscriptionId = request.activeSubscriptionId,
            region = request.region,
            targetBodyExpectation = TargetBodyExpectation.Exact(request.targetBody),
        )
    } catch (_: Exception) {
        reject(OneToOneSmsReactionRouteRejection.PROVIDER_UNAVAILABLE)
    }

    override fun reverify(
        route: OneToOneSmsReactionRoute,
    ): OneToOneSmsReactionRouteResult = try {
        val current = resolveSafely(
            providerSmsId = route.providerSmsId,
            navigationThreadId = route.threadId,
            activeSubscriptionId = route.subscriptionId,
            region = route.region,
            targetBodyExpectation = TargetBodyExpectation.AnyPresent,
        )
        when {
            current !is OneToOneSmsReactionRouteResult.Resolved -> current
            current.route == route -> current
            else -> reject(OneToOneSmsReactionRouteRejection.ROUTE_CHANGED)
        }
    } catch (_: Exception) {
        reject(OneToOneSmsReactionRouteRejection.PROVIDER_UNAVAILABLE)
    }

    private fun resolveSafely(
        providerSmsId: Long,
        navigationThreadId: Long,
        activeSubscriptionId: Int,
        region: String,
        targetBodyExpectation: TargetBodyExpectation,
    ): OneToOneSmsReactionRouteResult {
        val requestedRegion = region.trim().uppercase(Locale.ROOT)
        if (
            providerSmsId <= 0L ||
            navigationThreadId <= 0L ||
            activeSubscriptionId < 0 ||
            requestedRegion !in ISO_REGIONS
        ) {
            return reject(OneToOneSmsReactionRouteRejection.INVALID_REQUEST)
        }

        val target = provider.loadTarget(providerSmsId).singleOrNull()
            ?: return reject(OneToOneSmsReactionRouteRejection.TARGET_MISSING_OR_AMBIGUOUS)
        if (target.id != providerSmsId) {
            return reject(OneToOneSmsReactionRouteRejection.TARGET_MISSING_OR_AMBIGUOUS)
        }
        if (target.threadId != navigationThreadId) {
            return reject(OneToOneSmsReactionRouteRejection.STALE_NAVIGATION)
        }
        if (target.subscriptionId != activeSubscriptionId) {
            return reject(OneToOneSmsReactionRouteRejection.SUBSCRIPTION_MISMATCH)
        }

        val targetBody = target.body
            ?: return reject(OneToOneSmsReactionRouteRejection.TARGET_BODY_MISSING_OR_MISMATCH)
        val targetBodyFingerprint = fingerprintTargetBody(targetBody)
        val targetBodyMatches = when (targetBodyExpectation) {
            is TargetBodyExpectation.Exact -> targetBody == targetBodyExpectation.body
            TargetBodyExpectation.AnyPresent -> true
        }
        if (!targetBodyMatches) {
            return reject(OneToOneSmsReactionRouteRejection.TARGET_BODY_MISSING_OR_MISMATCH)
        }

        val direction = when (target.direction) {
            ProviderSmsDirection.INCOMING -> OneToOneSmsReactionDirection.INCOMING
            ProviderSmsDirection.OUTGOING -> OneToOneSmsReactionDirection.OUTGOING
            ProviderSmsDirection.UNSUPPORTED -> {
                return reject(OneToOneSmsReactionRouteRejection.TARGET_DIRECTION_UNSUPPORTED)
            }
        }

        val subscription = provider.loadActiveSubscriptions()
            .filter { it.id == activeSubscriptionId }
            .singleOrNull()
            ?: return reject(
                OneToOneSmsReactionRouteRejection.SUBSCRIPTION_NOT_ACTIVE_OR_AMBIGUOUS
            )
        val providerRegion = subscription.countryIso
            ?.trim()
            ?.uppercase(Locale.ROOT)
            ?.takeIf { it in ISO_REGIONS && canonicalizer.supportsRegion(it) }
            ?: return reject(
                OneToOneSmsReactionRouteRejection.SUBSCRIPTION_REGION_MISSING_OR_UNSUPPORTED
            )
        if (requestedRegion != providerRegion) {
            return reject(OneToOneSmsReactionRouteRejection.REGION_MISMATCH)
        }

        // SubscriptionInfo.number is frequently absent or carrier-redacted. It can prove that a
        // recipient is self when it canonicalizes, but its absence cannot invalidate provider
        // thread membership and it is never included in the persisted route proof.
        val selfE164 = subscription.selfAddress
            ?.takeIf(String::isNotBlank)
            ?.let { canonicalizer.toE164(it, providerRegion) }

        val thread = provider.loadThread(target.threadId).singleOrNull()
            ?: return reject(OneToOneSmsReactionRouteRejection.THREAD_MISSING_OR_AMBIGUOUS)
        if (thread.id != target.threadId) {
            return reject(OneToOneSmsReactionRouteRejection.THREAD_MISSING_OR_AMBIGUOUS)
        }

        val recipientIds = parseRecipientIds(thread.recipientIds)
            ?: return reject(
                OneToOneSmsReactionRouteRejection.RECIPIENT_IDS_MISSING_OR_INVALID
            )
        if (recipientIds.toSet().size != recipientIds.size) {
            return reject(OneToOneSmsReactionRouteRejection.DUPLICATE_PARTICIPANT)
        }
        if (recipientIds.size != 1) {
            return reject(OneToOneSmsReactionRouteRejection.NOT_ONE_TO_ONE)
        }

        val canonicalRows = provider.loadCanonicalAddresses(recipientIds)
        if (
            canonicalRows.size != 1 ||
            canonicalRows.single().id != recipientIds.single()
        ) {
            return reject(
                OneToOneSmsReactionRouteRejection.CANONICAL_ADDRESS_MISSING_OR_AMBIGUOUS
            )
        }
        val remoteE164 = canonicalRows.single().address
            ?.takeIf(String::isNotBlank)
            ?.let { canonicalizer.toE164(it, providerRegion) }
            ?: return reject(OneToOneSmsReactionRouteRejection.UNSUPPORTED_PARTICIPANT)
        if (selfE164 != null && remoteE164 == selfE164) {
            return reject(OneToOneSmsReactionRouteRejection.SELF_RECIPIENT)
        }

        val targetAddressE164 = target.address
            ?.takeIf(String::isNotBlank)
            ?.let { canonicalizer.toE164(it, providerRegion) }
            ?: return reject(
                OneToOneSmsReactionRouteRejection.TARGET_ADDRESS_MISSING_OR_UNSUPPORTED
            )
        if (selfE164 != null && targetAddressE164 == selfE164) {
            return reject(OneToOneSmsReactionRouteRejection.SELF_RECIPIENT)
        }
        if (targetAddressE164 != remoteE164) {
            return reject(OneToOneSmsReactionRouteRejection.THREAD_RECIPIENT_MISMATCH)
        }

        return OneToOneSmsReactionRouteResult.Resolved(
            OneToOneSmsReactionRoute(
                providerSmsId = target.id,
                threadId = target.threadId,
                subscriptionId = target.subscriptionId,
                region = providerRegion,
                direction = direction,
                remoteRecipientE164 = remoteE164,
                participantFingerprint = fingerprint(remoteE164),
                targetBodyFingerprint = targetBodyFingerprint,
            )
        )
    }

    private fun parseRecipientIds(rawRecipientIds: String?): List<Long>? {
        val raw = rawRecipientIds?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return raw.split(Regex("\\s+")).map { token ->
            token.toLongOrNull()?.takeIf { it > 0L } ?: return null
        }.takeIf(List<Long>::isNotEmpty)
    }

    private fun fingerprint(remoteE164: String): String {
        val canonicalInput = buildString {
            append(FINGERPRINT_VERSION)
            append("\nremote=")
            append(remoteE164)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonicalInput.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
    }

    private fun fingerprintTargetBody(body: String): String {
        val canonicalInput = buildString {
            append(TARGET_BODY_FINGERPRINT_VERSION)
            append("\nbody=")
            append(body)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonicalInput.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
    }

    private fun reject(
        reason: OneToOneSmsReactionRouteRejection,
    ): OneToOneSmsReactionRouteResult = OneToOneSmsReactionRouteResult.Rejected(reason)

    private companion object {
        const val FINGERPRINT_VERSION = "quik-one-to-one-sms-remote-participant-v1"
        const val TARGET_BODY_FINGERPRINT_VERSION = "quik-one-to-one-sms-target-body-v1"
        val ISO_REGIONS: Set<String> = Locale.getISOCountries().toSet()
    }

    private sealed class TargetBodyExpectation {
        data class Exact(val body: String) : TargetBodyExpectation()

        object AnyPresent : TargetBodyExpectation()
    }
}
