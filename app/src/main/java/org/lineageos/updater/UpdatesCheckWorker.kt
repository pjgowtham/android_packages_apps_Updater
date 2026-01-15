/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater

import android.content.Context
import android.os.SystemProperties
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.suspendCancellableCoroutine
import org.lineageos.updater.download.DownloadClient
import org.lineageos.updater.misc.Constants
import org.lineageos.updater.misc.DeviceInfoUtils
import org.lineageos.updater.misc.NotificationHelper
import org.lineageos.updater.misc.Utils
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Handles the background task of checking for new updates.
 */
class UpdatesCheckWorker(
    private val context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    private val preferences = PreferenceManager.getDefaultSharedPreferences(context)

    override suspend fun doWork(): Result {
        val isImmediateCheck = inputData.getBoolean(KEY_IMMEDIATE_CHECK, false)
        if (!isImmediateCheck && !isPeriodicCheckEnabled(context)) {
            return Result.success()
        }

        Utils.cleanupDownloadsDir(context)

        val json = Utils.getCachedUpdateList(context)
        val jsonNew = File(json.absolutePath + UUID.randomUUID())
        val url = getUpdateCheckUrl(context)

        try {
            return suspendCancellableCoroutine { continuation ->
                try {
                    val client = DownloadClient.Builder()
                        .setUrl(url)
                        .setDestination(jsonNew)
                        .setDownloadCallback(object : DownloadClient.DownloadCallback {
                            override fun onResponse(headers: DownloadClient.Headers) {
                            }

                            override fun onSuccess() {
                                try {
                                    if (json.exists() && checkForNewUpdates(json, jsonNew)) {
                                        NotificationHelper.getInstance(context).notifyNewUpdates()
                                    }
                                    if (!jsonNew.renameTo(json)) {
                                        throw IOException("Could not rename downloaded file to ${json.absolutePath}")
                                    }
                                    preferences.edit {
                                        putLong(
                                            Constants.PREF_LAST_UPDATE_CHECK,
                                            System.currentTimeMillis()
                                        )
                                    }
                                    if (continuation.isActive) {
                                        continuation.resume(Result.success())
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Could not process updates list", e)
                                    if (continuation.isActive) {
                                        continuation.resume(Result.retry())
                                    }
                                }
                            }

                            override fun onFailure(cancelled: Boolean) {
                                if (cancelled) {
                                    Log.d(TAG, "Download cancelled")
                                    // Worker was cancelled
                                } else {
                                    Log.e(TAG, "Download failed")
                                    if (continuation.isActive) {
                                        continuation.resume(Result.retry())
                                    }
                                }
                            }
                        })
                        .build()

                    client.start()

                    continuation.invokeOnCancellation {
                        client.cancel()
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Could not download updates list, retrying.", e)
                    if (continuation.isActive) {
                        continuation.resume(Result.retry())
                    }
                }
            }
        } finally {
            jsonNew.delete()
        }
    }

    /**
     * Compares two JSON formatted update list files.
     *
     * @param oldJson Old update list
     * @param newJson New update list
     * @return True if newJson has at least one compatible update not in oldJson
     */
    private fun checkForNewUpdates(oldJson: File, newJson: File): Boolean {
        val oldList = Utils.parseJson(oldJson, true)
        val newList = Utils.parseJson(newJson, true)
        val oldIds = oldList.map { it.downloadId }.toSet()
        return newList.any { it.downloadId !in oldIds }
    }

    companion object {
        private const val TAG = "UpdatesCheckWorker"
        private const val KEY_IMMEDIATE_CHECK = "immediate_check"
        private const val RETRY_DELAY_HOURS = 2L

        const val WORK_NAME = "updates_check"
        const val WORK_NAME_IMMEDIATE = "updates_check_immediate"

        private val CONSTRAINTS = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /**
         * Update check intervals.
         */
        enum class CheckInterval(val value: Int, val milliseconds: Long) {
            NEVER(Constants.AUTO_UPDATES_CHECK_INTERVAL_NEVER, 0),
            DAILY(Constants.AUTO_UPDATES_CHECK_INTERVAL_DAILY, TimeUnit.DAYS.toMillis(1)),
            WEEKLY(Constants.AUTO_UPDATES_CHECK_INTERVAL_WEEKLY, TimeUnit.DAYS.toMillis(7)),
            MONTHLY(Constants.AUTO_UPDATES_CHECK_INTERVAL_MONTHLY, TimeUnit.DAYS.toMillis(30));

            companion object {
                fun fromValue(value: Int): CheckInterval =
                    entries.find { it.value == value } ?: WEEKLY
            }
        }

        private fun getUpdateCheckUrl(context: Context): String {
            val incrementalVersion = DeviceInfoUtils.buildVersionIncremental
            val device = DeviceInfoUtils.device
            val type = DeviceInfoUtils.releaseType.lowercase(java.util.Locale.ROOT)

            var serverUrl = DeviceInfoUtils.updaterUri
            if (serverUrl.trim().isEmpty()) {
                serverUrl = context.getString(R.string.updater_server_url)
            }

            return serverUrl.replace("{device}", device)
                .replace("{type}", type)
                .replace("{incr}", incrementalVersion)
        }

        private fun getCheckInterval(context: Context): CheckInterval {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val value = prefs.getInt(
                Constants.PREF_AUTO_UPDATES_CHECK_INTERVAL,
                Constants.AUTO_UPDATES_CHECK_INTERVAL_WEEKLY
            )
            return CheckInterval.fromValue(value)
        }

        private fun isPeriodicCheckEnabled(context: Context): Boolean =
            getCheckInterval(context) != CheckInterval.NEVER

        @JvmStatic
        fun getUpdateCheckSetting(context: Context): Int =
            getCheckInterval(context).value

        @JvmStatic
        fun updateSchedule(context: Context) {
            if (isPeriodicCheckEnabled(context)) {
                schedule(context)
            } else {
                cancel(context)
            }
        }

        /**
         * Schedules a repeating update check.
         */
        @JvmStatic
        fun schedule(context: Context) {
            val interval = getCheckInterval(context)
            val workRequest = PeriodicWorkRequest.Builder(
                UpdatesCheckWorker::class.java,
                interval.milliseconds,
                TimeUnit.MILLISECONDS
            )
                .setConstraints(CONSTRAINTS)
                .setBackoffCriteria(BackoffPolicy.LINEAR, RETRY_DELAY_HOURS, TimeUnit.HOURS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )
        }

        /**
         * Cancels the repeating update check.
         */
        @JvmStatic
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /**
         * Runs an immediate update check.
         */
        @JvmStatic
        fun runImmediateCheck(context: Context) {
            val workRequest = OneTimeWorkRequest.Builder(UpdatesCheckWorker::class.java)
                .setConstraints(CONSTRAINTS)
                .setInputData(
                    Data.Builder()
                        .putBoolean(KEY_IMMEDIATE_CHECK, true)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_IMMEDIATE,
                ExistingWorkPolicy.KEEP,
                workRequest
            )
        }
    }
}
