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
import kotlinx.coroutines.withContext
import org.lineageos.updater.misc.Utils
import org.lineageos.updater.model.UpdateInfo
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Fetches, parses, and caches the list of available system updates. */
class UpdateCheckRepository(private val context: Context) {

    sealed interface FetchResult {
        /** The check completed successfully, possibly finding new updates. */
        data class Success(val updates: List<UpdateInfo>, val newUpdatesFound: Boolean) :
            FetchResult

        /** The check failed due to connectivity or server issues. */
        data object NetworkError : FetchResult

        /** The check failed because the server's response was malformed. */
        data object ParseError : FetchResult
    }

    /**
     * Get the cached list of updates.
     */
    suspend fun getCachedUpdates(): List<UpdateInfo> {
        val jsonFile = Utils.getCachedUpdateList(context)
        return if (jsonFile.exists()) {
            withContext(Dispatchers.IO) {
                runCatching {
                    Utils.parseJson(jsonFile, true)
                }.getOrElse {
                    Log.e(TAG, "Error parsing cached update list", it)
                    emptyList()
                }
            }
        } else {
            emptyList()
        }
    }

    /**
     * Get the timestamp of the last update check.
     */
    fun getLastCheckTimestamp(): Long {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getLong(KEY_LAST_UPDATE_CHECK, -1L)
    }

    /**
     * Suspend function to fetch updates from the server.
     *
     * @return [FetchResult] indicating success or specific failure type.
     */
    suspend fun fetchUpdates(): FetchResult {
        val jsonFile = Utils.getCachedUpdateList(context)
        val jsonFileTmp = File("${jsonFile.absolutePath}${UUID.randomUUID()}")
        val url = Utils.getServerURL(context)
        Log.d(TAG, "Checking $url")

        return try {
            downloadJson(url, jsonFileTmp)
            processNewJson(jsonFile, jsonFileTmp)
        } catch (e: IOException) {
            Log.e(TAG, "Could not download updates list", e)
            jsonFileTmp.delete()
            FetchResult.NetworkError
        }
    }

    /**
     * Download a small JSON file from [url] to [destination].
     * Runs on [Dispatchers.IO] and is cancellable at the coroutine level.
     */
    @Throws(IOException::class)
    private suspend fun downloadJson(url: String, destination: File) =
        withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                conn.connect()
                val responseCode = conn.responseCode
                if (responseCode !in 200..299) {
                    throw IOException("HTTP $responseCode")
                }
                conn.inputStream.use { input ->
                    destination.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } finally {
                conn.disconnect()
            }
        }

    /**
     * Process a freshly downloaded JSON: parse, persist last-check timestamp,
     * and handle periodic check scheduling.
     */
    private suspend fun processNewJson(
        existingJson: File,
        newJson: File,
    ): FetchResult = withContext(Dispatchers.IO) {
        runCatching {
            val updates = Utils.parseJson(newJson, true)

            PreferenceManager.getDefaultSharedPreferences(context).edit {
                putLong(KEY_LAST_UPDATE_CHECK, System.currentTimeMillis())
            }

            val newUpdatesFound = existingJson.exists() && Utils.checkForNewUpdates(existingJson, newJson)

            newJson.renameTo(existingJson)

            FetchResult.Success(updates, newUpdatesFound)
        }.getOrElse {
            Log.e(TAG, "Could not process downloaded json", it)
            newJson.delete()
            FetchResult.ParseError
        }
    companion object {
        private const val TAG = "UpdateCheckRepository"
        private const val TIMEOUT_MS = 5000
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
    }
}
