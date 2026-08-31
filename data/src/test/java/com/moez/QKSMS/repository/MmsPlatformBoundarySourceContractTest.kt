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

class MmsPlatformBoundarySourceContractTest {
    @Test
    fun `application delegates MMS send and download to SmsManager`() {
        val transaction = file("data/src/main/java/com/moez/QKSMS/manager/QkTransaction.kt")
        val sender = transaction.substring(
            transaction.indexOf("private fun sendMmsMessage"),
            transaction.indexOf("private fun buildPdu"),
        )
        assertTrue(sender.contains("sendMultimediaMessage"))
        assertFalse(sender.contains("MmsNetworkManager"))
        assertFalse(sender.contains("MmsHttpClient"))

        val receiver = file(
            "android-smsmms/src/main/java/com/android/mms/transaction/PushReceiver.java"
        )
        assertTrue(receiver.contains("DownloadManager.getInstance().downloadMultimediaMessage"))
        listOf(
            "MmsRequestManager",
            "MmsNetworkManager",
            "system_mms_sending",
            "setMobileDataEnabled",
            "setWifiEnabled",
        ).forEach { forbidden ->
            assertFalse("PushReceiver contains $forbidden", receiver.contains(forbidden))
        }
    }

    @Test
    fun `application has no legacy network authority or service entry point`() {
        val manifest = file("presentation/src/main/AndroidManifest.xml")
        listOf(
            "android.permission.INTERNET",
            "android.permission.CHANGE_NETWORK_STATE",
            "android:networkSecurityConfig",
            "android:usesCleartextTraffic",
            "com.android.mms.transaction.TransactionService",
        ).forEach { forbidden ->
            assertFalse("Manifest contains $forbidden", manifest.contains(forbidden))
        }
    }

    @Test
    fun `legacy sender is excluded and remaining network entry points are package private`() {
        val build = file("android-smsmms/build.gradle")
        assertTrue(build.contains("'com/android/mms/service_alt/SendRequest.java'"))
        assertTrue(build.contains("'com/android/mms/service_alt/MmsRequestManager.java'"))

        val request = file("android-smsmms/src/main/java/com/android/mms/service_alt/MmsRequest.java")
        assertTrue(request.contains("abstract class MmsRequest"))
        assertTrue(request.contains("void execute(Context context, MmsNetworkManager networkManager)"))
        assertFalse(request.contains("public void execute("))

        val download = file(
            "android-smsmms/src/main/java/com/android/mms/service_alt/DownloadRequest.java"
        )
        assertTrue(download.contains("DownloadRequest(RequestManager manager"))
        assertFalse(download.contains("public DownloadRequest("))
        assertTrue(download.contains("public static Uri persist("))
    }

    @Test
    fun `production code outside service alt cannot reference custom transport`() {
        val root = root()
        val serviceAlt = File(
            root,
            "android-smsmms/src/main/java/com/android/mms/service_alt",
        ).canonicalFile
        val productionRoots = listOf(
            "android-smsmms/src/main/java",
            "common/src/main/java",
            "data/src/main/java",
            "domain/src/main/java",
            "presentation/src/main/java",
        ).map { File(root, it) }
        val forbidden = listOf(
            "MmsNetworkManager",
            "MmsHttpClient",
            "MmsRequestManager",
            "SendRequest",
        )

        productionRoots.asSequence()
            .flatMap { it.walkTopDown().asSequence() }
            .filter(File::isFile)
            .filter { it.extension == "java" || it.extension == "kt" }
            .filterNot { it.canonicalFile.toPath().startsWith(serviceAlt.toPath()) }
            .forEach { source ->
                val text = source.readText()
                forbidden.forEach { symbol ->
                    assertFalse("${source.relativeTo(root)} references $symbol", text.contains(symbol))
                }
            }
    }

    private fun file(relativePath: String): String {
        return File(root(), relativePath).readText()
    }

    private fun root(): File {
        var root = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (!File(root, "settings.gradle").isFile) {
            root = requireNotNull(root.parentFile) { "Could not find repository root" }
        }
        return root
    }
}
