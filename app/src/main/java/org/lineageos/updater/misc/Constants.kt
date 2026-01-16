/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.misc

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.lineageos.updater.UpdatesCheckWorker
import java.util.concurrent.TimeUnit

object Constants {

    // Miscellaneous
    const val AB_PAYLOAD_BIN_PATH = "payload.bin"
    const val AB_PAYLOAD_PROPERTIES_PATH = "payload_properties.txt"
    const val UNCRYPT_FILE_EXT = ".uncrypt"
    const val UPDATE_RECOVERY_EXEC = "/vendor/bin/install-recovery.sh"

    // Preference keys - Update check state
    const val LAST_UPDATE_CHECK = "last_update_check"

    // Preference keys - Install state
    const val NEEDS_REBOOT_ID = "needs_reboot_id"
    const val INSTALL_OLD_TIMESTAMP = "install_old_timestamp"
    const val INSTALL_NEW_TIMESTAMP = "install_new_timestamp"
    const val INSTALL_PACKAGE_PATH = "install_package_path"
    const val INSTALL_AGAIN = "install_again"
    const val INSTALL_NOTIFIED = "install_notified"

    // Preference keys - UI state
    const val HAS_SEEN_INFO_DIALOG = "has_seen_info_dialog"
    const val HAS_SEEN_WELCOME_MESSAGE = "has_seen_welcome_message"

    // Preference keys - User settings
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

            fun getScheduledTime(context: Context): Long? {
                return try {
                    val workInfos = WorkManager.getInstance(context)
                        .getWorkInfosForUniqueWork(UpdatesCheckWorker.WORK_NAME)
                        .get()
                    workInfos.firstOrNull {
                        it.state == WorkInfo.State.ENQUEUED
                    }?.nextScheduleTimeMillis
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
}
