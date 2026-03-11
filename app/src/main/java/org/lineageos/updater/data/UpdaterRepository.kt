/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.data

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.lineageos.updater.misc.Utils
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "UpdaterRepository"
private const val CACHE_FILENAME = "updates.json"
private const val HTTP_TIMEOUT_MS = 10_000
private const val PREF_LAST_CHECK_TIMESTAMP = "last_check_timestamp"

/**
 * Single source of truth for OTA updates. Serves stale cache immediately for
 * instant UI, then replaces it with a fresh server response when network is available.
 */
class UpdaterRepository(private val context: Context) {

    private val cacheFile = File(context.cacheDir, CACHE_FILENAME)
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(context) }

    data class UpdateResult(
        val updates: List<Update>,
        val isStale: Boolean = false,
        val hasNewUpdates: Boolean = false,
    )

    /** Returns -1 if a check has never been performed. */
    fun getLastCheckTimestamp(): Long = prefs.getLong(PREF_LAST_CHECK_TIMESTAMP, -1L)

    fun getUpdates(): Flow<UpdateResult> = flow {
        val cached = readCache()

        if (cached.isNotEmpty()) emit(UpdateResult(updates = cached, isStale = true))

        if (Utils.isNetworkAvailable(context)) emit(fetchAndCache())
    }.flowOn(Dispatchers.IO)

    suspend fun fetchUpdates(): UpdateResult = withContext(Dispatchers.IO) {
        fetchAndCache()
    }

    private fun readCache(): List<Update> =
        cacheFile.takeIf { it.exists() }
            ?.runCatching { parseAndFilter(this) }
            ?.onFailure { Log.e(TAG, "Failed to read update cache", it) }
            ?.getOrNull() ?: emptyList()

    private suspend fun fetchAndCache(): UpdateResult {
        val oldIds = readCache().map { it.downloadId }.toSet()

        val json = fetchJson()
        Log.d(TAG, "Fetched update list from server")

        cacheFile.writeText(json)
        val updates = parseAndFilter(cacheFile)
        val hasNew = updates.any { it.downloadId !in oldIds }

        Log.d(TAG, "${updates.size} installable update(s) found")
        prefs.edit { putLong(PREF_LAST_CHECK_TIMESTAMP, System.currentTimeMillis()) }

        return UpdateResult(
            updates = updates,
            isStale = false,
            hasNewUpdates = hasNew,
        )
    }

    private fun fetchJson(): String {
        val url = Utils.getServerURL(context)
        Log.d(TAG, "Checking $url")

        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = HTTP_TIMEOUT_MS
        connection.readTimeout = HTTP_TIMEOUT_MS
        connection.requestMethod = "GET"

        try {
            connection.connect()
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "Unexpected response: HTTP ${connection.responseCode}"
            }
            return connection.inputStream.use { it.bufferedReader().readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseAndFilter(file: File): List<Update> =
        Utils.parseJson(file).filter { Utils.canInstall(it) && !Utils.isCurrentBuild(it) }
}
