/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appStateDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_state")

private val KEY_LAST_CHECKED_TIMESTAMP = longPreferencesKey("last_checked_timestamp")

class AppStateRepository(context: Context) {

    private val dataStore = context.applicationContext.appStateDataStore

    val lastCheckedTimestampFlow: Flow<Long> = dataStore.data.map { prefs ->
        prefs[KEY_LAST_CHECKED_TIMESTAMP] ?: 0L
    }

    suspend fun setLastCheckedTimestamp(timestamp: Long) {
        dataStore.edit { prefs -> prefs[KEY_LAST_CHECKED_TIMESTAMP] = timestamp }
    }
}
