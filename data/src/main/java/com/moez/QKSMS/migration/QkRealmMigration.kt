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
package dev.octoshrimpy.quik.migration

import android.annotation.SuppressLint
import dev.octoshrimpy.quik.extensions.map
import dev.octoshrimpy.quik.mapper.CursorToContactImpl
import dev.octoshrimpy.quik.model.EmojiReaction
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.model.ReactionAttempt
import dev.octoshrimpy.quik.repository.ReactionTransportPolicy
import dev.octoshrimpy.quik.util.Preferences
import io.realm.DynamicRealm
import io.realm.DynamicRealmObject
import io.realm.FieldAttribute
import io.realm.RealmList
import io.realm.RealmMigration
import io.realm.Sort
import timber.log.Timber
import java.security.MessageDigest
import javax.inject.Inject

class QkRealmMigration @Inject constructor(
    private val cursorToContact: CursorToContactImpl,
    private val prefs: Preferences
) : RealmMigration {

    companion object {
        const val SCHEMA_VERSION: Long = 19
        private val LEGACY_TRANSPORT_KEY = Regex("^content://(sms|mms)/(\\d+)$")

        internal fun fromMeForCarrier(type: String?, boxId: Int): Boolean? =
            EmojiReaction.fromMeForCarrier(type, boxId)

        internal fun requireSupportedVersionRange(oldVersion: Long, newVersion: Long) {
            check(newVersion >= oldVersion) {
                "Realm downgrade from v$oldVersion to v$newVersion is not supported"
            }
        }

        /**
         * Schema 16 could infer SENT from the provider box without receiving Android's sent
         * callback. Since those two histories are indistinguishable, every legacy attempt must
         * fail closed. Schema 17 is the first version whose SENT state is callback-only.
         */
        internal fun terminalStateForVersion16(): String = ReactionAttempt.State.FAILED.name

        internal fun bodyFingerprint(body: String): String = MessageDigest
            .getInstance("SHA-256")
            .digest(body.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    @SuppressLint("ApplySharedPref")
    override fun migrate(realm: DynamicRealm, oldVersion: Long, newVersion: Long) {
        requireSupportedVersionRange(oldVersion, newVersion)

        var version = oldVersion

        if (version == 0L) {
            realm.schema.get("MmsPart")
                ?.removeField("image")

            version++
        }

        if (version == 1L) {
            realm.schema.get("Message")
                ?.addField("subId", Int::class.java)

            version++
        }

        if (version == 2L) {
            realm.schema.get("Conversation")
                ?.addField("name", String::class.java, FieldAttribute.REQUIRED)

            version++
        }

        if (version == 3L) {
            realm.schema.create("ScheduledMessage")
                .addField("id", Long::class.java, FieldAttribute.PRIMARY_KEY, FieldAttribute.REQUIRED)
                .addField("date", Long::class.java, FieldAttribute.REQUIRED)
                .addField("subId", Long::class.java, FieldAttribute.REQUIRED)
                .addRealmListField("recipients", String::class.java)
                .addField("sendAsGroup", Boolean::class.java, FieldAttribute.REQUIRED)
                .addField("body", String::class.java, FieldAttribute.REQUIRED)
                .addRealmListField("attachments", String::class.java)

            version++
        }

        if (version == 4L) {
            realm.schema.get("Conversation")
                ?.addField("pinned", Boolean::class.java, FieldAttribute.REQUIRED, FieldAttribute.INDEXED)

            version++
        }

        if (version == 5L) {
            realm.schema.create("BlockedNumber")
                .addField("id", Long::class.java, FieldAttribute.PRIMARY_KEY, FieldAttribute.REQUIRED)
                .addField("address", String::class.java, FieldAttribute.REQUIRED)

            version++
        }

        if (version == 6L) {
            realm.schema.get("Conversation")
                ?.addField("blockingClient", Integer::class.java)
                ?.addField("blockReason", String::class.java)

            realm.schema.get("MmsPart")
                ?.addField("seq", Integer::class.java, FieldAttribute.REQUIRED)
                ?.addField("name", String::class.java)

            version++
        }

        if (version == 7L) {
            realm.schema.get("Conversation")
                ?.addRealmObjectField("lastMessage", realm.schema.get("Message"))
                ?.removeField("count")
                ?.removeField("date")
                ?.removeField("snippet")
                ?.removeField("read")
                ?.removeField("me")

            val conversations = realm.where("Conversation")
                .findAll()

            val messages = realm.where("Message")
                .sort("date", Sort.DESCENDING)
                .distinct("threadId")
                .findAll()
                .associateBy { message -> message.getLong("threadId") }

            conversations.forEach { conversation ->
                conversation.setObject("lastMessage", messages[conversation.getLong("id")])
            }

            version++
        }

        if (version == 8L) {
            // Delete this data since we'll need to repopulate it with its new primaryKey
            realm.delete("PhoneNumber")

            realm.schema.create("ContactGroup")
                .addField("id", Long::class.java, FieldAttribute.PRIMARY_KEY, FieldAttribute.REQUIRED)
                .addField("title", String::class.java, FieldAttribute.REQUIRED)
                .addRealmListField("contacts", realm.schema.get("Contact"))

            realm.schema.get("PhoneNumber")
                ?.addField("id", Long::class.java, FieldAttribute.PRIMARY_KEY, FieldAttribute.REQUIRED)
                ?.addField("accountType", String::class.java)
                ?.addField("isDefault", Boolean::class.java, FieldAttribute.REQUIRED)

            val phoneNumbers = cursorToContact.getContactsCursor()
                ?.map(cursorToContact::map)
                ?.distinctBy { contact -> contact.numbers.firstOrNull()?.id } // Each row has only one number
                ?.groupBy { contact -> contact.lookupKey }
                ?: mapOf()

            realm.schema.get("Contact")
                ?.addField("starred", Boolean::class.java, FieldAttribute.REQUIRED)
                ?.addField("photoUri", String::class.java)
                ?.transform { realmContact ->
                    val numbers = RealmList<DynamicRealmObject>()
                    phoneNumbers[realmContact.get("lookupKey")]
                        ?.flatMap { contact -> contact.numbers }
                        ?.map { number ->
                            realm.createObject("PhoneNumber", number.id).apply {
                                setString("accountType", number.accountType)
                                setString("address", number.address)
                                setString("type", number.type)
                            }
                        }
                        ?.let(numbers::addAll)

                    val photoUri = phoneNumbers[realmContact.get("lookupKey")]
                        ?.firstOrNull { number -> number.photoUri != null }
                        ?.photoUri

                    realmContact.setList("numbers", numbers)
                    realmContact.setString("photoUri", photoUri)
                }

            // Migrate conversation themes
            val recipients = mutableMapOf<Long, Int>() // Map of recipientId:theme
            realm.where("Conversation").findAll().forEach { conversation ->
                val pref = prefs.theme(conversation.getLong("id"))
                if (pref.isSet) {
                    conversation.getList("recipients").forEach { recipient ->
                        recipients[recipient.getLong("id")] = pref.get()
                    }

                    pref.delete()
                }
            }

            recipients.forEach { (recipientId, theme) ->
                prefs.theme(recipientId).set(theme)
            }

            version++
        }

        if (version == 9L) {
            val migrateNotificationAction = { pref: Int ->
                when (pref) {
                    1 -> Preferences.NOTIFICATION_ACTION_READ
                    2 -> Preferences.NOTIFICATION_ACTION_REPLY
                    3 -> Preferences.NOTIFICATION_ACTION_CALL
                    4 -> Preferences.NOTIFICATION_ACTION_DELETE
                    else -> pref
                }
            }

            val migrateSwipeAction = { pref: Int ->
                when (pref) {
                    2 -> Preferences.SWIPE_ACTION_DELETE
                    3 -> Preferences.SWIPE_ACTION_CALL
                    4 -> Preferences.SWIPE_ACTION_READ
                    5 -> Preferences.SWIPE_ACTION_UNREAD
                    else -> pref
                }
            }

            if (prefs.notifAction1.isSet) prefs.notifAction1.set(migrateNotificationAction(prefs.notifAction1.get()))
            if (prefs.notifAction2.isSet) prefs.notifAction2.set(migrateNotificationAction(prefs.notifAction2.get()))
            if (prefs.notifAction3.isSet) prefs.notifAction3.set(migrateNotificationAction(prefs.notifAction3.get()))
            if (prefs.swipeLeft.isSet) prefs.swipeLeft.set(migrateSwipeAction(prefs.swipeLeft.get()))
            if (prefs.swipeRight.isSet) prefs.swipeRight.set(migrateSwipeAction(prefs.swipeRight.get()))

            version++
        }

        if (version == 10L) {
            realm.schema.get("MmsPart")
                ?.addField("messageId", Long::class.java, FieldAttribute.INDEXED, FieldAttribute.REQUIRED)
                ?.transform { part ->
                    val messageId = part.linkingObjects("Message", "parts").firstOrNull()?.getLong("contentId") ?: 0
                    part.setLong("messageId", messageId)
                }

            version++
        }
        if (version == 11L) {
            realm.schema.get("ScheduledMessage")
                ?.addField("conversationId", Long::class.java, FieldAttribute.REQUIRED)
            // Because there was never any property associated with which conversation/recipients a scheduled message was for,
            // we can't update this field on a realm migration. It will be set to a default of 0

            realm.schema.create("MessageContentFilter")
                .addField("id", Long::class.java, FieldAttribute.PRIMARY_KEY, FieldAttribute.REQUIRED)
                .addField("value", String::class.java, FieldAttribute.REQUIRED)
                .addField("caseSensitive", Boolean::class.java, FieldAttribute.REQUIRED)
                .addField("isRegex", Boolean::class.java, FieldAttribute.REQUIRED)
                .addField("includeContacts", Boolean::class.java, FieldAttribute.REQUIRED)

            version++
        }

        if (version == 12L) {
            realm.schema.get("Conversation")
                ?.addField("draftDate", Long::class.java, FieldAttribute.REQUIRED)

            version++
        }

        if (version == 13L) {
            val emojiReactionTable = realm.schema.create("EmojiReaction")
                .addField("id", Long::class.java, FieldAttribute.PRIMARY_KEY, FieldAttribute.REQUIRED)
                .addField("reactionMessageId", Long::class.java, FieldAttribute.INDEXED, FieldAttribute.REQUIRED)
                .addField("senderAddress", String::class.java, FieldAttribute.REQUIRED)
                .addField("emoji", String::class.java, FieldAttribute.REQUIRED)
                .addField("originalMessageText", String::class.java, FieldAttribute.REQUIRED)
                .addField("threadId", Long::class.java, FieldAttribute.INDEXED, FieldAttribute.REQUIRED)

            realm.schema.get("Message")
                ?.addField("isEmojiReaction", Boolean::class.java, FieldAttribute.REQUIRED)
                ?.addRealmListField("emojiReactions", emojiReactionTable)
                ?.transform { msg ->
                    msg.setBoolean("isEmojiReaction", false)
                }

            realm.schema.create("EmojiSyncNeeded")
                .addField("createdAt", Long::class.java, FieldAttribute.REQUIRED)

            realm.createObject("EmojiSyncNeeded")

            version++
        }

        if (version == 14L) {
            if (realm.schema.get("Conversation")?.hasField("sendAsGroup") == false) {
                realm.schema.get("Conversation")
                    ?.addField("sendAsGroup", Boolean::class.java, FieldAttribute.REQUIRED)
                    ?.transform { conversation ->
                        conversation.setBoolean(
                            "sendAsGroup",
                            (conversation.getList("recipients").size > 1)
                        )
                    }
            }
            if (realm.schema.get("Message")?.hasField("sendAsGroup") == false) {
                realm.schema.get("Message")
                    ?.addField("sendAsGroup", Boolean::class.java, FieldAttribute.REQUIRED)
            }

            version++
        }

        if (version == 15L) {
            realm.schema.get("EmojiReaction")
                ?.addField("fromMe", Boolean::class.javaObjectType)
                ?.transform { reaction ->
                    val carrier = realm.where("Message")
                        .equalTo("id", reaction.getLong("reactionMessageId"))
                        .findFirst()
                    val fromMe = carrier?.let { message ->
                        fromMeForCarrier(message.getString("type"), message.getInt("boxId"))
                    }
                    if (fromMe != null) {
                        reaction.setBoolean("fromMe", fromMe)
                    }
                }

            realm.schema.create("ReactionAttempt")
                .addField("id", String::class.java, FieldAttribute.PRIMARY_KEY, FieldAttribute.REQUIRED)
                .addField("targetKey", String::class.java, FieldAttribute.INDEXED, FieldAttribute.REQUIRED)
                .addField("transportKey", String::class.java, FieldAttribute.INDEXED)
                .addField("threadId", Long::class.java, FieldAttribute.INDEXED, FieldAttribute.REQUIRED)
                .addField("body", String::class.java, FieldAttribute.REQUIRED)
                .addField("state", String::class.java, FieldAttribute.INDEXED, FieldAttribute.REQUIRED)
                .addField("createdAt", Long::class.java, FieldAttribute.INDEXED, FieldAttribute.REQUIRED)

            version++
        }

        if (version == 16L) {
            realm.schema.get("ReactionAttempt")
                ?.addField("handoffAt", Long::class.java, FieldAttribute.INDEXED, FieldAttribute.REQUIRED)
                ?.transform { attempt ->
                    val targetKey = migrateVersion16TargetKey(realm, attempt.getString("targetKey"))
                    val transportKey = migrateVersion16TransportKey(
                        realm,
                        attempt.getString("transportKey"),
                    )
                    if (targetKey != null) attempt.setString("targetKey", targetKey)
                    if (transportKey != null) attempt.setString("transportKey", transportKey)
                    attempt.setString(
                        "state",
                        terminalStateForVersion16(),
                    )
                    attempt.setLong("handoffAt", 0L)
                }

            version++
        }

        if (version == 17L) {
            realm.schema.get("ReactionAttempt")
                ?.addField(
                    "ownerSessionId",
                    String::class.java,
                    FieldAttribute.INDEXED,
                    FieldAttribute.REQUIRED,
                )
                ?.addField(
                    "routeKind",
                    String::class.java,
                    FieldAttribute.INDEXED,
                    FieldAttribute.REQUIRED,
                )
                ?.addField("routeFingerprint", String::class.java, FieldAttribute.REQUIRED)
                ?.addField("routeRegion", String::class.java, FieldAttribute.REQUIRED)
                ?.addField("targetBodyFingerprint", String::class.java, FieldAttribute.REQUIRED)
                ?.addField("bodyFingerprint", String::class.java, FieldAttribute.REQUIRED)
                ?.transform { attempt ->
                    attempt.setString("ownerSessionId", "")
                    attempt.setString("routeKind", "")
                    attempt.setString("routeFingerprint", "")
                    attempt.setString("routeRegion", "")
                    attempt.setString("targetBodyFingerprint", "")
                    attempt.setString(
                        "bodyFingerprint",
                        bodyFingerprint(attempt.getString("body").orEmpty()),
                    )
                    attempt.setString("state", ReactionAttempt.State.FAILED.name)
                }

            version++
        }

        if (version == 18L) {
            realm.schema.get("ReactionAttempt")
                ?.addField("routeDirection", String::class.java, FieldAttribute.REQUIRED)
                ?.transform { attempt ->
                    attempt.setString("routeDirection", "")
                    attempt.setString("state", ReactionAttempt.State.FAILED.name)
                }

            version++
        }

        check(version >= SCHEMA_VERSION) {
            "Migration from v$oldVersion to v$newVersion failed at v$version"
        }

        // throw an exception if migration failed
        check(version >= newVersion) {
            "Realm migration from v$oldVersion to v$newVersion after v$version"
        }

        // else
        Timber.d("Realm migration from v$oldVersion to v$newVersion succeeded")
    }

    private fun migrateVersion16TargetKey(realm: DynamicRealm, value: String?): String? {
        value?.let(ReactionTransportPolicy.ProviderIdentity::decode)?.let { return it.encode() }
        val fields = value?.split(':') ?: return null
        if (fields.size != 4 || fields[0] != "v1") return null
        val realmMessageId = fields[1].toLongOrNull()?.takeIf { it > 0L } ?: return null
        val threadId = fields[2].toLongOrNull()?.takeIf { it > 0L } ?: return null
        val subId = fields[3].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val message = realm.where("Message")
            .equalTo("id", realmMessageId)
            .equalTo("threadId", threadId)
            .equalTo("subId", subId)
            .findFirst()
            ?: return null
        return providerKey(message)
    }

    private fun migrateVersion16TransportKey(realm: DynamicRealm, value: String?): String? {
        value?.let(ReactionTransportPolicy.ProviderIdentity::decode)?.let { return it.encode() }
        val match = value?.let { LEGACY_TRANSPORT_KEY.matchEntire(it) } ?: return null
        val type = match.groupValues[1]
        val contentId = match.groupValues[2].toLongOrNull()?.takeIf { it > 0L } ?: return null
        val message = realm.where("Message")
            .equalTo("type", type)
            .equalTo("contentId", contentId)
            .findFirst()
            ?: return null
        return providerKey(message)
    }

    private fun providerKey(message: DynamicRealmObject): String? {
        val type = message.getString("type")
            ?.takeIf { it == Message.TYPE_SMS || it == Message.TYPE_MMS }
            ?: return null
        val contentId = message.getLong("contentId").takeIf { it > 0L } ?: return null
        val threadId = message.getLong("threadId").takeIf { it > 0L } ?: return null
        val subId = message.getInt("subId").takeIf { it >= 0 } ?: return null
        return ReactionTransportPolicy.ProviderIdentity(type, contentId, threadId, subId).encode()
    }

}
