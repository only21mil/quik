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

internal enum class ProviderMmsDirection {
    INCOMING,
    OUTGOING,
    UNSUPPORTED,
}

internal enum class ProviderMmsAddressType {
    FROM,
    TO,
    CC,
    UNSUPPORTED,
}

internal data class ProviderMmsTarget(
    val id: Long,
    val threadId: Long,
    val subscriptionId: Int,
    val direction: ProviderMmsDirection,
)

internal data class ProviderThread(
    val id: Long,
    val recipientIds: String?,
)

internal data class ProviderCanonicalAddress(
    val id: Long,
    val address: String?,
)

internal data class ProviderMmsAddress(
    val type: ProviderMmsAddressType,
    val address: String?,
)

internal data class ProviderSubscription(
    val id: Int,
    val selfAddress: String?,
    val countryIso: String?,
)

internal data class ProviderGroupMmsPart(
    val id: Long,
    val messageId: Long,
    val sequence: Int,
    val contentType: String?,
    val text: String?,
    val dataPath: String? = null,
)

/** Read-only boundary around Android's telephony providers and active subscriptions. */
internal interface GroupMmsReactionRouteProvider {
    fun loadTarget(mmsId: Long): List<ProviderMmsTarget>

    fun loadThread(threadId: Long): List<ProviderThread>

    fun loadCanonicalAddresses(recipientIds: List<Long>): List<ProviderCanonicalAddress>

    fun loadMmsAddresses(mmsId: Long): List<ProviderMmsAddress>

    fun loadMmsParts(mmsId: Long): List<ProviderGroupMmsPart>

    fun loadActiveSubscriptions(): List<ProviderSubscription>
}

internal interface GroupMmsPhoneCanonicalizer {
    fun toE164(address: String, region: String): String?

    fun supportsRegion(region: String): Boolean
}

internal class GroupMmsReactionRouteResolution(
    private val provider: GroupMmsReactionRouteProvider,
    private val canonicalizer: GroupMmsPhoneCanonicalizer,
) : GroupMmsReactionRouteResolver {

    override fun resolve(
        request: GroupMmsReactionRouteRequest,
    ): GroupMmsReactionRouteResult = try {
        resolveSafely(request)
    } catch (_: Exception) {
        reject(GroupMmsReactionRouteRejection.PROVIDER_UNAVAILABLE)
    }

    private fun resolveSafely(
        request: GroupMmsReactionRouteRequest,
    ): GroupMmsReactionRouteResult {
        val requestedRegion = request.region.trim().uppercase(Locale.ROOT)
        if (
            request.targetMmsId <= 0L ||
            request.navigationThreadId <= 0L ||
            request.activeSubscriptionId < 0 ||
            requestedRegion !in ISO_REGIONS
        ) {
            return reject(GroupMmsReactionRouteRejection.INVALID_REQUEST)
        }

        val target = provider.loadTarget(request.targetMmsId).singleOrNull()
            ?: return reject(GroupMmsReactionRouteRejection.TARGET_MISSING_OR_AMBIGUOUS)
        if (target.id != request.targetMmsId) {
            return reject(GroupMmsReactionRouteRejection.TARGET_MISSING_OR_AMBIGUOUS)
        }
        if (target.threadId != request.navigationThreadId) {
            return reject(GroupMmsReactionRouteRejection.STALE_NAVIGATION)
        }
        if (target.subscriptionId != request.activeSubscriptionId) {
            return reject(GroupMmsReactionRouteRejection.SUBSCRIPTION_MISMATCH)
        }

        val subscription = provider.loadActiveSubscriptions()
            .filter { it.id == request.activeSubscriptionId }
            .singleOrNull()
            ?: return reject(GroupMmsReactionRouteRejection.SUBSCRIPTION_NOT_ACTIVE)
        val providerRegion = subscription.countryIso
            ?.trim()
            ?.uppercase(Locale.ROOT)
            ?.takeIf { it in ISO_REGIONS && canonicalizer.supportsRegion(it) }
            ?: return reject(
                GroupMmsReactionRouteRejection.SUBSCRIPTION_REGION_MISSING_OR_UNSUPPORTED
            )
        if (providerRegion != requestedRegion) {
            return reject(GroupMmsReactionRouteRejection.REGION_MISMATCH)
        }
        val selfKey = subscription.selfAddress
            ?.takeIf(String::isNotBlank)
            ?.let { canonicalizer.toE164(it, providerRegion) }
            ?: return reject(GroupMmsReactionRouteRejection.SELF_ADDRESS_MISSING_OR_UNSUPPORTED)

        val thread = provider.loadThread(target.threadId).singleOrNull()
            ?: return reject(GroupMmsReactionRouteRejection.THREAD_MISSING_OR_AMBIGUOUS)
        if (thread.id != target.threadId) {
            return reject(GroupMmsReactionRouteRejection.THREAD_MISSING_OR_AMBIGUOUS)
        }

        val recipientIds = parseRecipientIds(thread.recipientIds)
            ?: return reject(GroupMmsReactionRouteRejection.RECIPIENT_IDS_MISSING_OR_INVALID)
        if (recipientIds.toSet().size != recipientIds.size) {
            return reject(GroupMmsReactionRouteRejection.RECIPIENT_IDS_MISSING_OR_INVALID)
        }

        val canonicalRows = provider.loadCanonicalAddresses(recipientIds)
        val canonicalRowsById = canonicalRows.groupBy(ProviderCanonicalAddress::id)
        if (
            canonicalRows.size != recipientIds.size ||
            canonicalRowsById.keys != recipientIds.toSet() ||
            canonicalRowsById.any { (_, rows) -> rows.size != 1 }
        ) {
            return reject(GroupMmsReactionRouteRejection.CANONICAL_ADDRESS_MISSING_OR_AMBIGUOUS)
        }

        val remoteKeys = mutableListOf<String>()
        recipientIds.forEach { recipientId ->
            val address = canonicalRowsById.getValue(recipientId).single().address
                ?.takeIf(String::isNotBlank)
                ?: return reject(GroupMmsReactionRouteRejection.UNSUPPORTED_PARTICIPANT)
            val key = canonicalizer.toE164(address, providerRegion)
                ?: return reject(GroupMmsReactionRouteRejection.UNSUPPORTED_PARTICIPANT)
            if (key == selfKey) {
                return reject(GroupMmsReactionRouteRejection.HEADER_SELF_MISMATCH)
            }
            remoteKeys += key
        }

        if (remoteKeys.toSet().size != remoteKeys.size) {
            return reject(GroupMmsReactionRouteRejection.DUPLICATE_PARTICIPANT)
        }
        val sortedRemoteKeys = remoteKeys.sorted()
        if (sortedRemoteKeys.size < MINIMUM_REMOTE_GROUP_SIZE) {
            return reject(GroupMmsReactionRouteRejection.NOT_TRUE_GROUP)
        }

        val addressRows = provider.loadMmsAddresses(target.id)
        if (addressRows.any { it.type == ProviderMmsAddressType.UNSUPPORTED }) {
            return reject(GroupMmsReactionRouteRejection.HEADER_WITNESS_MISSING_OR_AMBIGUOUS)
        }
        val fromRows = addressRows.filter { it.type == ProviderMmsAddressType.FROM }
        val recipientRows = addressRows.filter {
            it.type == ProviderMmsAddressType.TO || it.type == ProviderMmsAddressType.CC
        }
        if (fromRows.size != 1 || recipientRows.isEmpty()) {
            return reject(GroupMmsReactionRouteRejection.HEADER_WITNESS_MISSING_OR_AMBIGUOUS)
        }

        val headerKeys = addressRows.map { row ->
            val address = row.address?.takeIf(String::isNotBlank)
                ?: return reject(GroupMmsReactionRouteRejection.UNSUPPORTED_PARTICIPANT)
            if (
                target.direction == ProviderMmsDirection.OUTGOING &&
                row.type == ProviderMmsAddressType.FROM &&
                address == MMS_SELF_INSERT_ADDRESS_TOKEN
            ) {
                selfKey
            } else {
                canonicalizer.toE164(address, providerRegion)
                    ?: return reject(GroupMmsReactionRouteRejection.UNSUPPORTED_PARTICIPANT)
            }
        }
        if (headerKeys.toSet().size != headerKeys.size) {
            return reject(GroupMmsReactionRouteRejection.DUPLICATE_PARTICIPANT)
        }

        val fromKey = headerKeys[addressRows.indexOf(fromRows.single())]
        val recipientKeys = addressRows.zip(headerKeys)
            .filter { (row, _) -> row.type != ProviderMmsAddressType.FROM }
            .map { (_, key) -> key }
            .toSet()
        val direction = when (target.direction) {
            ProviderMmsDirection.INCOMING -> {
                if (fromKey == selfKey || selfKey !in recipientKeys) {
                    return reject(GroupMmsReactionRouteRejection.HEADER_SELF_MISMATCH)
                }
                val witnessedRemotes = recipientKeys.minus(selfKey).plus(fromKey)
                if (witnessedRemotes != sortedRemoteKeys.toSet()) {
                    return reject(GroupMmsReactionRouteRejection.HEADER_PARTICIPANT_MISMATCH)
                }
                GroupMmsReactionDirection.INCOMING
            }

            ProviderMmsDirection.OUTGOING -> {
                if (fromKey != selfKey || selfKey in recipientKeys) {
                    return reject(GroupMmsReactionRouteRejection.HEADER_SELF_MISMATCH)
                }
                if (recipientKeys != sortedRemoteKeys.toSet()) {
                    return reject(GroupMmsReactionRouteRejection.HEADER_PARTICIPANT_MISMATCH)
                }
                GroupMmsReactionDirection.OUTGOING
            }

            ProviderMmsDirection.UNSUPPORTED -> {
                return reject(GroupMmsReactionRouteRejection.HEADER_DIRECTION_UNSUPPORTED)
            }
        }

        val parts = provider.loadMmsParts(target.id)
        if (
            parts.isEmpty() ||
            parts.any { it.id <= 0L || it.messageId != target.id } ||
            parts.map(ProviderGroupMmsPart::id).toSet().size != parts.size
        ) {
            return reject(GroupMmsReactionRouteRejection.PARTS_MISSING_OR_AMBIGUOUS)
        }
        val orderedParts = parts.sortedWith(
            compareBy<ProviderGroupMmsPart> { it.sequence }.thenBy { it.id }
        )
        val typedParts = orderedParts.map { part ->
            val contentType = part.contentType?.trim()?.lowercase(Locale.ROOT)
                ?: return reject(GroupMmsReactionRouteRejection.NON_TEXT_PART)
            if (contentType !in ALLOWED_TEXT_CONTENT_TYPES || !part.dataPath.isNullOrBlank()) {
                return reject(GroupMmsReactionRouteRejection.NON_TEXT_PART)
            }
            part.copy(contentType = contentType)
        }
        val bodyParts = typedParts
            .filter { it.contentType == TEXT_PLAIN }
            .map {
                it.text ?: return reject(
                    GroupMmsReactionRouteRejection.BODY_MISSING_OR_UNSUPPORTED
                )
            }
            .filter(String::isNotBlank)
        if (bodyParts.isEmpty()) {
            return reject(GroupMmsReactionRouteRejection.BODY_MISSING_OR_UNSUPPORTED)
        }
        val targetBody = bodyParts.joinToString("\n")

        return GroupMmsReactionRouteResult.Resolved(
            GroupMmsReactionRoute(
                targetMmsId = target.id,
                threadId = target.threadId,
                subscriptionId = target.subscriptionId,
                region = providerRegion,
                direction = direction,
                selfParticipantKey = selfKey,
                remoteParticipantKeys = sortedRemoteKeys,
                targetBody = targetBody,
                participantFingerprint = participantFingerprint(
                    region = providerRegion,
                    selfParticipantKey = selfKey,
                    remoteParticipantKeys = sortedRemoteKeys,
                ),
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

    private fun participantFingerprint(
        region: String,
        selfParticipantKey: String,
        remoteParticipantKeys: List<String>,
    ): String = sha256(buildString {
        append(PARTICIPANT_FINGERPRINT_VERSION)
        append("\nregion=").append(region)
        append("\nself=").append(selfParticipantKey)
        remoteParticipantKeys.forEach { key -> append("\nremote=").append(key) }
    })

    private fun bodyFingerprint(
        orderedParts: List<ProviderGroupMmsPart>,
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
        reason: GroupMmsReactionRouteRejection,
    ): GroupMmsReactionRouteResult.Rejected = GroupMmsReactionRouteResult.Rejected(reason)

    private companion object {
        const val MINIMUM_REMOTE_GROUP_SIZE = 2
        const val TEXT_PLAIN = "text/plain"
        val ALLOWED_TEXT_CONTENT_TYPES = setOf(TEXT_PLAIN, "application/smil")
        const val PARTICIPANT_FINGERPRINT_VERSION = "quik-group-mms-participants-v2"
        const val BODY_FINGERPRINT_VERSION = "quik-group-mms-body-parts-v1"
        const val MMS_SELF_INSERT_ADDRESS_TOKEN = "insert-address-token"
        val ISO_REGIONS: Set<String> = Locale.getISOCountries().toSet()
    }
}
