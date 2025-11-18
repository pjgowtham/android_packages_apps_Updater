/*
 * SPDX-FileCopyrightText: 2017-2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.controller

import android.annotation.SuppressLint
import android.content.Context
import android.os.FileUtils
import android.os.RecoverySystem
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.lineageos.updater.misc.Constants
import org.lineageos.updater.misc.Utils
import org.lineageos.updater.model.UpdateStatus
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/*
 * Handles installing updates on non-A/B devices.
 */
class LegacyUpdateInstaller private constructor(
    context: Context,
    private val updaterController: UpdaterController
) : UpdateInstaller {

    private val context = context.applicationContext

    @Volatile
    private var canCancel = false
    private var prepareUpdateThread: Thread? = null

    override fun cancel() {
        if (!canCancel) {
            Log.d(TAG, "Nothing to cancel")
            return
        }
        prepareUpdateThread?.interrupt()
    }

    override fun install(downloadId: String) {
        if (isInstalling) {
            Log.e(TAG, "Already installing an update")
            return
        }

        val update = updaterController.getUpdate(downloadId) ?: return

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val buildTimestamp = SystemProperties.getLong(Constants.PROP_BUILD_DATE, 0)
        val lastBuildTimestamp =
            prefs.getLong(Constants.PREF_INSTALL_OLD_TIMESTAMP, buildTimestamp)
        val isReinstalling = buildTimestamp == lastBuildTimestamp

        prefs.edit {
            putLong(Constants.PREF_INSTALL_OLD_TIMESTAMP, buildTimestamp)
                .putLong(Constants.PREF_INSTALL_NEW_TIMESTAMP, update.timestamp)
                .putString(Constants.PREF_INSTALL_PACKAGE_PATH, update.file?.absolutePath)
                .putBoolean(Constants.PREF_INSTALL_AGAIN, isReinstalling)
                .putBoolean(Constants.PREF_INSTALL_NOTIFIED, false)
        }

        if (update.file != null && Utils.isEncrypted(context, update.file!!)) {
            prepareForUncryptAndInstall(downloadId, update.file!!)
        } else if (update.file != null) {
            installPackage(downloadId, update.file!!)
        } else {
            Log.e(TAG, "Update file is null, cannot install")
            updaterController.getActualUpdate(downloadId)
                ?.status = UpdateStatus.INSTALLATION_FAILED
            updaterController.notifyUpdateChange(downloadId)
        }
    }

    private fun installPackage(downloadId: String, updateFile: File) {
        try {
            RecoverySystem.installPackage(context, updateFile)
        } catch (e: IOException) {
            Log.e(TAG, "Could not install update", e)
            updaterController.getActualUpdate(downloadId)
                ?.status = UpdateStatus.INSTALLATION_FAILED
            updaterController.notifyUpdateChange(downloadId)
        }
    }

    /*
     * Creates an unencrypted copy of the update package and installs it.
     */
    @Synchronized
    private fun prepareForUncryptAndInstall(downloadId: String, updateFile: File) {
        val uncryptPath = updateFile.absolutePath + Constants.UNCRYPT_FILE_EXT
        val uncryptFile = File(uncryptPath)

        val copyRunnable = Runnable {
            try {
                canCancel = true

                FileInputStream(updateFile).use { input ->
                    FileOutputStream(uncryptFile).use { output ->
                        FileUtils.copy(input, output)
                    }
                }

                try {
                    val perms = setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.GROUP_READ,
                        PosixFilePermission.OTHERS_READ
                    )
                    Files.setPosixFilePermissions(uncryptFile.toPath(), perms)
                } catch (_: IOException) {
                    // Ignore permission setting failure
                }

                canCancel = false
                if (Thread.currentThread().isInterrupted) {
                    updaterController.getActualUpdate(downloadId)
                        ?.status = UpdateStatus.INSTALLATION_CANCELLED
                    if (!uncryptFile.delete()) {
                        Log.w(TAG, "Failed to delete uncrypt file: $uncryptFile")
                    }
                } else {
                    installPackage(downloadId, uncryptFile)
                }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to copy update for uncrypt install", e)
                if (!uncryptFile.delete()) {
                    Log.w(TAG, "Failed to delete uncrypt file: $uncryptFile")
                }
                updaterController.getActualUpdate(downloadId)
                    ?.status = UpdateStatus.INSTALLATION_FAILED
            } finally {
                synchronized(this) {
                    canCancel = false
                    prepareUpdateThread = null
                    installingUpdateId = null
                }
                updaterController.notifyUpdateChange(downloadId)
            }
        }

        prepareUpdateThread = Thread(copyRunnable)
        prepareUpdateThread?.start()
        installingUpdateId = downloadId
        canCancel = false

        updaterController.getActualUpdate(downloadId)?.status = UpdateStatus.INSTALLING
        updaterController.notifyUpdateChange(downloadId)
    }

    override fun reconnect() {
        // No-op for Legacy
    }

    override fun resume() {
        // No-op for Legacy
    }

    override fun setPerformanceMode(enable: Boolean) {
        // No-op for Legacy
    }

    override fun suspend() {
        // No-op for Legacy
    }

    companion object {
        private const val TAG = "LegacyUpdateInstaller"

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: LegacyUpdateInstaller? = null

        @Volatile
        var installingUpdateId: String? = null
            private set

        val isInstalling: Boolean
            get() = installingUpdateId != null

        @Synchronized
        fun getInstance(context: Context, controller: UpdaterController): LegacyUpdateInstaller {
            return instance ?: LegacyUpdateInstaller(context, controller).also { instance = it }
        }

        @Synchronized
        fun isInstalling(downloadId: String): Boolean {
            return installingUpdateId == downloadId
        }
    }
}
