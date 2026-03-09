/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.controller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class ControllerEvent(
    val action: String,
    val downloadId: String?,
)

/**
 * Emits a [ControllerEvent] for every broadcast fired by [UpdaterController].
 *
 * Broadcast registration and unregistration are handled automatically
 * via [callbackFlow]'s [awaitClose] block, so the receiver is active only while
 * the flow is being collected.
 */
fun Context.updaterControllerEvents(): Flow<ControllerEvent> = callbackFlow {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            trySend(
                ControllerEvent(
                    action = action,
                    downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID),
                )
            )
        }
    }
    val filter = IntentFilter().apply {
        addAction(UpdaterController.ACTION_UPDATE_STATUS)
        addAction(UpdaterController.ACTION_DOWNLOAD_PROGRESS)
        addAction(UpdaterController.ACTION_INSTALL_PROGRESS)
        addAction(UpdaterController.ACTION_UPDATE_REMOVED)
    }
    ContextCompat.registerReceiver(
        this@updaterControllerEvents,
        receiver,
        filter,
        ContextCompat.RECEIVER_NOT_EXPORTED
    )
    awaitClose {
        unregisterReceiver(receiver)
    }
}
