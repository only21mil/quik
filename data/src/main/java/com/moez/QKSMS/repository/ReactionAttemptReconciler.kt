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

import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.model.ReactionAttempt
import io.realm.Realm

internal object ReactionAttemptReconciler {
    internal const val HANDOFF_TIMEOUT_MILLIS = 5 * 60 * 1000L

    data class ResolvedAttempt(
        val carrier: Message,
        val target: Message,
    )

    fun reconcileAll(realm: Realm, now: Long = System.currentTimeMillis()) {
        val attempts = realm.where(ReactionAttempt::class.java).findAll().toList()

        // Stable and legacy keys identify one provider row. Staging keys do not: Android only
        // exposes SMS timestamps at millisecond precision and MMS timestamps at second precision.
        attempts.filterNot { attempt -> isStaging(attempt) }.forEach { attempt ->
            reconcileResolvedAttempt(attempt, resolveCarrier(realm, attempt), now)
        }

        attempts.mapNotNull { attempt ->
            val staging = attempt.transportKey
                ?.let(ReactionTransportPolicy.StagingIdentity::decode)
                ?: return@mapNotNull null
            StagingGroup(staging, attempt.body) to attempt
        }.groupBy(keySelector = { it.first }, valueTransform = { it.second })
            .forEach { (group, groupedAttempts) ->
                reconcileStagingGroup(realm, group, groupedAttempts, now)
            }
    }

    fun reconcileCarrier(
        realm: Realm,
        carrier: Message,
        now: Long = System.currentTimeMillis(),
    ): ReactionAttempt? {
        val transportKey = ReactionTransportPolicy.ProviderIdentity.from(carrier)?.encode()
            ?: return null
        val attempt = realm.where(ReactionAttempt::class.java)
            .equalTo("transportKey", transportKey)
            .findFirst()
        if (attempt != null) {
            reconcileResolvedAttempt(attempt, carrier, now)
            return attempt
        }

        val unstableAttempts = findUnstableAttempts(realm, carrier)
        if (unstableAttempts.isEmpty()) return null
        val staging = unstableAttempts.first().transportKey
            ?.let(ReactionTransportPolicy.StagingIdentity::decode)
            ?: return null
        val group = StagingGroup(staging, carrier.getText(false))
        val matchingCarriers = findStagedMessages(realm, group)
        matchingCarriers.forEach { candidate -> candidate.isEmojiReaction = true }
        val unclaimedCarriers = matchingCarriers.filter { candidate ->
            !isClaimedByAnotherAttempt(realm, candidate)
        }
        if (unstableAttempts.size == 1 && unclaimedCarriers.size == 1) {
            val uniqueAttempt = unstableAttempts.single()
            val uniqueCarrier = unclaimedCarriers.single()
            val uniqueKey = ReactionTransportPolicy.ProviderIdentity.from(uniqueCarrier)?.encode()
                ?: return null
            uniqueAttempt.transportKey = uniqueKey
            reconcileResolvedAttempt(uniqueAttempt, uniqueCarrier, now)
            return uniqueAttempt
        }
        quarantine(unstableAttempts)
        return null
    }

    fun resolve(realm: Realm, attempt: ReactionAttempt): ResolvedAttempt? {
        val carrierIdentity = attempt.transportKey
            ?.let(ReactionTransportPolicy.ProviderIdentity::decode)
            ?: return null
        val targetIdentity = ReactionTransportPolicy.ProviderIdentity.decode(attempt.targetKey)
            ?: return null
        val carrier = findMessage(realm, carrierIdentity) ?: return null
        val target = findMessage(realm, targetIdentity) ?: return null
        return ResolvedAttempt(carrier, target)
    }

    /**
     * Bind an exact provider identity supplied by this attempt's Android callback. The callback
     * carries both the random attempt id and exact provider URI, so it can resolve a previously
     * ambiguous staging group without guessing which row belonged to which attempt.
     */
    fun bindCallbackCarrier(
        realm: Realm,
        attempt: ReactionAttempt,
        receivedIdentity: ReactionTransportPolicy.ProviderIdentity,
    ): Boolean {
        val receivedKey = receivedIdentity.encode()
        if (isClaimedByAnotherAttempt(realm, receivedKey, attempt.id)) return false
        if (attempt.transportKey == receivedKey) return resolve(realm, attempt) != null
        if (attempt.state != ReactionAttempt.State.QUARANTINED.name) return false
        val staging = attempt.transportKey
            ?.let(ReactionTransportPolicy.StagingIdentity::decode)
            ?: return false
        val carrier = findMessage(realm, receivedIdentity) ?: return false
        if (!staging.matches(carrier) || carrier.getText(false) != attempt.body) return false
        carrier.isEmojiReaction = true
        attempt.transportKey = receivedKey
        return true
    }

    private fun resolveCarrier(realm: Realm, attempt: ReactionAttempt): Message? {
        val key = attempt.transportKey ?: return null
        ReactionTransportPolicy.ProviderIdentity.decode(key)?.let { identity ->
            return findMessage(realm, identity)
        }

        val carrier = ReactionTransportPolicy.LegacyProviderIdentity.decode(key)?.let { legacy ->
            findLegacyMessage(realm, legacy)
        } ?: return null

        ReactionTransportPolicy.ProviderIdentity.from(carrier)?.encode()?.let { stableKey ->
            attempt.transportKey = stableKey
        }
        return carrier
    }

    private fun findUnstableAttempts(realm: Realm, carrier: Message): List<ReactionAttempt> =
        realm.where(ReactionAttempt::class.java)
            .beginsWith("transportKey", "stage-v1:")
            .findAll()
            .filter { attempt ->
                val staging = attempt.transportKey
                    ?.let(ReactionTransportPolicy.StagingIdentity::decode)
                    ?: return@filter false
                staging.matches(carrier) && attempt.body == carrier.getText(false)
            }

    private fun findStagedMessages(realm: Realm, group: StagingGroup): List<Message> =
        realm.where(Message::class.java)
            .equalTo("type", group.staging.type)
            .equalTo("date", group.staging.stagedAt)
            .equalTo("threadId", group.staging.threadId)
            .equalTo("subId", group.staging.subId)
            .findAll()
            .filter { message -> message.getText(false) == group.body }

    private fun isClaimedByAnotherAttempt(
        realm: Realm,
        carrier: Message,
    ): Boolean {
        val stableKey = ReactionTransportPolicy.ProviderIdentity.from(carrier)?.encode()
            ?: return true
        return isClaimedByAnotherAttempt(realm, stableKey)
    }

    private fun isClaimedByAnotherAttempt(
        realm: Realm,
        stableKey: String,
        attemptId: String? = null,
    ): Boolean = realm.where(ReactionAttempt::class.java)
        .equalTo("transportKey", stableKey)
        .findAll()
        .any { candidate -> candidate.id != attemptId }

    private fun findLegacyMessage(
        realm: Realm,
        legacy: ReactionTransportPolicy.LegacyProviderIdentity,
    ): Message? = realm.where(Message::class.java)
        .equalTo("type", legacy.type)
        .equalTo("contentId", legacy.contentId)
        .findFirst()

    private fun ReactionTransportPolicy.StagingIdentity.matches(carrier: Message): Boolean =
        carrier.type == type && carrier.date == stagedAt && carrier.threadId == threadId &&
            carrier.subId == subId

    private data class StagingGroup(
        val staging: ReactionTransportPolicy.StagingIdentity,
        val body: String,
    )

    private fun isStaging(attempt: ReactionAttempt): Boolean =
        attempt.transportKey
            ?.let(ReactionTransportPolicy.StagingIdentity::decode) != null

    private fun reconcileStagingGroup(
        realm: Realm,
        group: StagingGroup,
        attempts: List<ReactionAttempt>,
        now: Long,
    ) {
        val carriers = findStagedMessages(realm, group)
        carriers.forEach { carrier -> carrier.isEmojiReaction = true }
        val unclaimedCarriers = carriers.filter { carrier ->
            !isClaimedByAnotherAttempt(realm, carrier)
        }
        if (attempts.size == 1 && unclaimedCarriers.size == 1) {
            val attempt = attempts.single()
            val carrier = unclaimedCarriers.single()
            ReactionTransportPolicy.ProviderIdentity.from(carrier)?.encode()?.let { stableKey ->
                attempt.transportKey = stableKey
                reconcileResolvedAttempt(attempt, carrier, now)
                return
            }
        }
        if (attempts.size > 1 || unclaimedCarriers.size > 1) {
            quarantine(attempts)
        } else {
            // Absence from Realm does not prove absence from Telephony. A process can die after
            // the provider insert and before the carrier is copied into Realm. Keep the staging
            // reservation blocking until provider sync finds one exact row or a callback binds it.
            return
        }
    }

    private fun quarantine(attempts: List<ReactionAttempt>) {
        attempts.filter { attempt ->
            attempt.state !in setOf(
                ReactionAttempt.State.SENT.name,
                ReactionAttempt.State.FAILED.name,
            )
        }.forEach { attempt -> attempt.state = ReactionAttempt.State.QUARANTINED.name }
    }

    private fun reconcileResolvedAttempt(
        attempt: ReactionAttempt,
        carrier: Message?,
        now: Long,
    ) {
        carrier?.isEmojiReaction = true
        when (attempt.state) {
            ReactionAttempt.State.PREPARING.name -> attempt.state = ReactionAttempt.State.FAILED.name
            ReactionAttempt.State.HANDOFF.name -> reconcileHandoff(attempt, carrier, now)
            ReactionAttempt.State.SUBMITTED.name -> rejectChangedCarrier(attempt, carrier)
        }
    }

    fun findMessage(
        realm: Realm,
        identity: ReactionTransportPolicy.ProviderIdentity,
    ): Message? = realm.where(Message::class.java)
        .equalTo("type", identity.type)
        .equalTo("contentId", identity.contentId)
        .equalTo("threadId", identity.threadId)
        .equalTo("subId", identity.subId)
        .findFirst()

    private fun reconcileHandoff(attempt: ReactionAttempt, carrier: Message?, now: Long) {
        if (carrier != null && carrier.getText(false) != attempt.body) {
            attempt.state = ReactionAttempt.State.FAILED.name
            return
        }
        if (isHandoffExpired(attempt.handoffAt, now)) {
            attempt.state = ReactionAttempt.State.HANDOFF_FAILED.name
        }
    }

    internal fun isHandoffExpired(handoffAt: Long, now: Long): Boolean =
        handoffAt <= 0L ||
            now >= handoffAt && now - handoffAt >= HANDOFF_TIMEOUT_MILLIS

    private fun rejectChangedCarrier(attempt: ReactionAttempt, carrier: Message?) {
        if (carrier != null && carrier.getText(false) != attempt.body) {
            attempt.state = ReactionAttempt.State.FAILED.name
        }
    }
}
