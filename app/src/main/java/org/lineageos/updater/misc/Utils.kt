/*
 * SPDX-FileCopyrightText: 2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.misc

import android.app.AlarmManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemProperties
import android.os.storage.StorageManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.getSystemService
import androidx.preference.PreferenceManager
import org.json.JSONException
import org.json.JSONObject
import org.lineageos.updater.R
import org.lineageos.updater.UpdatesDbHelper
import org.lineageos.updater.controller.UpdaterService
import org.lineageos.updater.model.Update
import org.lineageos.updater.model.UpdateBaseInfo
import org.lineageos.updater.model.UpdateInfo
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipFile
import androidx.core.content.edit

object Utils {
    private const val TAG = "Utils"

    @JvmStatic
    fun addToClipboard(
        context: Context,
        label: String,
        text: String,
        toastMessage: String
    ) {
        val clipboard = context.getSystemService<ClipboardManager>()
        val clip = ClipData.newPlainText(label, text)
        clipboard?.setPrimaryClip(clip)
        Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
    }

    @JvmStatic
    fun appendSequentialNumber(file: File): File {
        val name: String
        val extension: String
        val extensionPosition = file.name.lastIndexOf('.')
        if (extensionPosition > 0) {
            name = file.name.substring(0, extensionPosition)
            extension = file.name.substring(extensionPosition)
        } else {
            name = file.name
            extension = ""
        }
        val parent = file.parentFile
        for (i in 1 until Integer.MAX_VALUE) {
            val newFile = File(parent, "$name-$i$extension")
            if (!newFile.exists()) {
                return newFile
            }
        }
        throw IllegalStateException()
    }

    @JvmStatic
    fun canInstall(update: UpdateBaseInfo): Boolean {
        val allowMajorUpgrades = SystemProperties.getBoolean(
            Constants.PROP_ALLOW_MAJOR_UPGRADES, false
        )
        val isDowngradeAllowed = SystemProperties.getBoolean(
            Constants.PROP_UPDATER_ALLOW_DOWNGRADING, false
        )
        val currentBuildDate = SystemProperties.getLong(Constants.PROP_BUILD_DATE, 0)
        val currentVersion = SystemProperties.get(Constants.PROP_BUILD_VERSION)

        return (isDowngradeAllowed || update.timestamp > currentBuildDate) &&
                compareVersions(update.version, currentVersion, allowMajorUpgrades)
    }

    @JvmStatic
    @Throws(IOException::class, JSONException::class)
    fun checkForNewUpdates(oldJson: File, newJson: File): Boolean {
        val oldList = parseJson(oldJson, true)
        val newList = parseJson(newJson, true)
        val oldIds = oldList.map { it.downloadId }.toSet()
        return newList.any { !oldIds.contains(it.downloadId) }
    }

    @JvmStatic
    fun cleanupDownloadsDir(context: Context) {
        val downloadPath = getDownloadPath(context)
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)

        removeUncryptFiles(downloadPath)

        val buildTimestamp = SystemProperties.getLong(Constants.PROP_BUILD_DATE, 0)
        val prevTimestamp = preferences.getLong(Constants.PREF_INSTALL_OLD_TIMESTAMP, 0)
        val lastUpdatePath = preferences.getString(Constants.PREF_INSTALL_PACKAGE_PATH, null)
        val reinstalling = preferences.getBoolean(Constants.PREF_INSTALL_AGAIN, false)
        val deleteUpdates = preferences.getBoolean(Constants.PREF_AUTO_DELETE_UPDATES, false)
        if ((buildTimestamp != prevTimestamp || reinstalling) && deleteUpdates &&
            lastUpdatePath != null
        ) {
            val lastUpdate = File(lastUpdatePath)
            if (lastUpdate.exists()) {
                lastUpdate.delete()
                // Remove the pref not to delete the file if re-downloaded
                preferences.edit { remove(Constants.PREF_INSTALL_PACKAGE_PATH) }
            }
        }
        val downloadsCleanupDone = "cleanup_done"
        if (preferences.getBoolean(downloadsCleanupDone, false)) {
            return
        }
        Log.d(TAG, "Cleaning $downloadPath")
        if (!downloadPath.isDirectory) {
            return
        }
        val files = downloadPath.listFiles() ?: return

        // Ideally the database is empty when we get here
        val knownPaths = mutableListOf<String>()
        UpdatesDbHelper(context).use { dbHelper ->
            dbHelper.updates.forEach { knownPaths.add(it.file.absolutePath) }
        }
        for (file in files) {
            if (!knownPaths.contains(file.absolutePath)) {
                Log.d(TAG, "Deleting ${file.absolutePath}")
                file.delete()
            }
        }
        preferences.edit { putBoolean(downloadsCleanupDone, true) }
    }

    @JvmStatic
    fun getCachedUpdateList(context: Context) = File(context.cacheDir, "updates.json")

    @JvmStatic
    fun getChangelogURL(context: Context): String {
        val device = SystemProperties.get(
            Constants.PROP_NEXT_DEVICE,
            SystemProperties.get(Constants.PROP_DEVICE)
        )
        return context.getString(R.string.menu_changelog_url, device)
    }

    @JvmStatic
    fun getDownloadPath(context: Context) = File(context.getString(R.string.download_path))

    @JvmStatic
    fun getServerURL(context: Context): String {
        val incrementalVersion = SystemProperties.get(Constants.PROP_BUILD_VERSION_INCREMENTAL)
        val device = SystemProperties.get(
            Constants.PROP_NEXT_DEVICE,
            SystemProperties.get(Constants.PROP_DEVICE)
        )
        val type = SystemProperties.get(Constants.PROP_RELEASE_TYPE).lowercase(Locale.ROOT)
        val serverUrl = SystemProperties.get(Constants.PROP_UPDATER_URI).ifEmpty {
            context.getString(R.string.updater_server_url)
        }
        return serverUrl
            .replace("{device}", device)
            .replace("{type}", type)
            .replace("{incr}", incrementalVersion)
    }

    @JvmStatic
    fun getUpgradeBlockedURL(context: Context): String {
        val device = SystemProperties.get(
            Constants.PROP_NEXT_DEVICE,
            SystemProperties.get(Constants.PROP_DEVICE)
        )
        return context.getString(R.string.blocked_update_info_url, device)
    }

    @JvmStatic
    fun getUpdateCheckInterval(context: Context): Long {
        return when (getUpdateCheckSetting(context)) {
            AutoUpdatesCheckInterval.DAILY -> AlarmManager.INTERVAL_DAY
            AutoUpdatesCheckInterval.MONTHLY -> AlarmManager.INTERVAL_DAY * 30
            AutoUpdatesCheckInterval.NEVER -> 0L // Should not happen
            AutoUpdatesCheckInterval.WEEKLY -> AlarmManager.INTERVAL_DAY * 7
        }
    }

    @JvmStatic
    fun getUpdateCheckSetting(context: Context): AutoUpdatesCheckInterval {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val value = preferences.getInt(
            Constants.PREF_AUTO_UPDATES_CHECK_INTERVAL,
            AutoUpdatesCheckInterval.WEEKLY.value
        )
        return AutoUpdatesCheckInterval.entries.find { it.value == value }
            ?: AutoUpdatesCheckInterval.WEEKLY
    }

    @JvmStatic
    fun getZipEntryOffset(zipFile: ZipFile, entryPath: String): Long {
        // Each entry has a header of (30 + n + m) bytes
        // 'n' is the length of the file name
        // 'm' is the length of the extra field
        val fixedHeaderSize = 30
        var offset = 0L
        val zipEntries = zipFile.entries()
        while (zipEntries.hasMoreElements()) {
            val entry = zipEntries.nextElement()
            val n = entry.name.length
            val m = entry.extra?.size ?: 0
            val headerSize = fixedHeaderSize + n + m
            offset += headerSize
            if (entry.name == entryPath) {
                return offset
            }
            offset += entry.compressedSize
        }
        Log.e(TAG, "Entry $entryPath not found")
        throw IllegalArgumentException("The given entry was not found")
    }

    @JvmStatic
    fun hasTouchscreen(context: Context) =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)

    @JvmStatic
    fun isABDevice(): Boolean = SystemProperties.getBoolean(Constants.PROP_AB_DEVICE, false)

    @JvmStatic
    @Throws(IOException::class)
    fun isABUpdate(file: File): Boolean {
        return ZipFile(file).use { isABUpdate(it) }
    }

    @JvmStatic
    fun isABUpdate(zipFile: ZipFile): Boolean =
        zipFile.getEntry(Constants.AB_PAYLOAD_BIN_PATH) != null &&
                zipFile.getEntry(Constants.AB_PAYLOAD_PROPERTIES_PATH) != null

    @JvmStatic
    fun isCompatible(update: UpdateBaseInfo): Boolean {
        if (update.version < SystemProperties.get(Constants.PROP_BUILD_VERSION)) {
            Log.d(TAG, "${update.name} is older than current Android version")
            return false
        }
        if (!SystemProperties.getBoolean(Constants.PROP_UPDATER_ALLOW_DOWNGRADING, false) &&
            update.timestamp <= SystemProperties.getLong(Constants.PROP_BUILD_DATE, 0)
        ) {
            Log.d(TAG, "${update.name} is older than/equal to the current build")
            return false
        }
        if (!update.type.equals(SystemProperties.get(Constants.PROP_RELEASE_TYPE), true)) {
            Log.d(TAG, "${update.name} has type ${update.type}")
            return false
        }
        return true
    }

    @JvmStatic
    fun isEncrypted(context: Context, file: File): Boolean {
        val sm = context.getSystemService<StorageManager>()
        return sm?.isEncrypted(file) ?: false
    }

    @JvmStatic
    fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService<ConnectivityManager>()
        val activeNetwork = cm?.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    @JvmStatic
    fun isNetworkMetered(context: Context): Boolean {
        val cm = context.getSystemService<ConnectivityManager>()
        return cm?.isActiveNetworkMetered ?: false
    }

    @JvmStatic
    fun isRecoveryUpdateExecPresent() = File(Constants.UPDATE_RECOVERY_EXEC).exists()

    @JvmStatic
    fun isUpdateCheckEnabled(context: Context): Boolean {
        return getUpdateCheckSetting(context) != AutoUpdatesCheckInterval.NEVER
    }

    @JvmStatic
    @Throws(IOException::class, JSONException::class)
    fun parseJson(file: File, compatibleOnly: Boolean): List<UpdateInfo> {
        val updates = mutableListOf<UpdateInfo>()
        val json = file.readText()
        val obj = JSONObject(json)
        val updatesList = obj.getJSONArray("response")
        for (i in 0 until updatesList.length()) {
            if (updatesList.isNull(i)) {
                continue
            }
            try {
                val update = parseJsonUpdate(updatesList.getJSONObject(i))
                if (!compatibleOnly || isCompatible(update)) {
                    updates.add(update)
                } else {
                    Log.d(TAG, "Ignoring incompatible update ${update.name}")
                }
            } catch (e: JSONException) {
                Log.e(TAG, "Could not parse update object, index=$i", e)
            }
        }
        return updates
    }

    @JvmStatic
    fun removeUncryptFiles(downloadPath: File) {
        val uncryptFiles =
            downloadPath.listFiles { _, name -> name.endsWith(Constants.UNCRYPT_FILE_EXT) }
        uncryptFiles?.forEach { it.delete() }
    }

    @JvmStatic
    fun triggerUpdate(context: Context, downloadId: String) {
        val intent = Intent(context, UpdaterService::class.java).apply {
            action = UpdaterService.ACTION_INSTALL_UPDATE
            putExtra(UpdaterService.EXTRA_DOWNLOAD_ID, downloadId)
        }
        context.startService(intent)
    }

    private fun compareVersions(a: String, b: String, allowMajorUpgrades: Boolean): Boolean {
        return runCatching {
            val partsA = a.split('.').map { it.toInt() }
            val majorA = partsA[0]
            val minorA = partsA[1]

            val partsB = b.split('.').map { it.toInt() }
            val majorB = partsB[0]
            val minorB = partsB[1]

            // Return early and allow if we allow major version upgrades
            (allowMajorUpgrades && majorA > majorB) || (majorA == majorB && minorA >= minorB)
        }.getOrDefault(false)
    }

    // This should really return an UpdateBaseInfo object, but currently this only
    // used to initialize UpdateInfo objects
    @Throws(JSONException::class)
    private fun parseJsonUpdate(obj: JSONObject): UpdateInfo {
        return Update().apply {
            timestamp = obj.getLong("datetime")
            name = obj.getString("filename")
            downloadId = obj.getString("id")
            type = obj.getString("romtype")
            fileSize = obj.getLong("size")
            downloadUrl = obj.getString("url")
            version = obj.getString("version")
        }
    }
}
