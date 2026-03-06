/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.misc

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import org.lineageos.updater.R
import java.io.File
import java.io.IOException

object UtilsKt {

    /**
     * Checks if the battery level is sufficient for updates.
     * The absolute minimum where battery saver can be triggered is 20%.
     * We require minimum 30% if discharging to provide a 10% safety margin.
     * If plugged in, we allow the absolute minimum of 20%.
     */
    @JvmStatic
    fun isBatteryLevelOk(context: Context): Boolean {
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
            context.registerReceiver(null, ifilter)
        }

        val batteryPct: Int? = batteryStatus?.let { intent ->
            val level: Int = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale: Int = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            level * 100 / scale
        }

        // If battery level or scale is unavailable, don't block the update
        if (batteryPct == null || batteryPct == -1) return true

        val status: Int = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging: Boolean = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL

        val required = if (isCharging)
            context.resources.getInteger(R.integer.battery_ok_percentage_charging)
            else
            context.resources.getInteger(R.integer.battery_ok_percentage_discharging)
        return batteryPct >= required
    }

    @JvmStatic
    fun isScratchMounted(): Boolean {
        return try {
            File("/proc/mounts").useLines { lines ->
                lines.any { line -> line.split(" ").getOrNull(1) == "/mnt/scratch" }
            }
        } catch (_: IOException) {
            false
        }
    }
}
