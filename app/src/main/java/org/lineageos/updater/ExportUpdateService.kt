/*
 * SPDX-FileCopyrightText: 2017-2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.FileUtils
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import org.lineageos.updater.misc.Constants.NOTIFICATION_CHANNEL_EXPORT_UPDATE
import org.lineageos.updater.misc.Constants.NOTIFICATION_ID_EXPORT_UPDATE
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Service responsible for exporting an update package to user-selected storage.
 * Uses android.os.FileUtils for direct file descriptor copy.
 * Notifies user on success or failure with destination information.
 */
class ExportUpdateService : Service() {

    companion object {
        private const val TAG = "ExportUpdateService"

        const val ACTION_START_EXPORTING = "start_exporting"
        const val EXTRA_SOURCE_FILE = "source_file"
        const val EXTRA_DEST_URI = "dest_uri"
    }

    @Volatile
    private var isExporting = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START_EXPORTING) {
            Log.e(TAG, "Invalid action")
            stopSelf()
            return START_NOT_STICKY
        }

        if (isExporting) {
            Log.e(TAG, "Already exporting")
            Toast.makeText(this, R.string.toast_already_exporting, Toast.LENGTH_SHORT).show()
            return START_NOT_STICKY
        }

        isExporting = true

        val source = intent.getSerializableExtra(EXTRA_SOURCE_FILE, File::class.java)
        val destination = intent.getParcelableExtra(EXTRA_DEST_URI, Uri::class.java)

        if (source == null || destination == null) {
            Log.e(TAG, "Missing source or destination")
            stopSelf()
            return START_NOT_STICKY
        }

        Thread { exportFile(source, destination) }.start()
        Toast.makeText(this, R.string.toast_export_started, Toast.LENGTH_SHORT).show()
        return START_NOT_STICKY
    }

    private fun exportFile(source: File, destination: Uri) {
        try {
            val pfd = contentResolver.openFileDescriptor(destination, "w")
                ?: throw IOException("Failed to open destination: $destination")

            FileInputStream(source).use { input ->
                FileOutputStream(pfd.fileDescriptor).use { output ->
                    FileUtils.copy(input, output)
                }
            }

            notifyResult(success = true, destination)
        } catch (e: IOException) {
            Log.e(TAG, "Export failed", e)
            notifyResult(success = false, destination)
        } finally {
            isExporting = false
            stopSelf()
        }
    }

    private fun notifyResult(success: Boolean, destination: Uri) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_EXPORT_UPDATE,
            getString(R.string.export_channel_title),
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)

        val title = if (success) {
            getString(R.string.notification_export_success)
        } else {
            getString(R.string.notification_export_fail)
        }

        val text = if (success) {
            getString(R.string.notification_export_success) + ": " + destination.toString()
        } else {
            getString(R.string.notification_export_fail)
        }

        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_EXPORT_UPDATE)
            .setSmallIcon(R.drawable.ic_system_update)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .build()

        startForeground(
            NOTIFICATION_ID_EXPORT_UPDATE,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )

        notificationManager.notify(NOTIFICATION_ID_EXPORT_UPDATE, notification)
    }
}

