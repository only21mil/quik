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
 * Reloads all routing evidence from Android for every resolution attempt. This class never writes
 * to a provider and never invokes an SMS or MMS send API.
 */
@Singleton
class ProviderBackedGroupMmsReactionRouteResolver @Inject constructor(
    context: Context,
) : GroupMmsReactionRouteResolver {

    private val resolution = GroupMmsReactionRouteResolution(
        AndroidGroupMmsReactionRouteProvider(context.applicationContext),
        LibPhoneGroupMmsCanonicalizer(context.applicationContext),
    )

    override fun resolve(request: GroupMmsReactionRouteRequest): GroupMmsReactionRouteResult =
        resolution.resolve(request)
}

private class AndroidGroupMmsReactionRouteProvider(
    private val context: Context,
) : GroupMmsReactionRouteProvider {

    private val contentResolver: ContentResolver = context.contentResolver
    private val subscriptionManager: SubscriptionManager = SubscriptionManager.from(context)

    override fun loadTarget(mmsId: Long): List<ProviderMmsTarget> {
        val uri = ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, mmsId)
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
        ).readRows { cursor ->
            val messageBox = cursor.requiredInt(Telephony.Mms.MESSAGE_BOX)
            ProviderMmsTarget(
                id = cursor.requiredLong(Telephony.Mms._ID),
                threadId = cursor.requiredLong(Telephony.Mms.THREAD_ID),
                subscriptionId = cursor.requiredInt(Telephony.Mms.SUBSCRIPTION_ID),
                direction = when (messageBox) {
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
    ).readRows { cursor ->
        ProviderThread(
            id = cursor.requiredLong(Telephony.Threads._ID),
            recipientIds = cursor.optionalString(Telephony.Threads.RECIPIENT_IDS),
        )
    }

    override fun loadCanonicalAddresses(
        recipientIds: List<Long>,
    ): List<ProviderCanonicalAddress> {
        val placeholders = recipientIds.joinToString(",") { "?" }
        return contentResolver.query(
            CANONICAL_ADDRESSES_URI,
            arrayOf(
                Telephony.CanonicalAddressesColumns._ID,
                Telephony.CanonicalAddressesColumns.ADDRESS,
            ),
            "${Telephony.CanonicalAddressesColumns._ID} IN ($placeholders)",
            recipientIds.map(Long::toString).toTypedArray(),
            null,
        ).readRows { cursor ->
            ProviderCanonicalAddress(
                id = cursor.requiredLong(Telephony.CanonicalAddressesColumns._ID),
                address = cursor.optionalString(Telephony.CanonicalAddressesColumns.ADDRESS),
            )
        }
    }

    override fun loadMmsAddresses(mmsId: Long): List<ProviderMmsAddress> {
        val uri = Telephony.Mms.CONTENT_URI.buildUpon()
            .appendPath(mmsId.toString())
            .appendPath("addr")
            .build()
        return contentResolver.query(
            uri,
            arrayOf(Telephony.Mms.Addr.ADDRESS, Telephony.Mms.Addr.TYPE),
            null,
            null,
            null,
        ).readRows { cursor ->
            val type = when (cursor.requiredInt(Telephony.Mms.Addr.TYPE)) {
                PduHeaders.FROM -> ProviderMmsAddressType.FROM
                PduHeaders.TO -> ProviderMmsAddressType.TO
                PduHeaders.CC -> ProviderMmsAddressType.CC
                else -> ProviderMmsAddressType.UNSUPPORTED
            }
            ProviderMmsAddress(
                type = type,
                address = cursor.optionalString(Telephony.Mms.Addr.ADDRESS),
            )
        }
    }

    override fun loadMmsParts(mmsId: Long): List<ProviderGroupMmsPart> =
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
            arrayOf(mmsId.toString()),
            null,
        ).readRows { cursor ->
            ProviderGroupMmsPart(
                id = cursor.requiredLong(Telephony.Mms.Part._ID),
                messageId = cursor.requiredLong(Telephony.Mms.Part.MSG_ID),
                sequence = cursor.requiredInt(Telephony.Mms.Part.SEQ),
                contentType = cursor.optionalString(Telephony.Mms.Part.CONTENT_TYPE),
                text = cursor.optionalString(Telephony.Mms.Part.TEXT),
                dataPath = cursor.optionalString(Telephony.Mms.Part._DATA),
            )
        }

    @Suppress("DEPRECATION")
    override fun loadActiveSubscriptions(): List<ProviderSubscription> {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_STATE,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }

        return try {
            subscriptionManager.activeSubscriptionInfoList.orEmpty().map { subscription ->
                ProviderSubscription(
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

private class LibPhoneGroupMmsCanonicalizer(
    context: Context,
) : GroupMmsPhoneCanonicalizer {

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

private inline fun <T> Cursor?.readRows(mapper: (Cursor) -> T): List<T> {
    val cursor = this ?: error("Telephony provider query returned no cursor")
    return cursor.use {
        buildList {
            while (cursor.moveToNext()) {
                add(mapper(cursor))
            }
        }
    }
}

private fun Cursor.requiredLong(columnName: String): Long {
    val index = getColumnIndexOrThrow(columnName)
    check(!isNull(index)) { "Required provider column was null: $columnName" }
    return getLong(index)
}

private fun Cursor.requiredInt(columnName: String): Int {
    val index = getColumnIndexOrThrow(columnName)
    check(!isNull(index)) { "Required provider column was null: $columnName" }
    return getInt(index)
}

private fun Cursor.optionalString(columnName: String): String? {
    val index = getColumnIndexOrThrow(columnName)
    return if (isNull(index)) null else getString(index)
}
