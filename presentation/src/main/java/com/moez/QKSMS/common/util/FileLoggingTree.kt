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
package dev.octoshrimpy.quik.common.util

import android.content.Context
import android.util.Log
import dev.octoshrimpy.quik.util.Preferences
import io.reactivex.schedulers.Schedulers
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Based off Vipin Kumar's FileLoggingTree: https://medium.com/@vicky7230/file-logging-with-timber-4e63a1b86a66
 */
@Singleton
class FileLoggingTree @Inject constructor(
    private val prefs: Preferences,
    private val context: Context
) : Timber.DebugTree() {
    companion object {
        val TAG: String? = FileLoggingTree::class.simpleName
    }

    private var logFile: File? = null

    @Suppress("UNUSED_PARAMETER")
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (!prefs.logging.get()) return

        Schedulers.io().scheduleDirect {
            synchronized(this) {    // one thread can access file at a time
                val eventType = t?.javaClass?.simpleName
                    ?.filter(Char::isLetterOrDigit)
                    ?.take(64)
                    ?: "event"
                val logItem =
                    "${    // date/time
                        SimpleDateFormat(
                            "yyyy-MM-dd HH:mm:ss:SSS",
                            Locale.getDefault()
                        ).format(System.currentTimeMillis())
                    } ${    // priority
                        when (priority) {
                            Log.VERBOSE -> "V"
                            Log.DEBUG -> "D"
                            Log.INFO -> "I"
                            Log.WARN -> "W"
                            Log.ERROR -> "E"
                            else -> "?"
                        }
                    }/Quik: $eventType\n"

                // Keep diagnostic logs in app-private storage. A future support flow can export
                // them only after an explicit user action.
                if (logFile == null) {
                    val filename = "Quik-log-${
                        SimpleDateFormat(
                            "yyyy-MM-dd",
                            Locale.getDefault()
                        ).format(System.currentTimeMillis())
                    }.log"

                    val logDirectory = File(context.noBackupFilesDir, "logs")
                    if (logDirectory.exists() || logDirectory.mkdirs()) {
                        logFile = File(logDirectory, filename)
                    } else {
                        Log.e(TAG, "Error creating private log directory")
                    }
                }

                logFile?.let { file ->
                    try {
                        FileOutputStream(file, true).use { output ->
                            output.write(logItem.toByteArray(Charsets.UTF_8))
                        }
                    } catch (error: Exception) {
                        Log.e(TAG, "Error writing private log file", error)
                    }
                }
            }
        }
    }
}
