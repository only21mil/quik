/*
 * Copyright (C) 2026
 *
 * This file is part of QUIK.
 *
 * QUIK is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package dev.octoshrimpy.quik.model

import io.realm.RealmObject
import io.realm.annotations.Index
import io.realm.annotations.PrimaryKey
import java.util.UUID

open class ReactionAttempt : RealmObject() {
    enum class State {
        PREPARING,
        HANDOFF,
        SUBMITTED,
        SENT,
        FAILED,
        HANDOFF_FAILED,
        /** Pre-stage evidence matched more than one attempt or provider row. */
        QUARANTINED,
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }

    /** Created before the reaction is staged with the platform provider. */
    @PrimaryKey var id: String = ""

    /** Stable identity of the message being reacted to. */
    @Index var targetKey: String = ""

    /** Pre-stage quarantine identity, replaced by the stable platform carrier identity. */
    @Index var transportKey: String? = null

    @Index var threadId: Long = 0

    /** The exact text payload submitted to SMS/MMS transport. */
    var body: String = ""

    @Index var state: String = State.PREPARING.name

    @Index var createdAt: Long = 0

    /** Random process session that owns the PREPARING reservation. */
    @Index var ownerSessionId: String = ""

    /** ONE_TO_ONE_SMS, ONE_TO_ONE_MMS, or TRUE_GROUP_MMS after provider proof. */
    @Index var routeKind: String = ""

    /** INCOMING or OUTGOING as proven by the target provider row. */
    var routeDirection: String = ""

    /** SHA-256 of the provider-proved canonical remote participants. */
    var routeFingerprint: String = ""

    /** ISO region from the selected active subscription. */
    var routeRegion: String = ""

    /** Fingerprint of the exact provider target body or part tree. */
    var targetBodyFingerprint: String = ""

    /** SHA-256 of [body], checked before handoff and on callback. */
    var bodyFingerprint: String = ""

    /** Time the durable carrier was handed to the platform submission boundary. */
    @Index var handoffAt: Long = 0
}
