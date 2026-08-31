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

import com.google.android.mms.pdu_alt.PduHeaders
import java.security.MessageDigest
import java.util.Locale

internal data class ProviderOneToOneMmsPart(
    val id: Long,
    val messageId: Long,
    val sequence: Int,
    val contentType: String?,
    val text: String?,
    val dataPath: String? = null,
)

internal data class ProviderOneToOneMmsSubscription(
    val id: Int,
    val selfAddress: String?,
    val countryIso: String?,
)

/** Read-only boundary around the MMS, thread, address, part, and subscription providers. */
internal interface OneToOneMmsReactionRouteProvider {
    fun loadTarget(providerMmsId: Long): List<ProviderMmsTarget>

    fun loadThread(threadId: Long): List<ProviderThread>

    fun loadCanonicalAddresses(recipientIds: List<Long>): List<ProviderCanonicalAddress>

    fun loadMmsAddresses(providerMmsId: Long): List<ProviderMmsAddress>

    fun loadMmsParts(providerMmsId: Long): List<ProviderOneToOneMmsPart>

    fun loadActiveSubscriptions(): List<ProviderOneToOneMmsSubscription>
}

internal interface OneToOneMmsPhoneCanonicalizer {
    fun supportsRegion(region: String): Boolean

    fun toE164(address: String, region: String): String?
}

internal class OneToOneMmsReactionRouteResolution(
    private val provider: OneToOneMmsReactionRouteProvider,
    private val canonicalizer: OneToOneMmsPhoneCanonicalizer,
) : OneToOneMmsReactionRouteResolver {

    override fun resolve(
        request: OneToOneMmsReactionRouteRequest,
    ): OneToOneMmsReactionRouteResult = try {
        resolveSafely(request)
    } catch (_: Exception) {
        reject(OneToOneMmsReactionRouteRejection.PROVIDER_UNAVAILABLE)
    }

    private fun resolveSafely(
        request: OneToOneMmsReactionRouteRequest,
    ): OneToOneMmsReactionRouteResult {
        val requestedRegion = request.region.trim().uppercase(Locale.ROOT)
        if (
            request.providerMmsId <= 0L ||
            request.navigationThreadId <= 0L ||
            request.activeSubscriptionId < 0 ||
            requestedRegion !in ISO_REGIONS
        ) {
            return reject(OneToOneMmsReactionRouteRejection.INVALID_REQUEST)
        }

        val target = provider.loadTarget(request.providerMmsId).singleOrNull()
            ?: return reject(OneToOneMmsReactionRouteRejection.TARGET_MISSING_OR_AMBIGUOUS)
        if (target.id != request.providerMmsId) {
            return reject(OneToOneMmsReactionRouteRejection.TARGET_MISSING_OR_AMBIGUOUS)
        }
        if (target.threadId != request.navigationThreadId) {
            return reject(OneToOneMmsReactionRouteRejection.STALE_NAVIGATION)
        }
        if (target.subscriptionId != request.activeSubscriptionId) {
            return reject(OneToOneMmsReactionRouteRejection.SUBSCRIPTION_MISMATCH)
        }

        val direction = when (target.direction) {
            ProviderMmsDirection.INCOMING -> OneToOneMmsReactionDirection.INCOMING
            ProviderMmsDirection.OUTGOING -> OneToOneMmsReactionDirection.OUTGOING
            ProviderMmsDirection.UNSUPPORTED -> {
                return reject(OneToOneMmsReactionRouteRejection.TARGET_DIRECTION_UNSUPPORTED)
            }
        }

        val subscription = provider.loadActiveSubscriptions()
            .filter { it.id == request.activeSubscriptionId }
            .singleOrNull()
            ?: return reject(
                OneToOneMmsReactionRouteRejection.SUBSCRIPTION_NOT_ACTIVE_OR_AMBIGUOUS
            )
        val providerRegion = subscription.countryIso
            ?.trim()
            ?.uppercase(Locale.ROOT)
            ?.takeIf { it in ISO_REGIONS && canonicalizer.supportsRegion(it) }
            ?: return reject(
                OneToOneMmsReactionRouteRejection.SUBSCRIPTION_REGION_MISSING_OR_UNSUPPORTED
            )
        if (providerRegion != requestedRegion) {
            return reject(OneToOneMmsReactionRouteRejection.REGION_MISMATCH)
        }
        // SubscriptionInfo.number can be absent or carrier-redacted. A usable number remains an
        // extra self check, while an outgoing FROM insert-address-token can prove self without it.
        val selfE164 = subscription.selfAddress
            ?.takeIf(String::isNotBlank)
            ?.let { canonicalizer.toE164(it, providerRegion) }

        val thread = provider.loadThread(target.threadId).singleOrNull()
            ?: return reject(OneToOneMmsReactionRouteRejection.THREAD_MISSING_OR_AMBIGUOUS)
        if (thread.id != target.threadId) {
            return reject(OneToOneMmsReactionRouteRejection.THREAD_MISSING_OR_AMBIGUOUS)
        }
        val recipientIds = parseRecipientIds(thread.recipientIds)
            ?: return reject(
                OneToOneMmsReactionRouteRejection.RECIPIENT_IDS_MISSING_OR_INVALID
            )
        if (recipientIds.toSet().size != recipientIds.size) {
            return reject(OneToOneMmsReactionRouteRejection.DUPLICATE_PARTICIPANT)
        }
        if (recipientIds.size != 1) {
            return reject(OneToOneMmsReactionRouteRejection.NOT_ONE_TO_ONE)
        }

        val canonicalRows = provider.loadCanonicalAddresses(recipientIds)
        if (canonicalRows.size != 1 || canonicalRows.single().id != recipientIds.single()) {
            return reject(
                OneToOneMmsReactionRouteRejection.CANONICAL_ADDRESS_MISSING_OR_AMBIGUOUS
            )
        }
        val remoteE164 = canonicalRows.single().address
            ?.takeIf(String::isNotBlank)
            ?.let { canonicalizer.toE164(it, providerRegion) }
            ?: return reject(OneToOneMmsReactionRouteRejection.UNSUPPORTED_PARTICIPANT)
        if (selfE164 != null && remoteE164 == selfE164) {
            return reject(OneToOneMmsReactionRouteRejection.SELF_RECIPIENT)
        }

        val addressRows = provider.loadMmsAddresses(target.id)
        val fromRows = addressRows.filter { it.type == ProviderMmsAddressType.FROM }
        val recipientRows = addressRows.filter {
            it.type == ProviderMmsAddressType.TO || it.type == ProviderMmsAddressType.CC
        }
        if (fromRows.size != 1 || recipientRows.size != 1 || addressRows.size != 2) {
            return reject(
                OneToOneMmsReactionRouteRejection.HEADER_WITNESS_MISSING_OR_AMBIGUOUS
            )
        }
        val selfTokenRows = addressRows.filter {
            it.address == PduHeaders.FROM_INSERT_ADDRESS_TOKEN_STR
        }
        if (selfTokenRows.size > 1) {
            return reject(
                OneToOneMmsReactionRouteRejection.HEADER_WITNESS_MISSING_OR_AMBIGUOUS
            )
        }
        val hasOutgoingFromSelfToken = selfTokenRows.singleOrNull()?.let { tokenRow ->
            direction == OneToOneMmsReactionDirection.OUTGOING &&
                tokenRow.type == ProviderMmsAddressType.FROM
        } == true
        if (selfTokenRows.isNotEmpty() && !hasOutgoingFromSelfToken) {
            return reject(OneToOneMmsReactionRouteRejection.HEADER_SELF_MISMATCH)
        }
        if (selfE164 == null && !hasOutgoingFromSelfToken) {
            return reject(
                OneToOneMmsReactionRouteRejection.SELF_ADDRESS_MISSING_OR_UNSUPPORTED
            )
        }
        val rawFromAddress = fromRows.single().address?.takeIf(String::isNotBlank)
            ?: return reject(OneToOneMmsReactionRouteRejection.UNSUPPORTED_PARTICIPANT)
        val fromE164 = if (hasOutgoingFromSelfToken) {
            null
        } else {
            canonicalizer.toE164(rawFromAddress, providerRegion)
                ?: return reject(OneToOneMmsReactionRouteRejection.UNSUPPORTED_PARTICIPANT)
        }
        val headerRecipientE164 = recipientRows.single().address
            ?.takeIf(String::isNotBlank)
            ?.let { canonicalizer.toE164(it, providerRegion) }
            ?: return reject(OneToOneMmsReactionRouteRejection.UNSUPPORTED_PARTICIPANT)
        if (fromE164 != null && fromE164 == headerRecipientE164) {
            return reject(OneToOneMmsReactionRouteRejection.DUPLICATE_PARTICIPANT)
        }
        when (direction) {
            OneToOneMmsReactionDirection.INCOMING -> {
                val knownSelfE164 = selfE164 ?: return reject(
                    OneToOneMmsReactionRouteRejection.SELF_ADDRESS_MISSING_OR_UNSUPPORTED
                )
                if (fromE164 == knownSelfE164 || headerRecipientE164 != knownSelfE164) {
                    return reject(OneToOneMmsReactionRouteRejection.HEADER_SELF_MISMATCH)
                }
                if (fromE164 != remoteE164) {
                    return reject(
                        OneToOneMmsReactionRouteRejection.HEADER_PARTICIPANT_MISMATCH
                    )
                }
            }

            OneToOneMmsReactionDirection.OUTGOING -> {
                if (
                    (!hasOutgoingFromSelfToken && fromE164 != selfE164) ||
                    (selfE164 != null && headerRecipientE164 == selfE164)
                ) {
                    return reject(OneToOneMmsReactionRouteRejection.HEADER_SELF_MISMATCH)
                }
                if (headerRecipientE164 != remoteE164) {
                    return reject(
                        OneToOneMmsReactionRouteRejection.HEADER_PARTICIPANT_MISMATCH
                    )
                }
            }
        }

        val parts = provider.loadMmsParts(target.id)
        if (
            parts.isEmpty() ||
            parts.any { it.id <= 0L || it.messageId != target.id } ||
            parts.map(ProviderOneToOneMmsPart::id).toSet().size != parts.size
        ) {
            return reject(OneToOneMmsReactionRouteRejection.PARTS_MISSING_OR_AMBIGUOUS)
        }
        val orderedParts = parts.sortedWith(
            compareBy<ProviderOneToOneMmsPart> { it.sequence }.thenBy { it.id }
        )
        val typedParts = orderedParts.map { part ->
            val contentType = part.contentType?.trim()?.lowercase(Locale.ROOT)
                ?: return reject(OneToOneMmsReactionRouteRejection.NON_TEXT_PART)
            if (contentType !in ALLOWED_TEXT_CONTENT_TYPES || !part.dataPath.isNullOrBlank()) {
                return reject(OneToOneMmsReactionRouteRejection.NON_TEXT_PART)
            }
            part.copy(contentType = contentType)
        }
        val bodyParts = typedParts
            .filter { it.contentType == TEXT_PLAIN }
            .map { it.text ?: return reject(
                OneToOneMmsReactionRouteRejection.BODY_MISSING_OR_UNSUPPORTED
            ) }
            .filter(String::isNotBlank)
        if (bodyParts.isEmpty()) {
            return reject(OneToOneMmsReactionRouteRejection.BODY_MISSING_OR_UNSUPPORTED)
        }
        val targetBody = bodyParts.joinToString("\n")

        return OneToOneMmsReactionRouteResult.Resolved(
            OneToOneMmsReactionRoute(
                providerMmsId = target.id,
                threadId = target.threadId,
                subscriptionId = target.subscriptionId,
                region = providerRegion,
                direction = direction,
                remoteRecipientE164 = remoteE164,
                targetBody = targetBody,
                participantFingerprint = participantFingerprint(remoteE164),
                bodyFingerprint = bodyFingerprint(typedParts, targetBody),
            )
        )
    }

    private fun parseRecipientIds(rawRecipientIds: String?): List<Long>? {
        val raw = rawRecipientIds?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return raw.split(Regex("\\s+")).map { token ->
            token.toLongOrNull()?.takeIf { it > 0L } ?: return null
        }.takeIf(List<Long>::isNotEmpty)
    }

    private fun participantFingerprint(remoteE164: String): String = sha256(
        "$PARTICIPANT_FINGERPRINT_VERSION\nremote=$remoteE164"
    )

    private fun bodyFingerprint(
        orderedParts: List<ProviderOneToOneMmsPart>,
        targetBody: String,
    ): String = sha256(buildString {
        append(BODY_FINGERPRINT_VERSION)
        orderedParts.forEach { part ->
            val text = part.text
            append("\npart=").append(part.id)
            append("\nsequence=").append(part.sequence)
            append("\ntype=").append(part.contentType)
            append("\ndata=").append(part.dataPath ?: "null")
            append("\ntext=")
            if (text == null) append("null") else append(text.length).append(':').append(text)
        }
        append("\nbody=").append(targetBody.length).append(':').append(targetBody)
    })

    private fun sha256(input: String): String = MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }

    private fun reject(
        reason: OneToOneMmsReactionRouteRejection,
    ): OneToOneMmsReactionRouteResult = OneToOneMmsReactionRouteResult.Rejected(reason)

    private companion object {
        const val TEXT_PLAIN = "text/plain"
        val ALLOWED_TEXT_CONTENT_TYPES = setOf(TEXT_PLAIN, "application/smil")
        const val PARTICIPANT_FINGERPRINT_VERSION =
            "quik-one-to-one-mms-remote-participant-v1"
        const val BODY_FINGERPRINT_VERSION = "quik-one-to-one-mms-body-parts-v1"
        val ISO_REGIONS: Set<String> = Locale.getISOCountries().toSet()
    }
}
