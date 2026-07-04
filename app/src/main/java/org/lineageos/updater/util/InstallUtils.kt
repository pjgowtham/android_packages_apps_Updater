/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.util

import org.lineageos.updater.data.Update
import org.lineageos.updater.deviceinfo.DeviceInfoUtils
import java.io.File

object InstallUtils {
    @JvmStatic
    fun isScratchMounted() = runCatching {
        File("/proc/mounts").useLines { lines ->
            lines.any { it.split(" ")[1] == "/mnt/scratch" }
        }
    }.getOrDefault(false)

    enum class BlockedReason {
        NONE, DOWNGRADE, VERSION_UNSUPPORTED
    }

    @JvmStatic
    fun getBlockedReason(update: Update): BlockedReason {
        val osSdkLevel = update.osSdkLevel ?: return BlockedReason.VERSION_UNSUPPORTED

        if (!DeviceInfoUtils.isDowngradingAllowed &&
                (update.timestamp < DeviceInfoUtils.buildDateTimestamp ||
                        osSdkLevel < DeviceInfoUtils.sdkLevel)) {
            return BlockedReason.DOWNGRADE
        }

        if (!DeviceInfoUtils.isMajorUpdateAllowed &&
            osSdkLevel > DeviceInfoUtils.sdkLevel) {
            return BlockedReason.VERSION_UNSUPPORTED
        }

        return BlockedReason.NONE
    }

    @JvmStatic
    fun canInstall(update: Update) = getBlockedReason(update) == BlockedReason.NONE

    @JvmStatic
    fun canStreamUpdate(update: Update, streamUpdatesEnabled: Boolean) =
        DeviceInfoUtils.isABDevice &&
                streamUpdatesEnabled &&
                update.isAvailableOnline &&
                update.hasPayloadFileRanges() &&
                !update.hasFullyDownloadedPackage() &&
                !update.hasVerifiedPackage()
}
