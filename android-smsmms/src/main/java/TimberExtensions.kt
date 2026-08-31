package com.klinker.android.timberworkarounds

import timber.log.Timber

@Suppress("UNUSED_PARAMETER", "FunctionName")
fun Timber_isLoggable(tag: String, level: Int): Boolean = Timber.treeCount > 0
