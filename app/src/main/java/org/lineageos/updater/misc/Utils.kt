/*
 * SPDX-FileCopyrightText: 2017-2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.misc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.SystemProperties
import android.os.storage.StorageManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.content.getSystemService
import androidx.preference.PreferenceManager
import org.json.JSONException
import org.json.JSONObject
import org.lineageos.updater.R
import org.lineageos.updater.UpdatesDatabase
import org.lineageos.updater.controller.UpdaterService
import org.lineageos.updater.model.Update
import org.lineageos.updater.model.UpdateBaseInfo
import org.lineageos.updater.model.UpdateInfo
import org.lineageos.updater.model.UpdateStatus
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

private const val TAG = "Utils"

object Utils {

    /*
     * Add a text string to the clipboard.
     */
    @JvmStatic
    fun addToClipboard(
        context: Context, label: String, text: String, toastMessage: String
    ) {/*
         * We can't use context.clipboardManager because it's API 23+
         */
        val clipboard = context.getSystemService<ClipboardManager>()
        val clip = ClipData.newPlainText(label, text)
        clipboard?.setPrimaryClip(clip)
        Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
    }

    /*
     * Appends a sequential number to a file name if it already exists.
     */
    @JvmStatic
    fun appendSequentialNumber(file: File): File {
        val name: String
        val extension: String
        val extensionPosition = file.name.lastIndexOf(".")
        if (extensionPosition > 0) {
            name = file.name.substring(0, extensionPosition)
            extension = file.name.substring(extensionPosition)
        } else {
            name = file.name
            extension = ""
        }
        val parent = file.parentFile
        for (i in 1 until Int.MAX_VALUE) {
            val newFile = File(parent, "$name-$i$extension")
            if (!newFile.exists()) {
                return newFile
            }
        }
        throw IllegalStateException()
    }

    /*
     * Get the build date timestamp from system properties.
     */
    @get:JvmStatic
    val buildDateTimestamp: Long
        get() = SystemProperties.getLong(Constants.PROP_BUILD_DATE, 0)

    /*
     * Get the build version from system properties.
     */
    @get:JvmStatic
    val buildVersion: String
        get() = SystemProperties.get(Constants.PROP_BUILD_VERSION)

    /*
     * Check if the device is compatible with a given update.
     */
    @JvmStatic
    fun canInstall(update: UpdateBaseInfo): Boolean {
        val allowMajorUpgrades = SystemProperties.getBoolean(
            Constants.PROP_ALLOW_MAJOR_UPGRADES, false
        )
        return (SystemProperties.getBoolean(
            Constants.PROP_UPDATER_ALLOW_DOWNGRADING,
            false
        ) || update.timestamp > buildDateTimestamp) && compareVersions(
            update.version, buildVersion, allowMajorUpgrades
        )
    }

    /*
     * Clean up the downloads directory, removing stale files.
     */
    @JvmStatic
    fun cleanupDownloadsDir(context: Context) {
        val downloadPath = getDownloadPath(context)
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)

        removeUncryptFiles(downloadPath)

        val buildTimestamp = buildDateTimestamp
        val prevTimestamp =
            preferences.getLong(Constants.PREF_INSTALL_OLD_TIMESTAMP, 0)
        val lastUpdatePath =
            preferences.getString(Constants.PREF_INSTALL_PACKAGE_PATH, null)
        val reinstalling =
            preferences.getBoolean(Constants.PREF_INSTALL_AGAIN, false)
        val deleteUpdates =
            preferences.getBoolean(Constants.PREF_AUTO_DELETE_UPDATES, false)
        if ((buildTimestamp !=
                    prevTimestamp || reinstalling) && deleteUpdates && lastUpdatePath != null
        ) {
            val lastUpdate = File(lastUpdatePath)
            if (lastUpdate.exists()) {
                lastUpdate.delete()/*
                 * Remove the pref not to delete the file if re-downloaded
                 */
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

        /*
         * Ideally the database is empty when we get here
         */
        val knownPaths = mutableListOf<String>()
        val dao = UpdatesDatabase.getInstance(context).updateDao()
        for (entity in dao.getUpdates()) {
            val update = UpdatesDatabase.toModel(entity)
            update.file?.let { knownPaths.add(it.absolutePath) }
        }
        for (file in files) {
            if (!knownPaths.contains(file.absolutePath)) {
                Log.d(TAG, "Deleting ${file.absolutePath}")
                file.delete()
            }
        }

        preferences.edit { putBoolean(downloadsCleanupDone, true) }
    }

    /*
     * Get the cached update list file.
     */
    @JvmStatic
    fun getCachedUpdateList(context: Context): File {
        return File(context.cacheDir, "updates.json")
    }

    /*
     * Get the changelog URL for the current device.
     */
    @JvmStatic
    fun getChangelogURL(context: Context): String {
        val device = SystemProperties.get(
            Constants.PROP_NEXT_DEVICE, SystemProperties.get(Constants.PROP_DEVICE)
        )
        return context.getString(R.string.menu_changelog_url, device)
    }

    /*
     * Get the download path for updates.
     */
    @JvmStatic
    fun getDownloadPath(context: Context): File {
        return File(context.getString(R.string.download_path))
    }

    /*
     * Get the URL for information on blocked updates.
     */
    @JvmStatic
    fun getUpgradeBlockedURL(context: Context): String {
        val device = SystemProperties.get(
            Constants.PROP_NEXT_DEVICE, SystemProperties.get(Constants.PROP_DEVICE)
        )
        return context.getString(R.string.blocked_update_info_url, device)
    }

    /*
     * Get the offset of a zip entry within a zip file.
     */
    @JvmStatic
    fun getZipEntryOffset(zipFile: ZipFile, entryPath: String): Long {/*
         * Each entry has an header of (30 + n + m) bytes
         * 'n' is the length of the file name
         * 'm' is the length of the extra field
         */
        val fixedHeaderSize = 30
        val zipEntries = zipFile.entries()
        var offset: Long = 0
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

    /*
     * Check if the device has a touchscreen.
     */
    @JvmStatic
    fun hasTouchscreen(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    /*
     * Check if the device is an A/B device.
     */
    @JvmStatic
    fun isABDevice(): Boolean {
        return SystemProperties.getBoolean(Constants.PROP_AB_DEVICE, false)
    }

    /*
     * Check if a zip file is an A/B update package.
     */
    @JvmStatic
    fun isABUpdate(zipFile: ZipFile): Boolean {
        return zipFile.getEntry(Constants.AB_PAYLOAD_BIN_PATH) != null
                && zipFile.getEntry(Constants.AB_PAYLOAD_PROPERTIES_PATH) != null
    }

    /*
     * Check if a file is an A/B update package.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun isABUpdate(file: File): Boolean {
        ZipFile(file).use { zipFile ->
            return isABUpdate(zipFile)
        }
    }

    /*
     * Check if an update is compatible with the current system.
     */
    fun isCompatible(update: UpdateBaseInfo): Boolean {
        if (update.version < buildVersion) {
            Log.d(TAG, "${update.name} is older than current Android version")
            return false
        }
        if (!SystemProperties.getBoolean(
                Constants.PROP_UPDATER_ALLOW_DOWNGRADING,
                false
            ) && update.timestamp <= buildDateTimestamp
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

    /*
     * Check if a file is encrypted.
     */
    @JvmStatic
    fun isEncrypted(context: Context, file: File): Boolean {
        val sm = context.getSystemService<StorageManager>()
        return sm?.isEncrypted(file) ?: false
    }

    /*
     * Check if the active network is metered.
     */
    @JvmStatic
    fun isNetworkMetered(context: Context): Boolean {
        val cm = context.getSystemService<ConnectivityManager>()
        return cm?.isActiveNetworkMetered ?: false
    }

    /*
     * Check if the recovery update executable is present.
     */
    @JvmStatic
    fun isRecoveryUpdateExecPresent(): Boolean {
        return File(Constants.UPDATE_RECOVERY_EXEC).exists()
    }

    /*
     * Parse a JSON file containing a list of updates.
     */
    @JvmStatic
    @Throws(IOException::class, JSONException::class)
    fun parseJson(file: File, compatibleOnly: Boolean): List<UpdateInfo> {
        val updates = mutableListOf<UpdateInfo>()
        val json = file.bufferedReader().use { it.readText() }

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

    /*
     * Remove all uncrypt files from the download path.
     */
    fun removeUncryptFiles(downloadPath: File) {
        val uncryptFiles = downloadPath.listFiles { _, name ->
            name.endsWith(Constants.UNCRYPT_FILE_EXT)
        }
        uncryptFiles?.forEach { it.delete() }
    }

    /*
     * Start the updater service to install an update.
     */
    @JvmStatic
    fun triggerUpdate(context: Context, downloadId: String) {
        val intent = Intent(context, UpdaterService::class.java)
        intent.action = UpdaterService.ACTION_INSTALL_UPDATE
        intent.putExtra(UpdaterService.EXTRA_DOWNLOAD_ID, downloadId)
        context.startService(intent)
    }

    /*
     * Compare two version strings.
     */
    private fun compareVersions(a: String, b: String, allowMajorUpgrades: Boolean): Boolean {
        return try {
            val partsA = a.split(".")
            val partsB = b.split(".")
            val majorA = partsA[0].toInt()
            val minorA = partsA[1].toInt()
            val majorB = partsB[0].toInt()
            val minorB = partsB[1].toInt()

            /*
             * Return early and allow if we allow major version upgrades
             */
            (allowMajorUpgrades && majorA > majorB) || (majorA == majorB && minorA >= minorB)
        } catch (_: Exception) {/*
             * Catches NumberFormatException and ArrayIndexOutOfBoundsException
             */
            false
        }
    }

    /*
     * Parse a single update from a JSONObject.
     */
    @Throws(JSONException::class)
    private fun parseJsonUpdate(obj: JSONObject): UpdateInfo {/*
         * This should really return an UpdateBaseInfo object, but currently this only
         * used to initialize UpdateInfo objects
         */
        return Update(
            downloadId = obj.getString("id"),
            downloadUrl = obj.getString("url"),
            fileSize = obj.getLong("size"),
            name = obj.getString("filename"),
            timestamp = obj.getLong("datetime"),
            type = obj.getString("romtype"),
            version = obj.getString("version"),
            availableOnline = false, /* This is set by the controller */
            eta = 0,
            file = null,
            installProgress = 0,
            isFinalizing = false,
            persistentStatus = UpdateStatus.Persistent.UNKNOWN,
            progress = 0,
            speed = 0,
            status = UpdateStatus.UNKNOWN
        )
    }
}
