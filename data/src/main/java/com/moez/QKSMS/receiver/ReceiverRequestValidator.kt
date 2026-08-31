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

internal object ReceiverRequestValidator {

    private val messageProviderAuthorities = setOf("sms", "mms")

    fun isPrivilegedSystemRequest(
        action: String?,
        expectedAction: String,
        isDefaultSms: Boolean
    ): Boolean = action == expectedAction && isDefaultSms

    fun isDefaultSmsChangeRequest(
        action: String?,
        expectedAction: String,
        becameDefaultSms: Boolean,
        isDefaultSms: Boolean
    ): Boolean = action == expectedAction && becameDefaultSms && isDefaultSms

    fun isSpeakRequest(
        action: String?,
        expectedAction: String,
        hasThreadId: Boolean,
        threadId: Long
    ): Boolean {
        return action == expectedAction &&
            hasThreadId &&
            (threadId > 0 || threadId == -1L || threadId == -2L)
    }

    fun isProviderChangeRequest(
        action: String?,
        expectedAction: String,
        scheme: String?,
        authority: String?,
        lastPathSegment: String?
    ): Boolean {
        if (action != expectedAction || scheme != "content") return false
        if (authority !in messageProviderAuthorities) return false

        return lastPathSegment?.toLongOrNull()?.let { it > 0 } == true
    }
}
