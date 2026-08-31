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

class OneToOneSmsReactionProviderSourceContractTest {

    @Test
    fun providerAdapterReadsStableSmsThreadCanonicalAndSubscriptionEvidence() {
        val source = providerSource()

        listOf(
            "ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, providerSmsId)",
            "Telephony.Sms._ID",
            "Telephony.Sms.THREAD_ID",
            "Telephony.Sms.SUBSCRIPTION_ID",
            "Telephony.Sms.ADDRESS",
            "Telephony.Sms.BODY",
            "Telephony.Sms.TYPE",
            "Telephony.Threads.RECIPIENT_IDS",
            "Telephony.CanonicalAddressesColumns.ADDRESS",
            "activeSubscriptionInfoList",
            "ContextCompat.checkSelfPermission(",
            "Manifest.permission.READ_PHONE_STATE",
            "PackageManager.PERMISSION_GRANTED",
            "catch (_: SecurityException)",
            "subscription.countryIso",
            "phoneNumberUtil.supportedRegions",
            "phoneNumberUtil::isValidNumber",
            "PhoneNumberUtil.PhoneNumberFormat.E164",
        ).forEach { required ->
            assertTrue("Provider adapter is missing $required", source.contains(required))
        }
    }

    @Test
    fun providerAdapterHasNoWriteSendOrDefaultLocalePath() {
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
    fun providerAdapterChecksPermissionAndHandlesRuntimeRevocationBeforeReadingSubscriptions() {
        val source = providerSource()
        val permissionCheck = source.indexOf("ContextCompat.checkSelfPermission(")
        val permissionDenied = source.indexOf("return emptyList()", permissionCheck)
        val subscriptionRead = source.indexOf("activeSubscriptionInfoList", permissionDenied)
        val revocationHandler = source.indexOf("catch (_: SecurityException)", subscriptionRead)
        val failClosedResult = source.indexOf("emptyList()", revocationHandler)

        assertTrue("Permission check must precede subscription access", permissionCheck >= 0)
        assertTrue("Permission denial must fail closed", permissionDenied > permissionCheck)
        assertTrue("Subscription access must follow the permission gate", subscriptionRead > permissionDenied)
        assertTrue("Runtime permission revocation must be caught", revocationHandler > subscriptionRead)
        assertTrue("Runtime permission revocation must fail closed", failClosedResult > revocationHandler)
    }

    private fun providerSource(): String {
        val root = File(requireNotNull(System.getProperty("user.dir")))
        val candidates = listOf(
            File(
                root,
                "src/main/java/com/moez/QKSMS/repository/" +
                    "ProviderBackedOneToOneSmsReactionRouteResolver.kt"
            ),
            File(
                root,
                "data/src/main/java/com/moez/QKSMS/repository/" +
                    "ProviderBackedOneToOneSmsReactionRouteResolver.kt"
            ),
        )
        return requireNotNull(candidates.firstOrNull(File::isFile)) {
            "Could not find one-to-one SMS provider adapter from $root"
        }.readText()
    }
}
