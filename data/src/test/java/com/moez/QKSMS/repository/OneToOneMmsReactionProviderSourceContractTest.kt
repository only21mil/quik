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

class OneToOneMmsReactionProviderSourceContractTest {

    @Test
    fun adapterReadsOnlyApi23CompatibleStableProviderWitnesses() {
        val source = providerSource()

        listOf(
            "ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, providerMmsId)",
            "Telephony.Mms._ID",
            "Telephony.Mms.THREAD_ID",
            "Telephony.Mms.SUBSCRIPTION_ID",
            "Telephony.Mms.MESSAGE_BOX",
            "Telephony.Threads.RECIPIENT_IDS",
            "Telephony.CanonicalAddressesColumns.ADDRESS",
            "Telephony.Mms.Addr.ADDRESS",
            "Telephony.Mms.Part.MSG_ID",
            "Telephony.Mms.Part.CONTENT_TYPE",
            "Telephony.Mms.Part.TEXT",
            "Telephony.Mms.Part._DATA",
            "activeSubscriptionInfoList",
            "subscription.countryIso",
            "Manifest.permission.READ_PHONE_STATE",
            "ContextCompat.checkSelfPermission(",
            "catch (_: SecurityException)",
            "phoneNumberUtil.supportedRegions",
            "PhoneNumberUtil.PhoneNumberFormat.E164",
        ).forEach { required ->
            assertTrue("Provider adapter is missing $required", source.contains(required))
        }

        listOf("Build.VERSION_CODES.N", "Build.VERSION_CODES.O", "Build.VERSION_CODES.P")
            .forEach { postApi23 ->
                assertFalse("Adapter introduced a post-API-23 dependency: $postApi23", source.contains(postApi23))
            }
    }

    @Test
    fun adapterHasNoRealmRecipientWriteSendOrDefaultLocalePath() {
        val source = providerSource()

        listOf(
            "Conversation.recipients",
            "Realm",
            ".insert(",
            ".update(",
            ".delete(",
            "SmsManager",
            "sendTextMessage",
            "sendMultimediaMessage",
            "Locale.getDefault",
        ).forEach { forbidden ->
            assertFalse("Read-only provider adapter contains $forbidden", source.contains(forbidden))
        }
    }

    @Test
    fun permissionDenialAndRuntimeRevocationFailClosedBeforeSubscriptionUse() {
        val source = providerSource()
        val permissionCheck = source.indexOf("ContextCompat.checkSelfPermission(")
        val denied = source.indexOf("return emptyList()", permissionCheck)
        val subscriptionRead = source.indexOf("activeSubscriptionInfoList", denied)
        val revocation = source.indexOf("catch (_: SecurityException)", subscriptionRead)
        val failClosed = source.indexOf("emptyList()", revocation)

        assertTrue(permissionCheck >= 0)
        assertTrue(denied > permissionCheck)
        assertTrue(subscriptionRead > denied)
        assertTrue(revocation > subscriptionRead)
        assertTrue(failClosed > revocation)
    }

    private fun providerSource(): String {
        val root = File(requireNotNull(System.getProperty("user.dir")))
        val candidates = listOf(
            File(
                root,
                "src/main/java/com/moez/QKSMS/repository/" +
                    "ProviderBackedOneToOneMmsReactionRouteResolver.kt"
            ),
            File(
                root,
                "data/src/main/java/com/moez/QKSMS/repository/" +
                    "ProviderBackedOneToOneMmsReactionRouteResolver.kt"
            ),
        )
        return requireNotNull(candidates.firstOrNull(File::isFile)) {
            "Could not find one-to-one MMS provider adapter from $root"
        }.readText()
    }
}
