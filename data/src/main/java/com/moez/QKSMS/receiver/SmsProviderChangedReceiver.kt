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
package dev.octoshrimpy.quik.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import dagger.android.AndroidInjection
import dev.octoshrimpy.quik.interactor.SyncMessage
import dev.octoshrimpy.quik.manager.PermissionManager
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import timber.log.Timber
import javax.inject.Inject

/**
 * When the SMS contentprovider is changed by a process other than us, we need to sync the Uri that
 * was changed.
 *
 * This can happen if a message is sent through something like Pushbullet or Google Assistant.
 *
 * This only works on API 24+, so to fully solve this problem we'll need a smarter way of running
 * partial syncs on older devices.
 *
 * https://developer.android.com/reference/android/provider/Telephony.Sms.Intents.html#ACTION_EXTERNAL_PROVIDER_CHANGE
 */
class SmsProviderChangedReceiver : BroadcastReceiver() {

    @Inject lateinit var syncMessage: SyncMessage
    @Inject lateinit var permissionManager: PermissionManager

    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.data ?: return
        val isValidRequest = ReceiverRequestValidator.isProviderChangeRequest(
            intent.action,
            Telephony.Sms.Intents.ACTION_EXTERNAL_PROVIDER_CHANGE,
            uri.scheme,
            uri.authority,
            uri.lastPathSegment
        )
        if (!isValidRequest) return

        AndroidInjection.inject(this, context)
        if (!permissionManager.isDefaultSms()) return

        val pendingResult = goAsync()
        try {
            syncMessage.buildObservable(SyncMessage.Params(uri))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally { pendingResult.finish() }
                .subscribe({}, Timber::w)
        } catch (error: Throwable) {
            pendingResult.finish()
            throw error
        }
    }

}
