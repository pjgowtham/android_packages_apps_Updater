/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.UpdateEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.lineageos.updater.data.PreferencesRepository

data class BatteryState(
    val level: Float,
    val isCharging: Boolean,
    val acCharge: Boolean,
) {
    // Battery saver can trigger at up to 20% — keep thresholds at or above that
    // so an installation is never attempted while the device may be in a power-restricted state.
    val isLevelOk: Boolean
        get() = level >= if (isCharging) MIN_BATT_PCT_CHARGING else MIN_BATT_PCT_DISCHARGING

    companion object {
        const val MIN_BATT_PCT_CHARGING = 20
        const val MIN_BATT_PCT_DISCHARGING = 30
    }
}

class BatteryMonitor private constructor(context: Context) {

    companion object {
        @Volatile
        private var instance: BatteryMonitor? = null

        @JvmStatic
        fun getInstance(context: Context): BatteryMonitor =
            instance ?: synchronized(this) {
                instance ?: BatteryMonitor(context.applicationContext).also { instance = it }
            }

        private fun fromIntent(intent: Intent): BatteryState {
            val level: Int = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale: Int = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val batteryPct = level * 100 / scale.toFloat()

            val status: Int = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging: Boolean = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL

            val chargePlug: Int = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
            val acCharge: Boolean = chargePlug == BatteryManager.BATTERY_PLUGGED_AC

            return BatteryState(level = batteryPct, isCharging = isCharging, acCharge = acCharge)
        }
    }

    private val _batteryState = MutableStateFlow(
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.let { fromIntent(it) }
        // Batteryless devices (e.g. TVs) have no sticky broadcast. Assume the level is
        // sufficient and leave performance mode to the user preference alone.
            ?: BatteryState(level = 100f, isCharging = false, acCharge = false)
    )
    val batteryState = _batteryState.asStateFlow()

    // Null on non-AB devices where update_engine is absent.
    private val updateEngine: UpdateEngine? = try {
        UpdateEngine()
    } catch (_: Exception) {
        null
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = fromIntent(intent)
            val prev = _batteryState.value
            _batteryState.update { state }
            // On AC, maximize background updates; otherwise respect the user preference.
            // Guard on acCharge change to avoid redundant IPC on every battery level tick.
            if (state.acCharge != prev.acCharge) {
                try {
                    updateEngine?.setPerformanceMode(
                        state.acCharge || PreferencesRepository.getAbPerfModeBlocking(context)
                    )
                } catch (_: Exception) {
                    // Ignored
                }
            }
        }
    }

    init {
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }
}
