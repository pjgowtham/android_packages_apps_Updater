/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.data

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.lineageos.updater.misc.Constants
import org.lineageos.updater.misc.DeviceInfoUtils

class PreferencesRepository(private val context: Context) {

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    fun getPeriodicCheckEnabled(): Boolean =
        prefs.getBoolean(PREF_PERIODIC_CHECK_ENABLED, true)

    fun setPeriodicCheckEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(PREF_PERIODIC_CHECK_ENABLED, enabled) }
    }

    fun getCheckInterval(): CheckInterval =
        CheckInterval.fromValue(prefs.getString(PREF_CHECK_INTERVAL, null))

    fun setCheckInterval(interval: CheckInterval) {
        prefs.edit { putString(PREF_CHECK_INTERVAL, interval.value) }
    }

    fun getAutoDelete(): Boolean =
        prefs.getBoolean(Constants.PREF_AUTO_DELETE_UPDATES, true)

    fun setAutoDelete(value: Boolean) =
        prefs.edit { putBoolean(Constants.PREF_AUTO_DELETE_UPDATES, value) }

    fun getMeteredWarning(): Boolean =
        prefs.getBoolean(Constants.PREF_METERED_NETWORK_WARNING, true)

    fun setMeteredWarning(value: Boolean) =
        prefs.edit { putBoolean(Constants.PREF_METERED_NETWORK_WARNING, value) }

    fun getAbPerfMode(): Boolean =
        prefs.getBoolean(Constants.PREF_AB_PERF_MODE, false)

    fun setAbPerfMode(value: Boolean) =
        prefs.edit { putBoolean(Constants.PREF_AB_PERF_MODE, value) }

    // Recovery update is stored as a system property via DeviceInfoUtils rather than in
    // SharedPreferences, as it controls system-level behavior outside the app's data partition.
    fun getRecoveryUpdate(): Boolean = DeviceInfoUtils.isRecoveryUpdateEnabled

    fun setRecoveryUpdate(value: Boolean) {
        DeviceInfoUtils.isRecoveryUpdateEnabled = value
    }

    companion object {
        private const val PREF_CHECK_INTERVAL = "check_interval"
        private const val PREF_PERIODIC_CHECK_ENABLED = "periodic_check_enabled"
    }
}
