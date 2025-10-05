/*
 * SPDX-FileCopyrightText: 2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.misc

enum class AutoUpdatesCheckInterval(val value: Int) {
    DAILY(1),
    MONTHLY(3),
    NEVER(0),
    WEEKLY(2),
}

object Constants {
    const val AB_PAYLOAD_BIN_PATH: String = "payload.bin"
    const val AB_PAYLOAD_PROPERTIES_PATH: String = "payload_properties.txt"

    const val HAS_SEEN_INFO_DIALOG: String = "has_seen_info_dialog"
    const val HAS_SEEN_WELCOME_MESSAGE: String = "has_seen_welcome_message"

    const val PREF_AB_PERF_MODE: String = "ab_perf_mode"
    const val PREF_AUTO_DELETE_UPDATES: String = "auto_delete_updates"
    const val PREF_AUTO_UPDATES_CHECK_INTERVAL: String = "auto_updates_check_interval"
    const val PREF_INSTALL_AGAIN: String = "install_again"
    const val PREF_INSTALL_NEW_TIMESTAMP: String = "install_new_timestamp"
    const val PREF_INSTALL_NOTIFIED: String = "install_notified"
    const val PREF_INSTALL_OLD_TIMESTAMP: String = "install_old_timestamp"
    const val PREF_INSTALL_PACKAGE_PATH: String = "install_package_path"
    const val PREF_LAST_UPDATE_CHECK: String = "last_update_check"
    const val PREF_METERED_NETWORK_WARNING: String = "pref_metered_network_warning"
    const val PREF_MOBILE_DATA_WARNING: String = "pref_mobile_data_warning"
    const val PREF_NEEDS_REBOOT_ID: String = "needs_reboot_id"

    const val PROP_AB_DEVICE: String = "ro.build.ab_update"
    const val PROP_ALLOW_MAJOR_UPGRADES: String = "lineage.updater.allow_major_upgrades"
    const val PROP_BUILD_DATE: String = "ro.build.date.utc"
    const val PROP_BUILD_VERSION: String = "ro.lineage.build.version"
    const val PROP_BUILD_VERSION_INCREMENTAL: String = "ro.build.version.incremental"
    const val PROP_DEVICE: String = "ro.lineage.device"
    const val PROP_NEXT_DEVICE: String = "ro.updater.next_device"
    const val PROP_RELEASE_TYPE: String = "ro.lineage.releasetype"
    const val PROP_UPDATER_ALLOW_DOWNGRADING: String = "lineage.updater.allow_downgrading"
    const val PROP_UPDATER_URI: String = "lineage.updater.uri"

    const val UNCRYPT_FILE_EXT: String = ".uncrypt"

    const val UPDATE_RECOVERY_EXEC: String = "/vendor/bin/install-recovery.sh"
    const val UPDATE_RECOVERY_PROP: String = "persist.vendor.recovery_update"
}
