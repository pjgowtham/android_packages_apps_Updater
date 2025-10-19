/*
 * SPDX-FileCopyrightText: 2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import org.lineageos.updater.misc.Utils
import java.util.concurrent.TimeUnit

/**
 * This receiver handles boot completion to schedule the update check worker.
 */

class UpdatesCheckReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Utils.cleanupDownloadsDir(context)
            schedule(context)
        }
    }

    companion object {
        /**
         * Schedule a repeating update check.
         */
        @JvmStatic
        fun schedule(context: Context) {
            val workManager = WorkManager.getInstance(context)
            if (!Utils.isUpdateCheckEnabled(context)) {
                cancel(context)
                return
            }

            val constraints =
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

            val retryDelay = AlarmManager.INTERVAL_HOUR * 2

            val workRequest = PeriodicWorkRequest.Builder(
                UpdatesCheckWorker::class.java,
                Utils.getUpdateCheckInterval(context), // Replaces repeating alarm
                TimeUnit.MILLISECONDS
            ).setConstraints(constraints).setBackoffCriteria(
                BackoffPolicy.LINEAR, retryDelay, TimeUnit.MILLISECONDS
            ).build()

            workManager.enqueueUniquePeriodicWork(
                UpdatesCheckWorker.WORK_NAME, ExistingPeriodicWorkPolicy.REPLACE, workRequest
            )
        }

        /**
         * Cancel the repeating update check.
         */
        @JvmStatic
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UpdatesCheckWorker.WORK_NAME)
        }
    }
}
