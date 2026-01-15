/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.model

import org.lineageos.updater.UpdatesCheckWorker
import java.util.concurrent.TimeUnit

object Preferences {

    const val AB_PERF_MODE = "ab_perf_mode"
    const val AUTO_DELETE_UPDATES = "auto_delete_updates"
    const val METERED_NETWORK_WARNING = "pref_metered_network_warning"
    const val UPDATE_RECOVERY = "update_recovery"

    enum class CheckInterval(val value: String, val milliseconds: Long) {
        DAILY("1", TimeUnit.DAYS.toMillis(1)),
        WEEKLY("2", TimeUnit.DAYS.toMillis(7)),
        MONTHLY("3", TimeUnit.DAYS.toMillis(30));

        companion object {
            const val KEY_ENABLED = "periodic_check_enabled"
            const val KEY_INTERVAL = "periodic_check_interval"
            val DEFAULT = WEEKLY

            fun fromValue(value: String?): CheckInterval =
                entries.find { it.value == value } ?: DEFAULT

            fun getScheduledTime(context: android.content.Context): Long? {
                try {
                    val workInfos = androidx.work.WorkManager.getInstance(context)
                        .getWorkInfosForUniqueWork(UpdatesCheckWorker.WORK_NAME)
                        .get()
                    return workInfos.firstOrNull {
                        it.state == androidx.work.WorkInfo.State.ENQUEUED
                    }?.nextScheduleTimeMillis
                } catch (_: Exception) {
                    return null
                }
            }
        }
    }
}
