/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService

object UpdateNotifier {

    private const val NEW_UPDATES_NOTIFICATION_CHANNEL = "new_updates_notification_channel"
    private const val NEW_UPDATES_NOTIFICATION_ID = 1

    fun createNotificationChannel(context: Context) {
        val nm = context.getSystemService<NotificationManager>() ?: return
        if (nm.getNotificationChannel(NEW_UPDATES_NOTIFICATION_CHANNEL) != null) return

        nm.createNotificationChannel(
            NotificationChannel(
                NEW_UPDATES_NOTIFICATION_CHANNEL,
                context.getString(R.string.new_updates_channel_title),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    fun showNewUpdatesNotification(context: Context) {
        val nm = context.getSystemService<NotificationManager>() ?: return
        createNotificationChannel(context)

        val intent = PendingIntent.getActivity(
            context, 0,
            Intent(context, UpdatesActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, NEW_UPDATES_NOTIFICATION_CHANNEL)
            .setAutoCancel(true).setContentIntent(intent)
            .setContentTitle(context.getString(R.string.new_updates_found_title))
            .setSmallIcon(R.drawable.ic_system_update).build()

        nm.notify(NEW_UPDATES_NOTIFICATION_ID, notification)
    }
}
