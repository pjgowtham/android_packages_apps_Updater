/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.core.content.getSystemService
import androidx.preference.PreferenceManager
import org.lineageos.updater.controller.UpdaterService
import org.lineageos.updater.misc.Constants
import org.lineageos.updater.misc.DeviceInfoUtils
import org.lineageos.updater.misc.StringGenerator
import java.text.DateFormat

class UpdaterReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_INSTALL_REBOOT -> {
                context.getSystemService<PowerManager>()!!.reboot(null)
            }

            Intent.ACTION_BOOT_COMPLETED -> {
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
                    showUpdateFailedNotification(context)
                }
            }
        }
    }

    companion object {
        const val ACTION_INSTALL_REBOOT = "org.lineageos.updater.action.INSTALL_REBOOT"

        private const val INSTALL_ERROR_NOTIFICATION_CHANNEL = "install_error_notification_channel"

        private fun isUpdateSuccessful(context: Context): Boolean {
            val buildTimestamp = DeviceInfoUtils.buildDateTimestamp
            val preferences = PreferenceManager.getDefaultSharedPreferences(context)

            // We can't easily detect failed re-installations.
            val isReinstall = preferences.getBoolean(Constants.PREF_INSTALL_AGAIN, false)
            val lastBuildTimestamp = preferences.getLong(Constants.PREF_INSTALL_OLD_TIMESTAMP, -1)

            return isReinstall || buildTimestamp != lastBuildTimestamp
        }

        private fun showUpdateFailedNotification(context: Context) {
            val preferences = PreferenceManager.getDefaultSharedPreferences(context)
            val buildDate = StringGenerator.getDateLocalizedUTC(
                context,
                DateFormat.MEDIUM,
                preferences.getLong(Constants.PREF_INSTALL_NEW_TIMESTAMP, 0)
            )
            val buildInfo = context.getString(
                R.string.list_build_version_date, DeviceInfoUtils.buildVersion, buildDate
            )

            val notificationIntent = Intent(context, UpdatesActivity::class.java)
            val intent = PendingIntent.getActivity(
                context,
                0,
                notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(
                context, INSTALL_ERROR_NOTIFICATION_CHANNEL
            ).apply {
                setContentIntent(intent)
                setSmallIcon(R.drawable.ic_system_update)
                setContentTitle(context.getString(R.string.update_failed_notification))
                setStyle(NotificationCompat.BigTextStyle().bigText(buildInfo))
                setContentText(buildInfo)
            }

            val nm = context.getSystemService<NotificationManager>()!!
            if (nm.getNotificationChannel(INSTALL_ERROR_NOTIFICATION_CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        INSTALL_ERROR_NOTIFICATION_CHANNEL,
                        context.getString(R.string.update_failed_channel_title),
                        NotificationManager.IMPORTANCE_LOW,
                    )
                )
            }
            nm.notify(0, builder.build())
        }
    }
}
