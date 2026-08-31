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

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import io.michaelrocks.libphonenumber.android.PhoneNumberUtil
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reloads one-to-one SMS routing evidence from Android for every resolution attempt. This class
 * only queries providers and subscriptions. It never stages, updates, deletes, or sends a message.
 */
@Singleton
class ProviderBackedOneToOneSmsReactionRouteResolver @Inject constructor(
    context: Context,
) : OneToOneSmsReactionRouteResolver {

    private val resolution = OneToOneSmsReactionRouteResolution(
        AndroidOneToOneSmsReactionRouteProvider(context.applicationContext),
        LibPhoneOneToOneSmsCanonicalizer(context.applicationContext),
    )

    override fun resolve(
        request: OneToOneSmsReactionRouteRequest,
    ): OneToOneSmsReactionRouteResult = resolution.resolve(request)

    override fun reverify(
        route: OneToOneSmsReactionRoute,
    ): OneToOneSmsReactionRouteResult = resolution.reverify(route)
}

private class AndroidOneToOneSmsReactionRouteProvider(
    private val context: Context,
) : OneToOneSmsReactionRouteProvider {

    private val contentResolver: ContentResolver = context.contentResolver
    private val subscriptionManager: SubscriptionManager = SubscriptionManager.from(context)

    override fun loadTarget(providerSmsId: Long): List<ProviderSmsTarget> {
        val uri = ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, providerSmsId)
        return contentResolver.query(
            uri,
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.THREAD_ID,
                Telephony.Sms.SUBSCRIPTION_ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.TYPE,
            ),
            null,
            null,
            null,
        ).readOneToOneSmsRows { cursor ->
            ProviderSmsTarget(
                id = cursor.requiredOneToOneSmsLong(Telephony.Sms._ID),
                threadId = cursor.requiredOneToOneSmsLong(Telephony.Sms.THREAD_ID),
                subscriptionId = cursor.requiredOneToOneSmsInt(Telephony.Sms.SUBSCRIPTION_ID),
                address = cursor.optionalOneToOneSmsString(Telephony.Sms.ADDRESS),
                body = cursor.optionalOneToOneSmsString(Telephony.Sms.BODY),
                direction = when (cursor.requiredOneToOneSmsInt(Telephony.Sms.TYPE)) {
                    Telephony.Sms.MESSAGE_TYPE_INBOX -> ProviderSmsDirection.INCOMING
                    Telephony.Sms.MESSAGE_TYPE_SENT -> ProviderSmsDirection.OUTGOING
                    else -> ProviderSmsDirection.UNSUPPORTED
                },
            )
        }
    }

    override fun loadThread(threadId: Long): List<ProviderSmsThread> = contentResolver.query(
        SIMPLE_CONVERSATIONS_URI,
        arrayOf(Telephony.Threads._ID, Telephony.Threads.RECIPIENT_IDS),
        "${Telephony.Threads._ID} = ?",
        arrayOf(threadId.toString()),
        null,
    ).readOneToOneSmsRows { cursor ->
        ProviderSmsThread(
            id = cursor.requiredOneToOneSmsLong(Telephony.Threads._ID),
            recipientIds = cursor.optionalOneToOneSmsString(Telephony.Threads.RECIPIENT_IDS),
        )
    }

    override fun loadCanonicalAddresses(
        recipientIds: List<Long>,
    ): List<ProviderSmsCanonicalAddress> {
        check(recipientIds.size == 1) { "One-to-one resolution requires one recipient ID" }
        return contentResolver.query(
            CANONICAL_ADDRESSES_URI,
            arrayOf(
                Telephony.CanonicalAddressesColumns._ID,
                Telephony.CanonicalAddressesColumns.ADDRESS,
            ),
            "${Telephony.CanonicalAddressesColumns._ID} = ?",
            arrayOf(recipientIds.single().toString()),
            null,
        ).readOneToOneSmsRows { cursor ->
            ProviderSmsCanonicalAddress(
                id = cursor.requiredOneToOneSmsLong(Telephony.CanonicalAddressesColumns._ID),
                address = cursor.optionalOneToOneSmsString(
                    Telephony.CanonicalAddressesColumns.ADDRESS
                ),
            )
        }
    }

    @Suppress("DEPRECATION")
    override fun loadActiveSubscriptions(): List<ProviderSmsSubscription> {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_STATE,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }

        return try {
            subscriptionManager.activeSubscriptionInfoList.orEmpty().map { subscription ->
                ProviderSmsSubscription(
                    id = subscription.subscriptionId,
                    selfAddress = subscription.number,
                    countryIso = subscription.countryIso,
                )
            }
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    private companion object {
        val SIMPLE_CONVERSATIONS_URI: Uri = Uri.parse("content://mms-sms/conversations?simple=true")
        val CANONICAL_ADDRESSES_URI: Uri = Uri.parse("content://mms-sms/canonical-addresses")
    }
}

private class LibPhoneOneToOneSmsCanonicalizer(
    context: Context,
) : OneToOneSmsPhoneCanonicalizer {

    private val phoneNumberUtil = PhoneNumberUtil.createInstance(context)

    override fun supportsRegion(region: String): Boolean =
        region.uppercase(Locale.ROOT) in phoneNumberUtil.supportedRegions

    override fun toE164(address: String, region: String): String? = try {
        val parsed = phoneNumberUtil.parse(address, region.uppercase(Locale.ROOT))
        parsed.takeIf(phoneNumberUtil::isValidNumber)
            ?.let { phoneNumberUtil.format(it, PhoneNumberUtil.PhoneNumberFormat.E164) }
            ?.takeIf { it.matches(E164_PATTERN) }
    } catch (_: Exception) {
        null
    }

    private companion object {
        val E164_PATTERN = Regex("\\+[1-9][0-9]{1,14}")
    }
}

private inline fun <T> Cursor?.readOneToOneSmsRows(mapper: (Cursor) -> T): List<T> {
    val cursor = this ?: error("Telephony provider query returned no cursor")
    return cursor.use {
        buildList {
            while (cursor.moveToNext()) {
                add(mapper(cursor))
            }
        }
    }
}

private fun Cursor.requiredOneToOneSmsLong(columnName: String): Long {
    val index = getColumnIndexOrThrow(columnName)
    check(!isNull(index)) { "Required provider column was null: $columnName" }
    return getLong(index)
}

private fun Cursor.requiredOneToOneSmsInt(columnName: String): Int {
    val index = getColumnIndexOrThrow(columnName)
    check(!isNull(index)) { "Required provider column was null: $columnName" }
    return getInt(index)
}

private fun Cursor.optionalOneToOneSmsString(columnName: String): String? {
    val index = getColumnIndexOrThrow(columnName)
    return if (isNull(index)) null else getString(index)
}
