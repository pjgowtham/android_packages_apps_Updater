/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.lineageos.updater.misc.DeviceInfoUtils

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "updater_prefs")

private val KEY_PERIODIC_CHECK_ENABLED = booleanPreferencesKey("periodic_check_enabled")
private val KEY_CHECK_INTERVAL = stringPreferencesKey("check_interval")
private val KEY_AUTO_DELETE = booleanPreferencesKey("auto_delete_updates")
private val KEY_METERED_WARNING = booleanPreferencesKey("metered_network_warning")
private val KEY_AB_PERF_MODE = booleanPreferencesKey("ab_perf_mode")

class PreferencesRepository(context: Context) {

    private val dataStore = context.applicationContext.dataStore

    val periodicCheckEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_PERIODIC_CHECK_ENABLED] ?: true
    }

    val checkIntervalFlow: Flow<CheckInterval> = dataStore.data.map { prefs ->
        CheckInterval.fromValue(prefs[KEY_CHECK_INTERVAL])
    }

    val autoDeleteFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_AUTO_DELETE] ?: true
    }

    val meteredNetworkWarningFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_METERED_WARNING] ?: true
    }

    val abPerfModeFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_AB_PERF_MODE] ?: false
    }

    // Stored as a system property via DeviceInfoUtils, not in DataStore.
    val recoveryUpdateEnabled: Boolean
        get() = DeviceInfoUtils.isRecoveryUpdateEnabled

    suspend fun isPeriodicUpdateCheckEnabled(): Boolean = periodicCheckEnabledFlow.first()

    suspend fun getCheckInterval(): CheckInterval = checkIntervalFlow.first()

    suspend fun setPeriodicCheckEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_PERIODIC_CHECK_ENABLED] = enabled }
    }

    suspend fun setCheckInterval(interval: CheckInterval) {
        dataStore.edit { it[KEY_CHECK_INTERVAL] = interval.value }
    }

    suspend fun setAutoDelete(value: Boolean) {
        dataStore.edit { it[KEY_AUTO_DELETE] = value }
    }

    suspend fun setMeteredNetworkWarning(value: Boolean) {
        dataStore.edit { it[KEY_METERED_WARNING] = value }
    }

    suspend fun setAbPerfMode(value: Boolean) {
        dataStore.edit { it[KEY_AB_PERF_MODE] = value }
    }

    fun setRecoveryUpdate(value: Boolean) {
        DeviceInfoUtils.isRecoveryUpdateEnabled = value
    }

    companion object {
        @JvmStatic
        fun getPeriodicCheckEnabledBlocking(context: Context): Boolean = runBlocking {
            PreferencesRepository(context).periodicCheckEnabledFlow.first()
        }

        @JvmStatic
        fun getCheckIntervalBlocking(context: Context): CheckInterval = runBlocking {
            PreferencesRepository(context).checkIntervalFlow.first()
        }

        @JvmStatic
        fun getAutoDeleteBlocking(context: Context): Boolean = runBlocking {
            PreferencesRepository(context).autoDeleteFlow.first()
        }

        @JvmStatic
        fun getMeteredNetworkWarningBlocking(context: Context): Boolean = runBlocking {
            PreferencesRepository(context).meteredNetworkWarningFlow.first()
        }

        @JvmStatic
        fun getAbPerfModeBlocking(context: Context): Boolean = runBlocking {
            PreferencesRepository(context).abPerfModeFlow.first()
        }
    }
}
