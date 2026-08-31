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

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupMmsReactionProviderSourceContractTest {

    @Test
    fun adapterReadsTargetHeadersPartsThreadParticipantsAndProviderRegion() {
        val source = providerSource()

        listOf(
            "ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, mmsId)",
            "Telephony.Mms._ID",
            "Telephony.Mms.THREAD_ID",
            "Telephony.Mms.SUBSCRIPTION_ID",
            "Telephony.Mms.MESSAGE_BOX",
            "Telephony.Mms.Addr.ADDRESS",
            "Telephony.Mms.Addr.TYPE",
            "Telephony.Mms.Part.MSG_ID",
            "Telephony.Mms.Part.SEQ",
            "Telephony.Mms.Part.CONTENT_TYPE",
            "Telephony.Mms.Part.TEXT",
            "Telephony.Mms.Part._DATA",
            "Telephony.Threads.RECIPIENT_IDS",
            "Telephony.CanonicalAddressesColumns.ADDRESS",
            "activeSubscriptionInfoList",
            "subscription.countryIso",
            "phoneNumberUtil.supportedRegions",
            "phoneNumberUtil::isValidNumber",
            "PhoneNumberUtil.PhoneNumberFormat.E164",
        ).forEach { required ->
            assertTrue("Provider adapter is missing $required", source.contains(required))
        }
    }

    @Test
    fun adapterIsReadOnlyAndHasNoLocaleOrSendFallback() {
        val source = providerSource()

        listOf(
            ".insert(",
            ".update(",
            ".delete(",
            "SmsManager",
            "sendTextMessage",
            "sendMultipartTextMessage",
            "Locale.getDefault",
        ).forEach { forbidden ->
            assertFalse("Read-only provider adapter contains $forbidden", source.contains(forbidden))
        }
    }

    @Test
    fun subscriptionReadFailsClosedAroundPermissionRevocation() {
        val source = providerSource()
        val permissionCheck = source.indexOf("ContextCompat.checkSelfPermission(")
        val permissionDenied = source.indexOf("return emptyList()", permissionCheck)
        val subscriptionRead = source.indexOf("activeSubscriptionInfoList", permissionDenied)
        val revocationHandler = source.indexOf("catch (_: SecurityException)", subscriptionRead)
        val failClosedResult = source.indexOf("emptyList()", revocationHandler)

        assertTrue(permissionCheck >= 0)
        assertTrue(permissionDenied > permissionCheck)
        assertTrue(subscriptionRead > permissionDenied)
        assertTrue(revocationHandler > subscriptionRead)
        assertTrue(failClosedResult > revocationHandler)
    }

    private fun providerSource(): String {
        val root = File(requireNotNull(System.getProperty("user.dir")))
        val candidates = listOf(
            File(
                root,
                "src/main/java/com/moez/QKSMS/repository/" +
                    "ProviderBackedGroupMmsReactionRouteResolver.kt"
            ),
            File(
                root,
                "data/src/main/java/com/moez/QKSMS/repository/" +
                    "ProviderBackedGroupMmsReactionRouteResolver.kt"
            ),
        )
        return requireNotNull(candidates.firstOrNull(File::isFile)) {
            "Could not find group MMS provider adapter from $root"
        }.readText()
    }
}
