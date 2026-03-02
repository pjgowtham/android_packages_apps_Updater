/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.worker

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.lineageos.updater.UpdateNotifier
import org.lineageos.updater.misc.Constants
import org.lineageos.updater.repository.UpdaterRepository
import java.util.concurrent.TimeUnit

class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

     override suspend fun doWork(): Result {
        val context = applicationContext
        val isOneShot = tags.contains(WORK_NAME_ONESHOT)

        return try {
            val result = UpdaterRepository(context).fetchUpdates()

            if (result.updates.isNotEmpty()) {
                UpdateNotifier.showNewUpdatesNotification(context)
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed", e)
            if (!isOneShot) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    companion object {
        private const val TAG = "UpdateCheckWorker"

        private const val WORK_NAME_ONESHOT = "update_check_oneshot"
        private const val WORK_NAME_PERIODIC = "update_check_periodic"

        private val networkConstraints =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

        fun cancelPeriodicCheck(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_PERIODIC)
            Log.d(TAG, "Cancelled periodic update check")
        }

        fun scheduleOneshotCheck(context: Context) {
            val request =
                OneTimeWorkRequestBuilder<UpdateCheckWorker>()
                    .addTag(WORK_NAME_ONESHOT)
                    .setConstraints(networkConstraints)
                    .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_ONESHOT,
                ExistingWorkPolicy.KEEP,
                request,
            )
            Log.d(TAG, "Scheduled one-shot update check")
        }

        fun schedulePeriodicCheck(context: Context) {
            val preferences = PreferenceManager.getDefaultSharedPreferences(context)
            if (!preferences.getBoolean(Constants.PREF_PERIODIC_CHECK_ENABLED, true)) return

            val intervalValue = preferences.getString(Constants.CheckInterval.PREF_KEY, null)
            val interval = Constants.CheckInterval.fromValue(context, intervalValue)

            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
                interval.milliseconds, TimeUnit.MILLISECONDS,
            )
                .addTag(WORK_NAME_PERIODIC)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    1,
                    TimeUnit.HOURS
                )
                .setConstraints(networkConstraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )

            Log.d(TAG, "Scheduled periodic update check every ${interval.milliseconds}ms")
        }
    }
}
