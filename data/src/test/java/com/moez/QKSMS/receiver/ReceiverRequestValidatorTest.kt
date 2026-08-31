/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.octoshrimpy.quik.receiver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverRequestValidatorTest {

    private val speakAction = "dev.octoshrimpy.quik.action.ACTION_SPEAK_MESSAGES"
    private val providerAction = "android.provider.action.EXTERNAL_PROVIDER_CHANGE"
    private val bootAction = "android.intent.action.BOOT_COMPLETED"
    private val defaultSmsAction = "android.provider.action.DEFAULT_SMS_PACKAGE_CHANGED"

    @Test
    fun `privileged system request requires exact action and current default sms role`() {
        assertTrue(ReceiverRequestValidator.isPrivilegedSystemRequest(
            bootAction, bootAction, true
        ))
        assertFalse(ReceiverRequestValidator.isPrivilegedSystemRequest(
            "other", bootAction, true
        ))
        assertFalse(ReceiverRequestValidator.isPrivilegedSystemRequest(
            bootAction, bootAction, false
        ))
    }

    @Test
    fun `default sms change requires exact action role extra and current role`() {
        assertTrue(ReceiverRequestValidator.isDefaultSmsChangeRequest(
            defaultSmsAction, defaultSmsAction, true, true
        ))
        assertFalse(ReceiverRequestValidator.isDefaultSmsChangeRequest(
            "other", defaultSmsAction, true, true
        ))
        assertFalse(ReceiverRequestValidator.isDefaultSmsChangeRequest(
            defaultSmsAction, defaultSmsAction, false, true
        ))
        assertFalse(ReceiverRequestValidator.isDefaultSmsChangeRequest(
            defaultSmsAction, defaultSmsAction, true, false
        ))
    }

    @Test
    fun `speak request accepts conversation and aggregate thread ids`() {
        assertTrue(ReceiverRequestValidator.isSpeakRequest(speakAction, speakAction, true, 42L))
        assertTrue(ReceiverRequestValidator.isSpeakRequest(speakAction, speakAction, true, -1L))
        assertTrue(ReceiverRequestValidator.isSpeakRequest(speakAction, speakAction, true, -2L))
    }

    @Test
    fun `speak request rejects missing or malformed input`() {
        assertFalse(ReceiverRequestValidator.isSpeakRequest(null, speakAction, true, 42L))
        assertFalse(ReceiverRequestValidator.isSpeakRequest("other", speakAction, true, 42L))
        assertFalse(ReceiverRequestValidator.isSpeakRequest(speakAction, speakAction, false, 42L))
        assertFalse(ReceiverRequestValidator.isSpeakRequest(speakAction, speakAction, true, 0L))
        assertFalse(ReceiverRequestValidator.isSpeakRequest(speakAction, speakAction, true, -3L))
    }

    @Test
    fun `provider change accepts positive sms and mms message ids`() {
        assertTrue(ReceiverRequestValidator.isProviderChangeRequest(
            providerAction, providerAction, "content", "sms", "42"
        ))
        assertTrue(ReceiverRequestValidator.isProviderChangeRequest(
            providerAction, providerAction, "content", "mms", "7"
        ))
    }

    @Test
    fun `provider change rejects untrusted action and uri parts`() {
        assertFalse(ReceiverRequestValidator.isProviderChangeRequest(
            "other", providerAction, "content", "sms", "42"
        ))
        assertFalse(ReceiverRequestValidator.isProviderChangeRequest(
            providerAction, providerAction, "file", "sms", "42"
        ))
        assertFalse(ReceiverRequestValidator.isProviderChangeRequest(
            providerAction, providerAction, "content", "contacts", "42"
        ))
        assertFalse(ReceiverRequestValidator.isProviderChangeRequest(
            providerAction, providerAction, "content", "sms", null
        ))
        assertFalse(ReceiverRequestValidator.isProviderChangeRequest(
            providerAction, providerAction, "content", "sms", "not-an-id"
        ))
        assertFalse(ReceiverRequestValidator.isProviderChangeRequest(
            providerAction, providerAction, "content", "sms", "0"
        ))
        assertFalse(ReceiverRequestValidator.isProviderChangeRequest(
            providerAction, providerAction, "content", "sms", "-1"
        ))
    }
}
