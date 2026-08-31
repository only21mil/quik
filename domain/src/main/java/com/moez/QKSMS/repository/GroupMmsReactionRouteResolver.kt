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
 * Identifies an already-persisted MMS and the compose state from which it was selected.
 *
 * [region] must be an explicit ISO 3166-1 alpha-2 region. It is deliberately not inferred from
 * the process locale because doing so could silently change the set of reaction recipients.
 */
data class GroupMmsReactionRouteRequest(
    val targetMmsId: Long,
    val navigationThreadId: Long,
    val activeSubscriptionId: Int,
    val region: String,
)

enum class GroupMmsReactionDirection {
    INCOMING,
    OUTGOING,
}

/**
 * Immutable provider proof for one text-only true-group MMS target.
 *
 * [region] is the selected active subscription's provider country, not a process-locale guess.
 * [participantFingerprint] binds the self and remote participant set, while [bodyFingerprint]
 * binds the exact ordered part tree and [targetBody]. Call [GroupMmsReactionRouteResolver.reverify]
 * immediately before staging and again before accepting a send callback.
 */
data class GroupMmsReactionRoute(
    val targetMmsId: Long,
    val threadId: Long,
    val subscriptionId: Int,
    val region: String,
    val direction: GroupMmsReactionDirection,
    val selfParticipantKey: String,
    val remoteParticipantKeys: List<String>,
    val targetBody: String,
    val participantFingerprint: String,
    val bodyFingerprint: String,
) {
    fun requestForReverification(): GroupMmsReactionRouteRequest =
        GroupMmsReactionRouteRequest(
            targetMmsId = targetMmsId,
            navigationThreadId = threadId,
            activeSubscriptionId = subscriptionId,
            region = region,
        )
}

enum class GroupMmsReactionRouteRejection {
    INVALID_REQUEST,
    TARGET_MISSING_OR_AMBIGUOUS,
    STALE_NAVIGATION,
    SUBSCRIPTION_MISMATCH,
    SUBSCRIPTION_NOT_ACTIVE,
    SUBSCRIPTION_REGION_MISSING_OR_UNSUPPORTED,
    REGION_MISMATCH,
    SELF_ADDRESS_MISSING_OR_UNSUPPORTED,
    THREAD_MISSING_OR_AMBIGUOUS,
    RECIPIENT_IDS_MISSING_OR_INVALID,
    CANONICAL_ADDRESS_MISSING_OR_AMBIGUOUS,
    DUPLICATE_PARTICIPANT,
    UNSUPPORTED_PARTICIPANT,
    NOT_TRUE_GROUP,
    HEADER_WITNESS_MISSING_OR_AMBIGUOUS,
    HEADER_DIRECTION_UNSUPPORTED,
    HEADER_SELF_MISMATCH,
    HEADER_PARTICIPANT_MISMATCH,
    PARTS_MISSING_OR_AMBIGUOUS,
    NON_TEXT_PART,
    BODY_MISSING_OR_UNSUPPORTED,
    ROUTE_CHANGED,
    PROVIDER_UNAVAILABLE,
}

sealed class GroupMmsReactionRouteResult {
    data class Resolved(val route: GroupMmsReactionRoute) : GroupMmsReactionRouteResult()

    data class Rejected(
        val reason: GroupMmsReactionRouteRejection,
    ) : GroupMmsReactionRouteResult()
}

interface GroupMmsReactionRouteResolver {
    fun resolve(request: GroupMmsReactionRouteRequest): GroupMmsReactionRouteResult

    /** Reloads every provider witness and accepts it only when the complete proof is unchanged. */
    fun reverify(route: GroupMmsReactionRoute): GroupMmsReactionRouteResult {
        val current = resolve(route.requestForReverification())
        return when {
            current !is GroupMmsReactionRouteResult.Resolved -> current
            current.route == route -> current
            else -> GroupMmsReactionRouteResult.Rejected(
                GroupMmsReactionRouteRejection.ROUTE_CHANGED
            )
        }
    }
}
