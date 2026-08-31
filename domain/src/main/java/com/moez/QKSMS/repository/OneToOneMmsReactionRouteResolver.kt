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
 * Identifies a text-only MMS in Android's telephony provider and the compose state that selected
 * it. [region] is captured from the selected active subscription; callers cannot use the process
 * locale to reinterpret provider addresses.
 */
data class OneToOneMmsReactionRouteRequest(
    val providerMmsId: Long,
    val navigationThreadId: Long,
    val activeSubscriptionId: Int,
    val region: String,
)

enum class OneToOneMmsReactionDirection {
    INCOMING,
    OUTGOING,
}

/**
 * Immutable provider proof for one text-only, one-to-one MMS target.
 *
 * [OneToOneMmsReactionRouteResolver.reverify] must be called immediately before provider staging
 * and again before accepting a send callback. Reverification reloads the MMS, thread, address
 * headers, parts, canonical address, and active subscription. [participantFingerprint] binds the
 * canonical remote participant. [bodyFingerprint] binds the exact ordered text-only part tree as
 * well as [targetBody], so part replacement or media insertion invalidates the proof.
 */
data class OneToOneMmsReactionRoute(
    val providerMmsId: Long,
    val threadId: Long,
    val subscriptionId: Int,
    val region: String,
    val direction: OneToOneMmsReactionDirection,
    val remoteRecipientE164: String,
    val targetBody: String,
    val participantFingerprint: String,
    val bodyFingerprint: String,
) {
    fun requestForReverification(): OneToOneMmsReactionRouteRequest =
        OneToOneMmsReactionRouteRequest(
            providerMmsId = providerMmsId,
            navigationThreadId = threadId,
            activeSubscriptionId = subscriptionId,
            region = region,
        )
}

enum class OneToOneMmsReactionRouteRejection {
    INVALID_REQUEST,
    TARGET_MISSING_OR_AMBIGUOUS,
    TARGET_DIRECTION_UNSUPPORTED,
    STALE_NAVIGATION,
    SUBSCRIPTION_MISMATCH,
    SUBSCRIPTION_NOT_ACTIVE_OR_AMBIGUOUS,
    SUBSCRIPTION_REGION_MISSING_OR_UNSUPPORTED,
    REGION_MISMATCH,
    SELF_ADDRESS_MISSING_OR_UNSUPPORTED,
    THREAD_MISSING_OR_AMBIGUOUS,
    RECIPIENT_IDS_MISSING_OR_INVALID,
    NOT_ONE_TO_ONE,
    CANONICAL_ADDRESS_MISSING_OR_AMBIGUOUS,
    UNSUPPORTED_PARTICIPANT,
    DUPLICATE_PARTICIPANT,
    SELF_RECIPIENT,
    HEADER_WITNESS_MISSING_OR_AMBIGUOUS,
    HEADER_SELF_MISMATCH,
    HEADER_PARTICIPANT_MISMATCH,
    PARTS_MISSING_OR_AMBIGUOUS,
    NON_TEXT_PART,
    BODY_MISSING_OR_UNSUPPORTED,
    ROUTE_CHANGED,
    PROVIDER_UNAVAILABLE,
}

sealed class OneToOneMmsReactionRouteResult {
    data class Resolved(
        val route: OneToOneMmsReactionRoute,
    ) : OneToOneMmsReactionRouteResult()

    data class Rejected(
        val reason: OneToOneMmsReactionRouteRejection,
    ) : OneToOneMmsReactionRouteResult()
}

interface OneToOneMmsReactionRouteResolver {
    fun resolve(request: OneToOneMmsReactionRouteRequest): OneToOneMmsReactionRouteResult

    /** Reloads every provider witness and accepts it only when the immutable proof is unchanged. */
    fun reverify(route: OneToOneMmsReactionRoute): OneToOneMmsReactionRouteResult {
        val current = resolve(route.requestForReverification())
        return when {
            current !is OneToOneMmsReactionRouteResult.Resolved -> current
            current.route == route -> current
            else -> OneToOneMmsReactionRouteResult.Rejected(
                OneToOneMmsReactionRouteRejection.ROUTE_CHANGED
            )
        }
    }
}
