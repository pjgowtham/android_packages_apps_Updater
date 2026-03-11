/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.worker

import android.content.Context
import android.util.Log
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
import org.lineageos.updater.data.CheckInterval
import org.lineageos.updater.data.NotificationHelper
import org.lineageos.updater.data.PreferencesRepository
import org.lineageos.updater.data.UpdaterRepository
import kotlin.time.Duration.Companion.hours
import kotlin.time.toJavaDuration

class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        val result = UpdaterRepository(applicationContext).fetchUpdates()

        if (result.hasNewUpdates) {
            NotificationHelper(applicationContext).showNewUpdatesNotification()
        }

        Result.success()
    } catch (e: Exception) {
        Log.e(TAG, "Update check failed", e)
        Result.retry()
    }

    companion object {
        private const val TAG = "UpdateCheckWorker"

        private const val WORK_NAME_ONESHOT = "update_check_oneshot"
        private const val WORK_NAME_PERIODIC = "update_check_periodic"

        private val networkConstraints =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

        @JvmStatic
        fun cancelPeriodicCheck(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_PERIODIC)
            Log.d(TAG, "Cancelled periodic update check")
        }

        @JvmStatic
        fun reschedulePeriodicCheck(context: Context) {
            enqueuePeriodicCheck(
                context = context,
                interval = PreferencesRepository(context).getCheckInterval(),
                policy = ExistingPeriodicWorkPolicy.UPDATE,
            )
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

        @JvmStatic
        @JvmOverloads
        fun schedulePeriodicCheck(
            context: Context,
            interval: CheckInterval = PreferencesRepository(context).getCheckInterval(),
            replaceExisting: Boolean = false,
        ) {
            enqueuePeriodicCheck(
                context = context,
                interval = interval,
                policy = if (replaceExisting) {
                    ExistingPeriodicWorkPolicy.UPDATE
                } else {
                    ExistingPeriodicWorkPolicy.KEEP
                },
            )
        }

        private fun enqueuePeriodicCheck(
            context: Context,
            interval: CheckInterval,
            policy: ExistingPeriodicWorkPolicy,
        ) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
                interval.duration.toJavaDuration(),
            )
                .addTag(WORK_NAME_PERIODIC)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1.hours.toJavaDuration())
                .setConstraints(networkConstraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC,
                policy,
                request,
            )
            Log.d(TAG, "Enqueued periodic check (policy=$policy, interval=${interval.value})")
        }
    }
}
