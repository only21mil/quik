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

/**
 * Identifies an SMS in Android's telephony provider and the compose state that selected it.
 *
 * [region] is the ISO 3166-1 alpha-2 region captured from the selected subscription when the user
 * chose the reaction target. Resolution requires it to match the current active subscription's
 * provider region. The caller cannot choose the region used to interpret a local phone number.
 */
data class OneToOneSmsReactionRouteRequest(
    val providerSmsId: Long,
    val navigationThreadId: Long,
    val activeSubscriptionId: Int,
    val region: String,
    /** Exact text shown for the selected provider SMS. No normalization is applied. */
    val targetBody: String,
)

enum class OneToOneSmsReactionDirection {
    INCOMING,
    OUTGOING,
}

/**
 * Immutable provider proof for one raw SMS recipient.
 *
 * A transport may persist this value and pass it to [OneToOneSmsReactionRouteResolver.reverify]
 * before provider staging and again when correlating a callback. Reverification reloads the SMS,
 * thread membership, canonical address, and active subscription rather than trusting this proof.
 * [region] comes from the active subscription provider and [participantFingerprint] covers the
 * canonical remote participant. [targetBodyFingerprint] covers the exact, unnormalized provider
 * body without retaining a second copy of the message text. A subscription line number is not
 * persisted because Android may redact or omit it; when available, resolution uses it only to
 * reject a positive self-match.
 */
data class OneToOneSmsReactionRoute(
    val providerSmsId: Long,
    val threadId: Long,
    val subscriptionId: Int,
    val region: String,
    val direction: OneToOneSmsReactionDirection,
    val remoteRecipientE164: String,
    val participantFingerprint: String,
    val targetBodyFingerprint: String,
)

enum class OneToOneSmsReactionRouteRejection {
    INVALID_REQUEST,
    TARGET_MISSING_OR_AMBIGUOUS,
    TARGET_DIRECTION_UNSUPPORTED,
    TARGET_ADDRESS_MISSING_OR_UNSUPPORTED,
    TARGET_BODY_MISSING_OR_MISMATCH,
    STALE_NAVIGATION,
    SUBSCRIPTION_MISMATCH,
    SUBSCRIPTION_NOT_ACTIVE_OR_AMBIGUOUS,
    SUBSCRIPTION_REGION_MISSING_OR_UNSUPPORTED,
    REGION_MISMATCH,
    THREAD_MISSING_OR_AMBIGUOUS,
    RECIPIENT_IDS_MISSING_OR_INVALID,
    NOT_ONE_TO_ONE,
    CANONICAL_ADDRESS_MISSING_OR_AMBIGUOUS,
    UNSUPPORTED_PARTICIPANT,
    DUPLICATE_PARTICIPANT,
    SELF_RECIPIENT,
    THREAD_RECIPIENT_MISMATCH,
    ROUTE_CHANGED,
    PROVIDER_UNAVAILABLE,
}

sealed class OneToOneSmsReactionRouteResult {
    data class Resolved(
        val route: OneToOneSmsReactionRoute,
    ) : OneToOneSmsReactionRouteResult()

    data class Rejected(
        val reason: OneToOneSmsReactionRouteRejection,
    ) : OneToOneSmsReactionRouteResult()
}

interface OneToOneSmsReactionRouteResolver {
    fun resolve(request: OneToOneSmsReactionRouteRequest): OneToOneSmsReactionRouteResult

    /** Reloads provider evidence and accepts it only when every persisted route field is unchanged. */
    fun reverify(route: OneToOneSmsReactionRoute): OneToOneSmsReactionRouteResult
}
