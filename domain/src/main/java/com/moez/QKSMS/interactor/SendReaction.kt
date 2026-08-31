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
package dev.octoshrimpy.quik.interactor

import dev.octoshrimpy.quik.repository.MessageRepository
import dev.octoshrimpy.quik.repository.ReactionAttemptReservation
import dev.octoshrimpy.quik.repository.SendReactionRequest
import dev.octoshrimpy.quik.repository.SendReactionResult
import io.reactivex.Flowable
import javax.inject.Inject

class SendReaction @Inject constructor(
    private val messageRepository: MessageRepository,
) : Interactor<SendReaction.Params>() {

    data class Params(
        val targetMessageId: Long,
        val expectedThreadId: Long,
        val expectedSubscriptionId: Int,
        val region: String,
        val operation: ReactionOperation,
        val reaction: Reaction,
        val reservation: ReactionAttemptReservation? = null,
    )

    enum class Reaction {
        HEART,
        LIKE,
        DISLIKE,
        LAUGH,
        EMPHASIS,
        QUESTION,
    }

    enum class ReactionOperation {
        ADD,
        REMOVE,
    }

    override fun buildObservable(params: Params): Flowable<SendReactionResult> =
        Flowable.fromCallable {
            messageRepository.sendReaction(
                SendReactionRequest(
                    targetMessageId = params.targetMessageId,
                    expectedThreadId = params.expectedThreadId,
                    expectedSubscriptionId = params.expectedSubscriptionId,
                    region = params.region,
                    operation = params.operation,
                    reaction = params.reaction,
                    reservation = params.reservation,
                )
            )
        }
}
