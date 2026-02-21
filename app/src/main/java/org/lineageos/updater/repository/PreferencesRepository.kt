/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.repository

import android.content.Context
import android.content.SharedPreferences
import android.os.UpdateEngine
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import org.lineageos.updater.UpdatesCheckReceiver
import org.lineageos.updater.misc.Constants
import org.lineageos.updater.misc.Constants.CheckInterval
import org.lineageos.updater.misc.DeviceInfoUtils

data class PreferencesData(
    val periodicCheckEnabled: Boolean,
    val periodicCheckInterval: CheckInterval,
    val autoDeleteUpdates: Boolean,
    val meteredNetworkWarning: Boolean,
    val abPerfMode: Boolean,
    val abStreamingMode: Boolean,
    val updateRecovery: Boolean,
)

class PreferencesRepository(private val context: Context) {

    companion object {
        private const val TAG = "PreferencesRepository"
    }

    private val prefs: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    val preferencesData: Flow<PreferencesData> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(read())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(read())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate()

    fun setPeriodicCheckEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(Constants.PREF_PERIODIC_CHECK_ENABLED, enabled) }
        if (enabled) {
            UpdatesCheckReceiver.scheduleRepeatingUpdatesCheck(context)
        } else {
            UpdatesCheckReceiver.cancelRepeatingUpdatesCheck(context)
            UpdatesCheckReceiver.cancelUpdatesCheck(context)
        }
    }

    fun setPeriodicCheckInterval(interval: CheckInterval) {
        prefs.edit { putString(CheckInterval.PREF_KEY, interval.value) }
        UpdatesCheckReceiver.updateRepeatingUpdatesCheck(context)
    }

    fun setAutoDeleteUpdates(enabled: Boolean) {
        prefs.edit { putBoolean(Constants.PREF_AUTO_DELETE_UPDATES, enabled) }
    }

    fun setMeteredNetworkWarning(enabled: Boolean) {
        prefs.edit { putBoolean(Constants.PREF_METERED_NETWORK_WARNING, enabled) }
    }

    fun setAbPerfMode(enabled: Boolean) {
        prefs.edit { putBoolean(Constants.PREF_AB_PERF_MODE, enabled) }
        try {
            UpdateEngine().setPerformanceMode(enabled)
        } catch (e: Exception) {
            Log.w(TAG, "setPerformanceMode not supported", e)
        }
    }

    fun setAbStreamingMode(enabled: Boolean) {
        prefs.edit { putBoolean(Constants.PREF_AB_STREAMING_MODE, enabled) }
    }

    fun setUpdateRecovery(enabled: Boolean) {
        DeviceInfoUtils.isRecoveryUpdateEnabled = enabled
        prefs.edit { putBoolean(Constants.PREF_UPDATE_RECOVERY, enabled) }
    }

    private fun read() = PreferencesData(
        periodicCheckEnabled = prefs.getBoolean(Constants.PREF_PERIODIC_CHECK_ENABLED, true),
        periodicCheckInterval = CheckInterval.fromValue(
            context, prefs.getString(CheckInterval.PREF_KEY, null)
        ),
        autoDeleteUpdates = prefs.getBoolean(Constants.PREF_AUTO_DELETE_UPDATES, true),
        meteredNetworkWarning = prefs.getBoolean(Constants.PREF_METERED_NETWORK_WARNING, true),
        abPerfMode = prefs.getBoolean(Constants.PREF_AB_PERF_MODE, false),
        abStreamingMode = prefs.getBoolean(Constants.PREF_AB_STREAMING_MODE, false),
        updateRecovery = prefs.getBoolean(
            Constants.PREF_UPDATE_RECOVERY, DeviceInfoUtils.isRecoveryUpdateEnabled
        ),
    )
}
