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
package dev.octoshrimpy.quik.feature.main

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PhonePermissionGateSourceContractTest {

    @Test
    fun `phone permission request is explicit and user visible`() {
        val activity = source(
            "presentation/src/main/java/com/moez/QKSMS/feature/main/MainActivity.kt"
        )
        val request = activity.substring(
            activity.indexOf("override fun requestPermissions()"),
            activity.indexOf("override fun clearSearch()"),
        )
        val state = source(
            "presentation/src/main/java/com/moez/QKSMS/feature/main/MainState.kt"
        )
        val strings = source("presentation/src/main/res/values/strings.xml")

        assertTrue(request.contains("Manifest.permission.READ_PHONE_STATE"))
        assertTrue(state.contains("val phonePermission: Boolean = true"))
        assertTrue(activity.contains("!state.phonePermission"))
        assertTrue(activity.contains("R.string.main_permission_phone"))
        assertTrue(strings.contains("identify the active SIM and route reactions"))
        assertTrue(strings.contains("does not request permission to place calls"))
    }

    @Test
    fun `permission state drives startup resume and retry paths`() {
        val viewModel = source(
            "presentation/src/main/java/com/moez/QKSMS/feature/main/MainViewModel.kt"
        )
        val startupGate = viewModel.substring(
            viewModel.indexOf("override fun bindView(view: MainView)"),
            viewModel.indexOf("// when unreadAtTop preference changes"),
        )
        val resumedGate = viewModel.substring(
            viewModel.indexOf("// Active-SIM reaction routing"),
            viewModel.indexOf("// If the Notifications Permission state changes"),
        )
        val retryGate = viewModel.substring(
            viewModel.indexOf("view.snackbarButtonIntent"),
        )

        assertTrue(startupGate.contains("!permissionManager.hasPhone()"))
        assertTrue(startupGate.contains("view.requestPermissions()"))
        assertTrue(resumedGate.contains(".map { permissionManager.hasPhone() }"))
        assertTrue(resumedGate.contains("copy(phonePermission = phonePermission)"))
        assertTrue(retryGate.contains("!state.phonePermission -> view.requestPermissions()"))
    }

    @Test
    fun `missing denied or revoked permission blocks both subscription providers`() {
        listOf(
            "ProviderBackedOneToOneSmsReactionRouteResolver.kt",
            "ProviderBackedGroupMmsReactionRouteResolver.kt",
        ).forEach { filename ->
            val provider = source(
                "data/src/main/java/com/moez/QKSMS/repository/$filename"
            )
            val lookup = provider.substring(
                provider.indexOf("override fun loadActiveSubscriptions"),
                provider.indexOf("private companion object"),
            )
            val permissionCheck = lookup.indexOf("ContextCompat.checkSelfPermission(")
            val deniedResult = lookup.indexOf("return emptyList()", permissionCheck)
            val subscriptionRead = lookup.indexOf("activeSubscriptionInfoList", deniedResult)
            val revoked = lookup.indexOf("catch (_: SecurityException)", subscriptionRead)
            val revokedResult = lookup.indexOf("emptyList()", revoked)

            assertTrue("$filename must check permission first", permissionCheck >= 0)
            assertTrue("$filename must fail closed when denied", deniedResult > permissionCheck)
            assertTrue(
                "$filename must read subscriptions only after grant",
                subscriptionRead > deniedResult,
            )
            assertTrue("$filename must handle runtime revocation", revoked > subscriptionRead)
            assertTrue("$filename must fail closed after revocation", revokedResult > revoked)
        }
    }

    @Test
    fun `granted permission has one explicit active subscription path`() {
        listOf(
            "ProviderBackedOneToOneSmsReactionRouteResolver.kt",
            "ProviderBackedGroupMmsReactionRouteResolver.kt",
        ).forEach { filename ->
            val provider = source(
                "data/src/main/java/com/moez/QKSMS/repository/$filename"
            )
            val lookup = provider.substring(
                provider.indexOf("override fun loadActiveSubscriptions"),
                provider.indexOf("private companion object"),
            )
            val permissionGate = lookup.indexOf("PackageManager.PERMISSION_GRANTED")
            val activeSubscriptionRead = lookup.indexOf("activeSubscriptionInfoList")

            assertTrue("$filename must name the granted state", permissionGate >= 0)
            assertTrue(
                "$filename must read active subscriptions only after the gate",
                activeSubscriptionRead > permissionGate,
            )
            assertTrue(
                "$filename must retain the provider subscription ID",
                lookup.contains("id = subscription.subscriptionId"),
            )
        }
    }

    private fun source(relativePath: String): String {
        val root = File(requireNotNull(System.getProperty("user.dir")))
        val candidates = listOf(File(root, relativePath), File(root.parentFile, relativePath))
        return requireNotNull(candidates.firstOrNull(File::isFile)) {
            "Could not find source $relativePath from $root"
        }.readText()
    }
}
