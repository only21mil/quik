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

import dev.octoshrimpy.quik.interactor.SendReaction

data class SendReactionRequest(
    val targetMessageId: Long,
    val expectedThreadId: Long,
    val expectedSubscriptionId: Int,
    val region: String,
    val operation: SendReaction.ReactionOperation,
    val reaction: SendReaction.Reaction,
    val reservation: ReactionAttemptReservation? = null,
)

sealed class SendReactionResult {
    data class Submitted(val attemptId: String) : SendReactionResult()

    data class Rejected(val reason: Rejection) : SendReactionResult()

    enum class Rejection {
        INVALID_REQUEST,
        RUNTIME_AUTHORITY_REVOKED,
        TARGET_CHANGED,
        ROUTE_UNAVAILABLE,
        FAN_OUT_FORBIDDEN,
        CODEC_REJECTED,
        NOT_ONE_SMS_SEGMENT,
        PROVIDER_STAGING_FAILED,
        REALM_SYNC_FAILED,
        PLATFORM_SUBMISSION_FAILED,
    }
}

enum class ReactionCallbackResult {
    SENT,
    FAILED,
    ALREADY_TERMINAL,
    NOT_FOUND,
    CORRELATION_MISMATCH,
    RUNTIME_AUTHORITY_REVOKED,
}
