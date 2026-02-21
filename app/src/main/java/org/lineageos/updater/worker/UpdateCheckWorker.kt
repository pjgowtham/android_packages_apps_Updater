/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.lineageos.updater.R
import org.lineageos.updater.UpdatesActivity
import org.lineageos.updater.data.UpdateCheckRepository
import org.lineageos.updater.misc.Constants
import java.util.concurrent.TimeUnit

/**
 * A [CoroutineWorker] that periodically checks for system updates.
 *
 * Replaces the legacy `UpdatesCheckReceiver` + `AlarmManager` pattern with
 * WorkManager, which is battery-efficient, constraint-aware, and survives reboots.
 */
class UpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repository = UpdateCheckRepository(applicationContext)
        return when (val fetchResult = repository.fetchUpdates()) {
            is UpdateCheckRepository.FetchResult.Success -> {
                if (fetchResult.newUpdatesFound) {
                    showNotification(applicationContext)
                }
                Result.success()
            }
            is UpdateCheckRepository.FetchResult.NetworkError -> {
                Log.w(TAG, "Network error during update check, will retry")
                Result.retry()
            }
            is UpdateCheckRepository.FetchResult.ParseError -> {
                Log.e(TAG, "Parse error during update check")
                Result.failure()
            }
        }
    }

    companion object {
        private const val TAG = "UpdateCheckWorker"

        /** Unique work name used by WorkManager. */
        private const val WORK_NAME = "update_check"

        private const val NEW_UPDATES_NOTIFICATION_CHANNEL = "new_updates_notification_channel"
        private const val NOTIFICATION_ID_NEW_UPDATES = 100

        /**
         * Schedule a periodic update check using WorkManager.
         * Respects the user's check interval preference.
         * The [ExistingPeriodicWorkPolicy.UPDATE] policy ensures that if the interval
         * changes, the existing work is updated rather than duplicated.
         */
        @JvmStatic
        fun schedule(context: Context) {
            val preferences = PreferenceManager.getDefaultSharedPreferences(context)
            if (!preferences.getBoolean(Constants.PREF_PERIODIC_CHECK_ENABLED, true)) {
                cancel(context)
                return
            }

            val intervalValue = preferences.getString(Constants.CheckInterval.PREF_KEY, null)
            val interval = Constants.CheckInterval.fromValue(context, intervalValue)

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
                interval.milliseconds, TimeUnit.MILLISECONDS,
            ).setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )

            Log.d(TAG, "Scheduled periodic update check: every ${interval.name}")
        }

        /**
         * Cancel any scheduled periodic update checks.
         */
        @JvmStatic
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Cancelled periodic update check")
        }

        /**
         * Re-schedule periodic checks (cancel + schedule).
         * Called when the user changes the check interval.
         */
        @JvmStatic
        fun reschedule(context: Context) {
            schedule(context)
        }

        private fun showNotification(context: Context) {
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                NEW_UPDATES_NOTIFICATION_CHANNEL,
                context.getString(R.string.new_updates_channel_title),
                NotificationManager.IMPORTANCE_LOW,
            )
            notificationManager.createNotificationChannel(channel)

            val contentIntent = PendingIntent.getActivity(
                context, 0,
                Intent(context, UpdatesActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notification = NotificationCompat.Builder(context, NEW_UPDATES_NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_system_update)
                .setContentIntent(contentIntent)
                .setContentTitle(context.getString(R.string.new_updates_found_title))
                .setAutoCancel(true)
                .build()

            notificationManager.notify(NOTIFICATION_ID_NEW_UPDATES, notification)
        }
    }
}
