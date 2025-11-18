/*
 * SPDX-FileCopyrightText: 2017-2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.controller

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import org.lineageos.updater.UpdatesDatabase
import org.lineageos.updater.download.DownloadClient
import org.lineageos.updater.misc.Utils
import org.lineageos.updater.model.Update
import org.lineageos.updater.model.UpdateInfo
import org.lineageos.updater.model.UpdateStatus
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Controller that manages the download and status of updates.
 * It acts as the central hub for update data and state management.
 */
class UpdaterController private constructor(context: Context) {

    private val updateDao = UpdatesDatabase.getInstance(context).updateDao()
    private val downloadRoot = Utils.getDownloadPath(context)
    private val context = context.applicationContext

    private val wakeLock: PowerManager.WakeLock

    private var activeDownloads = 0
    private val verifyingUpdates = Collections.synchronizedSet(HashSet<String>())
    private val downloads = ConcurrentHashMap<String, DownloadEntry>()

    init {
        val powerManager = context.getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Updater:wakelock")
        wakeLock.setReferenceCounted(false)

        Thread {
            Utils.cleanupDownloadsDir(context)
            for (entity in updateDao.getUpdates()) {
                addUpdate(UpdatesDatabase.toModel(entity), false)
            }
        }.start()
    }

    private class DownloadEntry(val update: Update) {
        var downloadClient: DownloadClient? = null
    }

    @JvmOverloads
    fun addUpdate(updateInfo: UpdateInfo, availableOnline: Boolean = true): Boolean {
        Log.d(TAG, "Adding download: ${updateInfo.downloadId}")
        if (downloads.containsKey(updateInfo.downloadId)) {
            Log.d(TAG, "Download (${updateInfo.downloadId}) already added")
            val entry = downloads[updateInfo.downloadId]
            if (entry != null) {
                val updateAdded = entry.update
                updateAdded.availableOnline = availableOnline && updateAdded.availableOnline
                updateAdded.downloadUrl = updateInfo.downloadUrl
            }
            return false
        }

        val update = Update.from(updateInfo)
        if (!fixUpdateStatus(update) && !availableOnline) {
            update.persistentStatus = UpdateStatus.Persistent.UNKNOWN
            deleteUpdateAsync(update)
            Log.d(TAG, "${update.downloadId} had an invalid status and is not online")
            return false
        }
        update.availableOnline = availableOnline
        downloads[update.downloadId] = DownloadEntry(update)
        return true
    }

    private fun addDownloadClient(entry: DownloadEntry, downloadClient: DownloadClient) {
        if (entry.downloadClient != null) {
            return
        }
        entry.downloadClient = downloadClient
        activeDownloads++
    }

    @SuppressLint("WakelockTimeout")
    fun startDownload(downloadId: String) {
        Log.d(TAG, "Starting $downloadId")
        if (!downloads.containsKey(downloadId) || isDownloading(downloadId)) {
            return
        }
        val entry = downloads[downloadId] ?: run {
            Log.e(TAG, "Could not get download entry")
            return
        }
        val update = entry.update
        var destination = File(downloadRoot, update.name)
        if (destination.exists()) {
            destination = Utils.appendSequentialNumber(destination)
            Log.d(TAG, "Changing name with ${destination.name}")
        }
        update.file = destination
        val downloadClient: DownloadClient
        try {
            downloadClient = DownloadClient.Builder()
                .setUrl(update.downloadUrl)
                .setDestination(update.file!!)
                .setDownloadCallback(getDownloadCallback(downloadId))
                .setProgressListener(getProgressListener(downloadId))
                .setUseDuplicateLinks(true)
                .build()
        } catch (_: IOException) {
            Log.e(TAG, "Could not build download client")
            update.status = UpdateStatus.PAUSED_ERROR
            notifyUpdateChange(downloadId)
            return
        }
        addDownloadClient(entry, downloadClient)
        update.status = UpdateStatus.STARTING
        notifyUpdateChange(downloadId)
        downloadClient.start()
        wakeLock.acquire()
    }

    @SuppressLint("WakelockTimeout")
    fun resumeDownload(downloadId: String) {
        Log.d(TAG, "Resuming $downloadId")
        if (!downloads.containsKey(downloadId) || isDownloading(downloadId)) {
            return
        }
        val entry = downloads[downloadId] ?: run {
            Log.e(TAG, "Could not get download entry")
            return
        }
        val update = entry.update
        val file = update.file
        if (file == null || !file.exists()) {
            Log.e(TAG, "The destination file of $downloadId doesn't exist, can't resume")
            update.status = UpdateStatus.PAUSED_ERROR
            notifyUpdateChange(downloadId)
            return
        }
        if (file.exists() && update.fileSize > 0 && file.length() >= update.fileSize) {
            Log.d(TAG, "File already downloaded, starting verification")
            update.status = UpdateStatus.VERIFYING
            verifyUpdateAsync(downloadId)
            notifyUpdateChange(downloadId)
        } else {
            val downloadClient: DownloadClient
            try {
                downloadClient = DownloadClient.Builder()
                    .setUrl(update.downloadUrl)
                    .setDestination(update.file!!)
                    .setDownloadCallback(getDownloadCallback(downloadId))
                    .setProgressListener(getProgressListener(downloadId))
                    .setUseDuplicateLinks(true)
                    .build()
            } catch (_: IOException) {
                Log.e(TAG, "Could not build download client")
                update.status = UpdateStatus.PAUSED_ERROR
                notifyUpdateChange(downloadId)
                return
            }
            addDownloadClient(entry, downloadClient)
            update.status = UpdateStatus.STARTING
            notifyUpdateChange(downloadId)
            downloadClient.resume()
            wakeLock.acquire()
        }
    }

    fun pauseDownload(downloadId: String) {
        Log.d(TAG, "Pausing $downloadId")
        if (!isDownloading(downloadId)) {
            return
        }

        val entry = downloads[downloadId]
        if (entry != null) {
            entry.downloadClient?.cancel()
            removeDownloadClient(entry)
            entry.update.status = UpdateStatus.PAUSED
            entry.update.eta = 0
            entry.update.speed = 0
            notifyUpdateChange(downloadId)
        }
    }

    fun deleteUpdate(downloadId: String) {
        Log.d(TAG, "Deleting update: $downloadId")
        if (!downloads.containsKey(downloadId) || isDownloading(downloadId)) {
            return
        }
        val entry = downloads[downloadId]
        if (entry != null) {
            val update = entry.update
            update.status = UpdateStatus.DELETED
            update.progress = 0
            update.persistentStatus = UpdateStatus.Persistent.UNKNOWN
            deleteUpdateAsync(update)

            val isLocalUpdate = Update.LOCAL_ID == downloadId
            if (!isLocalUpdate && !update.availableOnline) {
                Log.d(TAG, "Download no longer available online, removing")
                downloads.remove(downloadId)
                notifyUpdateDelete(downloadId)
            } else {
                notifyUpdateChange(downloadId)
            }
        }
    }

    fun getUpdates(): List<UpdateInfo> {
        return ArrayList(downloads.values.map { it.update })
    }

    fun getUpdate(downloadId: String?): UpdateInfo? {
        return downloads[downloadId]?.update
    }

    fun getActualUpdate(downloadId: String): Update? {
        return downloads[downloadId]?.update
    }

    fun isDownloading(downloadId: String): Boolean {
        return downloads[downloadId]?.downloadClient != null
    }

    fun hasActiveDownloads(): Boolean {
        return activeDownloads > 0
    }

    fun isVerifyingUpdate(): Boolean {
        return verifyingUpdates.isNotEmpty()
    }

    fun isVerifyingUpdate(downloadId: String): Boolean {
        return verifyingUpdates.contains(downloadId)
    }

    fun isInstallingUpdate(): Boolean {
        return LegacyUpdateInstaller.isInstalling ||
                ABUpdateInstaller.isInstallingUpdate(context)
    }

    fun isInstallingUpdate(downloadId: String): Boolean {
        return LegacyUpdateInstaller.isInstalling(downloadId) ||
                ABUpdateInstaller.isInstallingUpdate(context, downloadId)
    }

    fun isInstallingABUpdate(): Boolean {
        return ABUpdateInstaller.isInstallingUpdate(context)
    }

    fun isWaitingForReboot(downloadId: String): Boolean {
        return ABUpdateInstaller.isWaitingForReboot(context, downloadId)
    }

    fun setPerformanceMode(enable: Boolean) {
        if (!Utils.isABDevice()) {
            return
        }
        ABUpdateInstaller.getInstance(context, this).setPerformanceMode(enable)
    }

    fun setUpdatesAvailableOnline(downloadIds: List<String>, purgeList: Boolean) {
        val toRemove = ArrayList<String>()
        for (entry in downloads.values) {
            val online = downloadIds.contains(entry.update.downloadId)
            entry.update.availableOnline = online
            if (!online && purgeList &&
                entry.update.persistentStatus == UpdateStatus.Persistent.UNKNOWN
            ) {
                toRemove.add(entry.update.downloadId)
            }
        }
        for (downloadId in toRemove) {
            Log.d(TAG, "$downloadId no longer available online, removing")
            downloads.remove(downloadId)
            notifyUpdateDelete(downloadId)
        }
    }

    internal fun notifyUpdateChange(downloadId: String) {
        val intent = Intent()
        intent.action = ACTION_UPDATE_STATUS
        intent.putExtra(EXTRA_DOWNLOAD_ID, downloadId)
        context.sendBroadcast(intent)
    }

    internal fun notifyUpdateDelete(downloadId: String) {
        val intent = Intent()
        intent.action = ACTION_UPDATE_REMOVED
        intent.putExtra(EXTRA_DOWNLOAD_ID, downloadId)
        context.sendBroadcast(intent)
    }

    internal fun notifyDownloadProgress(downloadId: String) {
        val intent = Intent()
        intent.action = ACTION_DOWNLOAD_PROGRESS
        intent.putExtra(EXTRA_DOWNLOAD_ID, downloadId)
        context.sendBroadcast(intent)
    }

    internal fun notifyInstallProgress(downloadId: String) {
        val intent = Intent()
        intent.action = ACTION_INSTALL_PROGRESS
        intent.putExtra(EXTRA_DOWNLOAD_ID, downloadId)
        context.sendBroadcast(intent)
    }

    private fun deleteUpdateAsync(update: Update) {
        Thread {
            val file = update.file
            if (file != null && file.exists() && !file.delete()) {
                Log.e(TAG, "Could not delete ${file.absolutePath}")
            }
            updateDao.removeUpdate(update.downloadId)
        }.start()
    }

    private fun fixUpdateStatus(update: Update): Boolean {
        when (update.persistentStatus) {
            UpdateStatus.Persistent.VERIFIED, UpdateStatus.Persistent.INCOMPLETE -> {
                if (update.file == null || !update.file!!.exists()) {
                    update.status = UpdateStatus.UNKNOWN
                    return false
                } else if (update.fileSize > 0) {
                    update.status = UpdateStatus.PAUSED
                    val progress = (update.file!!.length() * 100f / update.fileSize).roundToInt()
                    update.progress = progress
                }
            }
        }
        return true
    }

    private fun getDownloadCallback(downloadId: String): DownloadClient.DownloadCallback {
        return object : DownloadClient.DownloadCallback {
            override fun onResponse(headers: okhttp3.Headers) {
                val entry = downloads[downloadId] ?: return
                val update = entry.update
                val contentLength = headers["Content-Length"]
                if (contentLength != null) {
                    try {
                        val size = contentLength.toLong()
                        if (update.fileSize < size) {
                            update.fileSize = size
                        }
                    } catch (_: NumberFormatException) {
                        Log.e(TAG, "Could not get content-length")
                    }
                }
                update.status = UpdateStatus.DOWNLOADING
                update.persistentStatus = UpdateStatus.Persistent.INCOMPLETE
                Thread {
                    updateDao.addUpdate(UpdatesDatabase.toEntity(update))
                }.start()
                notifyUpdateChange(downloadId)
            }

            override fun onSuccess() {
                Log.d(TAG, "Download complete")
                val entry = downloads[downloadId]
                if (entry != null) {
                    val update = entry.update
                    update.status = UpdateStatus.VERIFYING
                    removeDownloadClient(entry)
                    verifyUpdateAsync(downloadId)
                    notifyUpdateChange(downloadId)
                    tryReleaseWakelock()
                }
            }

            override fun onFailure(cancelled: Boolean) {
                if (cancelled) {
                    Log.d(TAG, "Download cancelled")
                    // Already notified
                } else {
                    val entry = downloads[downloadId]
                    if (entry != null) {
                        val update = entry.update
                        Log.e(TAG, "Download failed")
                        removeDownloadClient(entry)
                        update.status = UpdateStatus.PAUSED_ERROR
                        notifyUpdateChange(downloadId)
                    }
                }
                tryReleaseWakelock()
            }
        }
    }

    private fun getProgressListener(downloadId: String): DownloadClient.ProgressListener {
        return object : DownloadClient.ProgressListener {
            private var lastUpdate: Long = 0
            private var currentProgress = 0

            override fun update(bytesRead: Long, contentLength: Long, speed: Long, eta: Long) {
                var finalContentLength = contentLength
                val entry = downloads[downloadId] ?: return
                val update = entry.update
                if (finalContentLength <= 0) {
                    if (update.fileSize <= 0) {
                        return
                    } else {
                        finalContentLength = update.fileSize
                    }
                }
                if (finalContentLength <= 0) {
                    return
                }
                val now = SystemClock.elapsedRealtime()
                val progress = (bytesRead * 100f / finalContentLength).roundToInt()
                if (progress != currentProgress || now - lastUpdate > MAX_REPORT_INTERVAL_MS) {
                    currentProgress = progress
                    lastUpdate = now
                    update.progress = progress
                    update.eta = eta
                    update.speed = speed
                    notifyDownloadProgress(downloadId)
                }
            }
        }
    }

    private fun removeDownloadClient(entry: DownloadEntry) {
        if (entry.downloadClient == null) {
            return
        }
        entry.downloadClient = null
        activeDownloads--
    }

    private fun tryReleaseWakelock() {
        if (!hasActiveDownloads()) {
            wakeLock.release()
        }
    }

    @SuppressLint("SetWorldReadable")
    private fun verifyUpdateAsync(downloadId: String) {
        verifyingUpdates.add(downloadId)
        Thread {
            val entry = downloads[downloadId]
            if (entry != null) {
                val update = entry.update
                val file = update.file
                if (file != null && file.exists() && verifyPackage(file)) {
                    file.setReadable(true, false)
                    update.persistentStatus = UpdateStatus.Persistent.VERIFIED
                    updateDao.changeUpdateStatus(
                        update.downloadId,
                        update.persistentStatus
                    )
                    update.status = UpdateStatus.VERIFIED
                } else {
                    update.persistentStatus = UpdateStatus.Persistent.UNKNOWN
                    updateDao.removeUpdate(downloadId)
                    update.progress = 0
                    update.status = UpdateStatus.VERIFICATION_FAILED
                }
                verifyingUpdates.remove(downloadId)
                notifyUpdateChange(downloadId)
            }
        }.start()
    }

    private fun verifyPackage(file: File): Boolean {
        return try {
            android.os.RecoverySystem.verifyPackage(file, null, null)
            Log.e(TAG, "Verification successful")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Verification failed", e)
            if (file.exists()) {
                file.delete()
            } else {
                // The download was probably stopped. Exit silently
                Log.e(TAG, "Error while verifying the file", e)
            }
            false
        }
    }

    companion object {
        const val ACTION_DOWNLOAD_PROGRESS = "action_download_progress"
        const val ACTION_INSTALL_PROGRESS = "action_install_progress"
        const val ACTION_UPDATE_REMOVED = "action_update_removed"
        const val ACTION_UPDATE_STATUS = "action_update_status_change"
        const val EXTRA_DOWNLOAD_ID = "extra_download_id"

        private const val TAG = "UpdaterController"
        private const val MAX_REPORT_INTERVAL_MS = 1000

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: UpdaterController? = null

        @JvmStatic
        @Synchronized
        fun getInstance(context: Context): UpdaterController {
            return instance ?: UpdaterController(context).also { instance = it }
        }
    }
}
