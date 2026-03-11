/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.content.edit
import androidx.core.content.getSystemService
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.lineageos.updater.controller.UpdaterService
import org.lineageos.updater.data.NotificationHelper
import org.lineageos.updater.misc.Constants
import org.lineageos.updater.misc.DeviceInfoUtils
import org.lineageos.updater.misc.Utils
import org.lineageos.updater.worker.UpdateCheckWorker

class UpdaterReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_INSTALL_REBOOT -> {
                checkNotNull(context.getSystemService<PowerManager>()).reboot(null)
            }

            Intent.ACTION_BOOT_COMPLETED -> {
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        Utils.cleanupDownloadsDir(context)
                    } finally {
                        pendingResult.finish()
                    }
                }
                UpdateCheckWorker.schedulePeriodicCheck(context)
                UpdateCheckWorker.scheduleOneshotCheck(context)

                val pref = PreferenceManager.getDefaultSharedPreferences(context)

                val downloadId = pref.getString(Constants.PREF_NEEDS_REBOOT_ID, null)
                val updateSuccessful = isUpdateSuccessful(context)

                pref.edit { remove(Constants.PREF_NEEDS_REBOOT_ID) }

                if (downloadId != null && updateSuccessful) {
                    val cleanupIntent = Intent(context, UpdaterService::class.java).apply {
                        action = UpdaterService.ACTION_POST_REBOOT_CLEANUP
                        putExtra(UpdaterService.EXTRA_DOWNLOAD_ID, downloadId)
                    }
                    context.startService(cleanupIntent)
                }

                if (!pref.getBoolean(Constants.PREF_INSTALL_NOTIFIED, false)
                    && !updateSuccessful
                ) {
                    pref.edit { putBoolean(Constants.PREF_INSTALL_NOTIFIED, true) }
                    NotificationHelper(context).showUpdateFailedNotification()
                }
            }
        }
    }

    companion object {
        const val ACTION_INSTALL_REBOOT = "org.lineageos.updater.action.INSTALL_REBOOT"

        private fun isUpdateSuccessful(context: Context): Boolean {
            val buildTimestamp = DeviceInfoUtils.buildDateTimestamp
            val preferences = PreferenceManager.getDefaultSharedPreferences(context)

            // We can't easily detect failed re-installations.
            val isReinstall = preferences.getBoolean(Constants.PREF_INSTALL_AGAIN, false)
            val lastBuildTimestamp = preferences.getLong(Constants.PREF_INSTALL_OLD_TIMESTAMP, -1)

            return isReinstall || buildTimestamp != lastBuildTimestamp
        }
    }
}
