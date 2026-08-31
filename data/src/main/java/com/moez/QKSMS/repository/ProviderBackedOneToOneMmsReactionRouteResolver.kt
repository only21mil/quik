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
import com.google.android.mms.pdu_alt.PduHeaders
import io.michaelrocks.libphonenumber.android.PhoneNumberUtil
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reloads one-to-one MMS routing evidence from Android on every resolution attempt. This adapter
 * is read-only: it never stages, updates, deletes, or sends a message.
 */
@Singleton
class ProviderBackedOneToOneMmsReactionRouteResolver @Inject constructor(
    context: Context,
) : OneToOneMmsReactionRouteResolver {

    private val resolution = OneToOneMmsReactionRouteResolution(
        AndroidOneToOneMmsReactionRouteProvider(context.applicationContext),
        LibPhoneOneToOneMmsCanonicalizer(context.applicationContext),
    )

    override fun resolve(
        request: OneToOneMmsReactionRouteRequest,
    ): OneToOneMmsReactionRouteResult = resolution.resolve(request)
}

private class AndroidOneToOneMmsReactionRouteProvider(
    private val context: Context,
) : OneToOneMmsReactionRouteProvider {

    private val contentResolver: ContentResolver = context.contentResolver
    private val subscriptionManager: SubscriptionManager = SubscriptionManager.from(context)

    override fun loadTarget(providerMmsId: Long): List<ProviderMmsTarget> {
        val uri = ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, providerMmsId)
        return contentResolver.query(
            uri,
            arrayOf(
                Telephony.Mms._ID,
                Telephony.Mms.THREAD_ID,
                Telephony.Mms.SUBSCRIPTION_ID,
                Telephony.Mms.MESSAGE_BOX,
            ),
            null,
            null,
            null,
        ).readOneToOneMmsRows { cursor ->
            ProviderMmsTarget(
                id = cursor.requiredOneToOneMmsLong(Telephony.Mms._ID),
                threadId = cursor.requiredOneToOneMmsLong(Telephony.Mms.THREAD_ID),
                subscriptionId = cursor.requiredOneToOneMmsInt(Telephony.Mms.SUBSCRIPTION_ID),
                direction = when (cursor.requiredOneToOneMmsInt(Telephony.Mms.MESSAGE_BOX)) {
                    Telephony.Mms.MESSAGE_BOX_INBOX -> ProviderMmsDirection.INCOMING
                    Telephony.Mms.MESSAGE_BOX_SENT -> ProviderMmsDirection.OUTGOING
                    else -> ProviderMmsDirection.UNSUPPORTED
                },
            )
        }
    }

    override fun loadThread(threadId: Long): List<ProviderThread> = contentResolver.query(
        SIMPLE_CONVERSATIONS_URI,
        arrayOf(Telephony.Threads._ID, Telephony.Threads.RECIPIENT_IDS),
        "${Telephony.Threads._ID} = ?",
        arrayOf(threadId.toString()),
        null,
    ).readOneToOneMmsRows { cursor ->
        ProviderThread(
            id = cursor.requiredOneToOneMmsLong(Telephony.Threads._ID),
            recipientIds = cursor.optionalOneToOneMmsString(Telephony.Threads.RECIPIENT_IDS),
        )
    }

    override fun loadCanonicalAddresses(
        recipientIds: List<Long>,
    ): List<ProviderCanonicalAddress> {
        check(recipientIds.size == 1) { "One-to-one MMS resolution requires one recipient ID" }
        return contentResolver.query(
            CANONICAL_ADDRESSES_URI,
            arrayOf(
                Telephony.CanonicalAddressesColumns._ID,
                Telephony.CanonicalAddressesColumns.ADDRESS,
            ),
            "${Telephony.CanonicalAddressesColumns._ID} = ?",
            arrayOf(recipientIds.single().toString()),
            null,
        ).readOneToOneMmsRows { cursor ->
            ProviderCanonicalAddress(
                id = cursor.requiredOneToOneMmsLong(Telephony.CanonicalAddressesColumns._ID),
                address = cursor.optionalOneToOneMmsString(
                    Telephony.CanonicalAddressesColumns.ADDRESS
                ),
            )
        }
    }

    override fun loadMmsAddresses(providerMmsId: Long): List<ProviderMmsAddress> {
        val uri = Telephony.Mms.CONTENT_URI.buildUpon()
            .appendPath(providerMmsId.toString())
            .appendPath("addr")
            .build()
        return contentResolver.query(
            uri,
            arrayOf(Telephony.Mms.Addr.ADDRESS, Telephony.Mms.Addr.TYPE),
            null,
            null,
            null,
        ).readOneToOneMmsRows { cursor ->
            val type = when (cursor.requiredOneToOneMmsInt(Telephony.Mms.Addr.TYPE)) {
                PduHeaders.FROM -> ProviderMmsAddressType.FROM
                PduHeaders.TO -> ProviderMmsAddressType.TO
                PduHeaders.CC -> ProviderMmsAddressType.CC
                else -> error("Telephony provider returned an unrequested MMS address type")
            }
            ProviderMmsAddress(
                type = type,
                address = cursor.optionalOneToOneMmsString(Telephony.Mms.Addr.ADDRESS),
            )
        }
    }

    override fun loadMmsParts(providerMmsId: Long): List<ProviderOneToOneMmsPart> =
        contentResolver.query(
            MMS_PARTS_URI,
            arrayOf(
                Telephony.Mms.Part._ID,
                Telephony.Mms.Part.MSG_ID,
                Telephony.Mms.Part.SEQ,
                Telephony.Mms.Part.CONTENT_TYPE,
                Telephony.Mms.Part.TEXT,
                Telephony.Mms.Part._DATA,
            ),
            "${Telephony.Mms.Part.MSG_ID} = ?",
            arrayOf(providerMmsId.toString()),
            null,
        ).readOneToOneMmsRows { cursor ->
            ProviderOneToOneMmsPart(
                id = cursor.requiredOneToOneMmsLong(Telephony.Mms.Part._ID),
                messageId = cursor.requiredOneToOneMmsLong(Telephony.Mms.Part.MSG_ID),
                sequence = cursor.requiredOneToOneMmsInt(Telephony.Mms.Part.SEQ),
                contentType = cursor.optionalOneToOneMmsString(Telephony.Mms.Part.CONTENT_TYPE),
                text = cursor.optionalOneToOneMmsString(Telephony.Mms.Part.TEXT),
                dataPath = cursor.optionalOneToOneMmsString(Telephony.Mms.Part._DATA),
            )
        }

    @Suppress("DEPRECATION")
    override fun loadActiveSubscriptions(): List<ProviderOneToOneMmsSubscription> {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_STATE,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }

        return try {
            subscriptionManager.activeSubscriptionInfoList.orEmpty().map { subscription ->
                ProviderOneToOneMmsSubscription(
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
        val MMS_PARTS_URI: Uri = Uri.parse("content://mms/part")
    }
}

private class LibPhoneOneToOneMmsCanonicalizer(
    context: Context,
) : OneToOneMmsPhoneCanonicalizer {

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

private inline fun <T> Cursor?.readOneToOneMmsRows(mapper: (Cursor) -> T): List<T> {
    val cursor = this ?: error("Telephony provider query returned no cursor")
    return cursor.use {
        buildList {
            while (cursor.moveToNext()) add(mapper(cursor))
        }
    }
}

private fun Cursor.requiredOneToOneMmsLong(columnName: String): Long {
    val index = getColumnIndexOrThrow(columnName)
    check(!isNull(index)) { "Required provider column was null: $columnName" }
    return getLong(index)
}

private fun Cursor.requiredOneToOneMmsInt(columnName: String): Int {
    val index = getColumnIndexOrThrow(columnName)
    check(!isNull(index)) { "Required provider column was null: $columnName" }
    return getInt(index)
}

private fun Cursor.optionalOneToOneMmsString(columnName: String): String? {
    val index = getColumnIndexOrThrow(columnName)
    return if (isNull(index)) null else getString(index)
}
