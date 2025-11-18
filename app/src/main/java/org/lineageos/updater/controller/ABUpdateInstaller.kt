/*
 * SPDX-FileCopyrightText: 2017-2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.controller

import android.annotation.SuppressLint
import android.content.Context
import android.os.ServiceSpecificException
import android.os.UpdateEngine
import android.os.UpdateEngineCallback
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.lineageos.updater.misc.Constants
import org.lineageos.updater.misc.Utils
import org.lineageos.updater.model.UpdateStatus
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.zip.ZipFile
import kotlin.math.roundToInt

/*
 * Handles installing updates on A/B devices.
 */
class ABUpdateInstaller private constructor(
    context: Context, private val updaterController: UpdaterController
) : UpdateInstaller {

    private val context = context.applicationContext
    private val updateEngine = UpdateEngine()

    private var bound = false
    private var downloadId: String? = null
    private var finalizing = false
    private var progress = 0

    private val updateEngineCallback = object : UpdateEngineCallback() {
        override fun onStatusUpdate(status: Int, percent: Float) {
            val currentId = downloadId ?: return
            val update = updaterController.getActualUpdate(currentId)
            if (update == null) {
                // We read the id from a preference, the update could no longer exist
                installationDone(status == UpdateEngine.UpdateStatusConstants.UPDATED_NEED_REBOOT)
                return
            }

            when (status) {
                UpdateEngine.UpdateStatusConstants.DOWNLOADING, UpdateEngine.UpdateStatusConstants.FINALIZING -> {
                    if (update.status != UpdateStatus.INSTALLING) {
                        update.status = UpdateStatus.INSTALLING
                        updaterController.notifyUpdateChange(currentId)
                    }
                    progress = (percent * 100).roundToInt()
                    update.installProgress = progress
                    finalizing = status == UpdateEngine.UpdateStatusConstants.FINALIZING
                    update.isFinalizing = finalizing
                    updaterController.notifyInstallProgress(currentId)
                }

                UpdateEngine.UpdateStatusConstants.UPDATED_NEED_REBOOT -> {
                    installationDone(true)
                    update.installProgress = 0
                    update.status = UpdateStatus.INSTALLED
                    updaterController.notifyUpdateChange(currentId)
                }

                UpdateEngine.UpdateStatusConstants.IDLE -> {
                    // The service was restarted because we thought we were installing,
                    // but we aren't, so clear everything.
                    installationDone(false)
                }
            }
        }

        override fun onPayloadApplicationComplete(errorCode: Int) {
            if (errorCode != UpdateEngine.ErrorCodeConstants.SUCCESS) {
                installationDone(false)
                val currentId = downloadId ?: return
                // Fix: Handle nullable update object safely
                val update = updaterController.getActualUpdate(currentId) ?: return
                update.installProgress = 0
                update.status = UpdateStatus.INSTALLATION_FAILED
                updaterController.notifyUpdateChange(currentId)
            }
        }
    }

    override fun cancel() {
        if (!isInstallingUpdate(context)) {
            Log.e(TAG, "cancel: Not installing any update")
            return
        }

        if (!bound) {
            Log.e(TAG, "Not connected to update engine")
            return
        }

        updateEngine.cancel()
        installationDone(false)

        downloadId?.let {
            updaterController.getActualUpdate(it)?.status = UpdateStatus.INSTALLATION_CANCELLED
            updaterController.notifyUpdateChange(it)
        }
    }

    fun cleanup() {
        val errorCode = updateEngine.cleanupAppliedPayload()
        if (errorCode == UpdateEngine.ErrorCodeConstants.SUCCESS) {
            Log.i(TAG, "A/B payload cleanup successful")
        } else {
            Log.e(TAG, "A/B payload cleanup failed: $errorCode")
        }
    }

    override fun install(downloadId: String) {
        if (isInstallingUpdate(context)) {
            Log.e(TAG, "Already installing an update")
            return
        }

        this.downloadId = downloadId

        // Fix: Explicit null checks rather than silent return
        val update = updaterController.getActualUpdate(downloadId)
        if (update == null) {
            Log.e(TAG, "Update not found: $downloadId")
            return
        }

        if (update.file == null || !update.file!!.exists()) {
            Log.e(TAG, "The given update file doesn't exist")
            update.status = UpdateStatus.INSTALLATION_FAILED
            updaterController.notifyUpdateChange(downloadId)
            return
        }

        install(update.file!!, downloadId)
    }

    private fun install(file: File, downloadId: String) {
        val offset: Long
        val headerKeyValuePairs: Array<String>
        try {
            ZipFile(file).use { zipFile ->
                offset = Utils.getZipEntryOffset(zipFile, Constants.AB_PAYLOAD_BIN_PATH)
                val payloadPropEntry = zipFile.getEntry(Constants.AB_PAYLOAD_PROPERTIES_PATH)
                zipFile.getInputStream(payloadPropEntry).use { isStream ->
                    InputStreamReader(isStream).use { isr ->
                        BufferedReader(isr).use { br ->
                            headerKeyValuePairs = br.readLines().toTypedArray()
                        }
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Could not prepare $file", e)
            updaterController.getActualUpdate(downloadId)?.status = UpdateStatus.INSTALLATION_FAILED
            updaterController.notifyUpdateChange(downloadId)
            return
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Could not prepare $file", e)
            updaterController.getActualUpdate(downloadId)?.status = UpdateStatus.INSTALLATION_FAILED
            updaterController.notifyUpdateChange(downloadId)
            return
        }

        if (!bound) {
            bound = updateEngine.bind(updateEngineCallback)
            if (!bound) {
                Log.e(TAG, "Could not bind")
                updaterController.getActualUpdate(downloadId)?.status =
                    UpdateStatus.INSTALLATION_FAILED
                updaterController.notifyUpdateChange(downloadId)
                return
            }
        }

        val enableABPerfMode = PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(Constants.PREF_AB_PERF_MODE, false)
        updateEngine.setPerformanceMode(enableABPerfMode)

        val zipFileUri = "file://${file.absolutePath}"
        try {
            updateEngine.applyPayload(zipFileUri, offset, 0, headerKeyValuePairs)
        } catch (e: ServiceSpecificException) {
            if (e.errorCode == UpdateEngine.ErrorCodeConstants.PAYLOAD_TIMESTAMP_ERROR) {
                installationDone(true)
                updaterController.getActualUpdate(downloadId)?.status = UpdateStatus.INSTALLED
                updaterController.notifyUpdateChange(downloadId)
                return
            }
            throw e
        }

        updaterController.getActualUpdate(downloadId)?.status = UpdateStatus.INSTALLING
        updaterController.notifyUpdateChange(downloadId)

        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(PREF_INSTALLING_AB_ID, downloadId)
        }
    }

    private fun installationDone(needsReboot: Boolean) {
        val id = if (needsReboot) downloadId else null
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(Constants.PREF_NEEDS_REBOOT_ID, id).remove(PREF_INSTALLING_AB_ID)
        }
    }

    override fun reconnect() {
        if (!isInstallingUpdate(context)) {
            Log.e(TAG, "reconnect: Not installing any update")
            return
        }

        if (bound) {
            return
        }

        downloadId = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_INSTALLING_AB_ID, null)

        // We will get a status notification as soon as we are connected
        bound = updateEngine.bind(updateEngineCallback)
        if (!bound) {
            Log.e(TAG, "Could not bind")
        }
    }

    override fun resume() {
        if (!isInstallingUpdateSuspended(context)) {
            Log.e(TAG, "resume: No update is suspended")
            return
        }

        if (!bound) {
            Log.e(TAG, "Not connected to update engine")
            return
        }

        updateEngine.resume()

        val currentId = downloadId ?: return
        val update = updaterController.getActualUpdate(currentId) ?: return
        update.status = UpdateStatus.INSTALLING
        updaterController.notifyUpdateChange(currentId)
        update.installProgress = progress
        update.isFinalizing = finalizing
        updaterController.notifyInstallProgress(currentId)

        PreferenceManager.getDefaultSharedPreferences(context).edit {
            remove(PREF_INSTALLING_SUSPENDED_AB_ID)
        }
    }

    override fun setPerformanceMode(enable: Boolean) {
        updateEngine.setPerformanceMode(enable)
    }

    override fun suspend() {
        if (!isInstallingUpdate(context)) {
            Log.e(TAG, "suspend: Not installing any update")
            return
        }

        if (!bound) {
            Log.e(TAG, "Not connected to update engine")
            return
        }

        updateEngine.suspend()

        val currentId = downloadId ?: return
        updaterController.getActualUpdate(currentId)?.status = UpdateStatus.INSTALLATION_SUSPENDED
        updaterController.notifyUpdateChange(currentId)

        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(PREF_INSTALLING_SUSPENDED_AB_ID, currentId)
        }
    }

    companion object {
        private const val TAG = "ABUpdateInstaller"
        private const val PREF_INSTALLING_AB_ID = "installing_ab_id"
        private const val PREF_INSTALLING_SUSPENDED_AB_ID = "installing_suspended_ab_id"

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: ABUpdateInstaller? = null

        @Synchronized
        fun getInstance(context: Context, controller: UpdaterController): ABUpdateInstaller {
            return instance ?: ABUpdateInstaller(context, controller).also { instance = it }
        }

        @Synchronized
        fun isInstallingUpdate(context: Context): Boolean {
            val pref = PreferenceManager.getDefaultSharedPreferences(context)
            return pref.getString(
                PREF_INSTALLING_AB_ID,
                null
            ) != null || pref.getString(Constants.PREF_NEEDS_REBOOT_ID, null) != null
        }

        @Synchronized
        fun isInstallingUpdate(context: Context, downloadId: String): Boolean {
            val pref = PreferenceManager.getDefaultSharedPreferences(context)
            return downloadId == pref.getString(
                PREF_INSTALLING_AB_ID,
                null
            ) || downloadId == pref.getString(Constants.PREF_NEEDS_REBOOT_ID, null)
        }

        @Synchronized
        fun isInstallingUpdateSuspended(context: Context): Boolean {
            val pref = PreferenceManager.getDefaultSharedPreferences(context)
            return pref.getString(PREF_INSTALLING_SUSPENDED_AB_ID, null) != null
        }

        @Synchronized
        fun isWaitingForReboot(context: Context, downloadId: String): Boolean {
            val waitingId = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(Constants.PREF_NEEDS_REBOOT_ID, null)
            return waitingId == downloadId
        }
    }
}
