/*
 * Copyright (C) 2026 Quik contributors
 *
 * This file is part of QUIK.
 *
 * QUIK is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package dev.octoshrimpy.quik.manager

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionBackedReactionRuntimeAuthorityTest {
    @Test
    fun `default sms role and send permission are both required`() {
        assertTrue(authority(isDefaultSms = true, hasSendSms = true).canSendReaction())
        assertFalse(authority(isDefaultSms = false, hasSendSms = true).canSendReaction())
        assertFalse(authority(isDefaultSms = true, hasSendSms = false).canSendReaction())
    }

    private fun authority(
        isDefaultSms: Boolean,
        hasSendSms: Boolean,
    ) = PermissionBackedReactionRuntimeAuthority(
        object : PermissionManager {
            override fun isDefaultSms(): Boolean = isDefaultSms
            override fun hasReadSms(): Boolean = false
            override fun hasSendSms(): Boolean = hasSendSms
            override fun hasContacts(): Boolean = false
            override fun hasNotifications(): Boolean = false
            override fun hasPhone(): Boolean = false
            override fun hasStorage(): Boolean = false
            override fun hasRecordAudio(): Boolean = false
            override fun hasExactAlarms(): Boolean = false
        }
    )
}
