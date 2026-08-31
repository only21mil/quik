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
package dev.octoshrimpy.quik.common

import com.klinker.android.timberworkarounds.Timber_isLoggable
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import timber.log.Timber

class RuntimePrivacySourceContractTest {

    @Test
    fun `logcat tree is debug only and file logs stay app private`() {
        val application = source("presentation/src/main/java/com/moez/QKSMS/common/QKApplication.kt")
        val fileLogger = source("presentation/src/main/java/com/moez/QKSMS/common/util/FileLoggingTree.kt")
        val debugLoggingBlock = application.substring(
            application.indexOf("if (BuildConfig.DEBUG)"),
            application.indexOf("// configure emoji compatibility")
        )
        val persistedRecord = fileLogger.substring(
            fileLogger.indexOf("val logItem"),
            fileLogger.indexOf("// Keep diagnostic logs")
        )

        assertTrue(application.contains("if (BuildConfig.DEBUG)"))
        assertTrue(debugLoggingBlock.contains("Timber.DebugTree(), fileLoggingTree"))
        assertFalse(application.substring(application.indexOf("// configure emoji compatibility"))
            .contains("Timber.plant(fileLoggingTree)"))
        assertTrue(fileLogger.contains("context.noBackupFilesDir"))
        assertFalse(fileLogger.contains("FileUtils.Location.Downloads"))
        assertFalse(persistedRecord.contains("message"))
        assertFalse(persistedRecord.contains("tag"))
        assertFalse(persistedRecord.contains("getStackTraceString"))
        assertFalse(fileLogger.lineSequence().any { line ->
            line.contains("Log.e") && line.contains("logItem")
        })
    }

    @Test
    fun `shortcut variants use literal application ids`() {
        mapOf(
            "presentation/src/main/res/xml/shortcuts.xml" to
                "io.github.only21mil.quik.reactions",
            "presentation/src/debug/res/xml/shortcuts.xml" to
                "io.github.only21mil.quik.reactions.debug",
            "presentation/src/fdroid/res/xml/shortcuts.xml" to
                "io.github.only21mil.quik.reactions.fdroid",
        ).forEach { (path, applicationId) ->
            val shortcut = source(path)
            assertTrue(shortcut.contains("android:targetPackage=\"$applicationId\""))
            assertTrue(shortcut.contains("android:action=\"android.intent.action.MAIN\""))
            assertFalse(shortcut.contains("android:targetPackage=\"@"))
        }
    }

    @Test
    fun `system receivers require exact actions and the current sms role`() {
        val boot = source("data/src/main/java/com/moez/QKSMS/receiver/BootReceiver.kt")
        val roleChange = source("data/src/main/java/com/moez/QKSMS/receiver/DefaultSmsChangedReceiver.kt")

        assertTrue(boot.contains("intent?.action != Intent.ACTION_BOOT_COMPLETED"))
        assertTrue(boot.contains("permissionManager.isDefaultSms()"))
        assertTrue(roleChange.contains("intent.action != Telephony.Sms.Intents.ACTION_DEFAULT_SMS_PACKAGE_CHANGED"))
        assertTrue(roleChange.contains("permissionManager.isDefaultSms()"))
    }

    @Test
    fun `widget trampoline is private and requires its dedicated action`() {
        val manifest = source("presentation/src/main/AndroidManifest.xml")
        val receiver = source("data/src/main/java/com/moez/QKSMS/receiver/StartActivityFromWidgetReceiver.kt")
        val provider = source("presentation/src/main/java/com/moez/QKSMS/feature/widget/WidgetProvider.kt")
        val receiverDeclaration = manifest.substring(
            manifest.indexOf("android:name=\".receiver.StartActivityFromWidgetReceiver\""),
            manifest.indexOf("/>", manifest.indexOf("android:name=\".receiver.StartActivityFromWidgetReceiver\""))
        )

        assertTrue(receiverDeclaration.contains("android:exported=\"false\""))
        assertTrue(receiver.contains("intent.action != ACTION_START_ACTIVITY"))
        assertTrue(provider.contains(".setAction(StartActivityFromWidgetReceiver.ACTION_START_ACTIVITY)"))
    }

    @Test
    fun `reaction and sync logs omit message data and throwable details`() {
        val reactions = source("data/src/main/java/com/moez/QKSMS/repository/EmojiReactionRepositoryImpl.kt")
        val transaction = source("data/src/main/java/com/moez/QKSMS/manager/QkTransaction.kt")
        val messages = source("data/src/main/java/com/moez/QKSMS/repository/MessageRepositoryImpl.kt")
        val application = source("presentation/src/main/java/com/moez/QKSMS/common/QKApplication.kt")
        val sentReceiver = source("data/src/main/java/com/moez/QKSMS/receiver/MessageSentReceiver.kt")
        val sync = source("data/src/main/java/com/moez/QKSMS/repository/SyncRepositoryImpl.kt")
        val logLines = (transaction + messages + sentReceiver + application + sync)
            .lineSequence()
            .filter { line -> line.contains("Timber.") }
            .joinToString("\n")

        assertFalse(reactions.lineSequence().any { line ->
            line.contains("Timber.") && line.contains("originalMessageText")
        })
        listOf(
            "\$messageUri",
            "\$threadId",
            "\$messageId",
            "\$filePath",
            "\$fileName",
            "\$address",
            "\$body",
            "\$uri",
            "\${message.id}",
            "\${childMessage.id}",
            "\${row.address}",
            "\${row.body}",
        ).forEach { forbidden -> assertFalse(logLines.contains(forbidden)) }
        assertFalse(logLines.contains("Timber.e(error,"))
        assertFalse(logLines.contains("Timber.w(error,"))
        assertFalse(logLines.contains("Timber.e(result.error,"))
        assertFalse(logLines.contains("Timber.w(result.error,"))
        assertFalse(transaction.lineSequence().any { line ->
            line.contains("Timber.") && Regex("Timber\\.[a-z]+\\([a-zA-Z]+,").containsMatchIn(line)
        })
    }

    @Test
    fun `mms compatibility logging is disabled without a planted tree`() {
        val gate = source("android-smsmms/src/main/java/TimberExtensions.kt")
        val statusService = source(
            "android-smsmms/src/main/java/com/android/mms/transaction/MessageStatusService.java"
        )

        assertTrue(gate.contains("Timber.treeCount > 0"))
        assertFalse(gate.contains("= true"))
        assertFalse(statusService.contains("Log.d("))
        assertFalse(statusService.contains("Log.e("))
        assertFalse(statusService.lineSequence().any { line ->
            line.contains("Timber.") && line.contains("messageUri")
        })
    }

    @Test
    fun `mms logging gate follows the planted timber forest`() {
        Timber.uprootAll()
        try {
            assertFalse(Timber_isLoggable("Mms", 3))
            Timber.plant(object : Timber.Tree() {
                override fun log(
                    priority: Int,
                    tag: String?,
                    message: String,
                    t: Throwable?
                ) = Unit
            })
            assertTrue(Timber_isLoggable("Mms", 3))
        } finally {
            Timber.uprootAll()
        }
    }

    private fun source(relativePath: String): String {
        val root = File(requireNotNull(System.getProperty("user.dir")))
        val candidates = listOf(
            File(root, relativePath),
            File(root.parentFile, relativePath),
        )
        val file = requireNotNull(candidates.firstOrNull(File::isFile)) {
            "Could not find source $relativePath from $root"
        }
        return file.readText()
    }
}
