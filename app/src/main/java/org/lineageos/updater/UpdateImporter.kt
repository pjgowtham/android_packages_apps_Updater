/*
 * SPDX-FileCopyrightText: 2017-2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

/* This file handles importing a local update ZIP file. */
package org.lineageos.updater

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.lineageos.updater.controller.UpdaterController
import org.lineageos.updater.misc.Utils
import org.lineageos.updater.model.Update
import org.lineageos.updater.model.UpdateStatus
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipFile
import kotlin.concurrent.thread

class UpdateImporter(
    private val activity: Activity,
    private val callbacks: Callbacks
) {

    private var workingThread: Thread? = null

    fun stopImport() {
        if (workingThread?.isAlive == true) {
            workingThread?.interrupt()
            workingThread = null
        }
    }

    fun openImportPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(MIME_ZIP)
        activity.startActivityForResult(intent, REQUEST_PICK)
    }

    fun onResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (resultCode != Activity.RESULT_OK || requestCode != REQUEST_PICK) {
            return false
        }
        data?.data?.let {
            return onPicked(it)
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun onPicked(uri: Uri): Boolean {
        callbacks.onImportStarted()

        workingThread = thread {
            var importedFile: File? = null
            try {
                importedFile = importFile(uri)
                verifyPackage(importedFile)

                val update = buildLocalUpdate(importedFile)
                addUpdate(update)
                activity.runOnUiThread { callbacks.onImportCompleted(update) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to import update package", e)
                // Do not store invalid update
                importedFile?.delete()
                activity.runOnUiThread { callbacks.onImportCompleted(null) }
            }
        }
        return true
    }

    @SuppressLint("SetWorldReadable")
    @Throws(IOException::class)
    private fun importFile(uri: Uri): File {
        val parcelDescriptor = activity.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("Failed to obtain fileDescriptor")

        val outFile = File(Utils.getDownloadPath(activity), FILE_NAME)
        if (outFile.exists()) {
            outFile.delete()
        }

        parcelDescriptor.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { iStream ->
                FileOutputStream(outFile).use { oStream ->
                    iStream.copyTo(oStream)
                }
            }
        }

        outFile.setReadable(true, false)
        return outFile
    }

    private fun buildLocalUpdate(file: File): Update {
        val timeStamp = getTimeStamp(file)
        val name = activity.getString(R.string.local_update_name)
        return Update(
            downloadId = Update.LOCAL_ID,
            downloadUrl = "",
            fileSize = file.length(),
            name = name,
            timestamp = timeStamp,
            type = name, // Not relevant for local updates
            version = name,
            availableOnline = false,
            eta = 0,
            file = file,
            installProgress = 0,
            isFinalizing = false,
            persistentStatus = UpdateStatus.Persistent.VERIFIED,
            progress = 0,
            speed = 0,
            status = UpdateStatus.VERIFIED
        )
    }

    @Throws(Exception::class)
    @Suppress("DEPRECATION")
    private fun verifyPackage(file: File) {
        try {
            android.os.RecoverySystem.verifyPackage(file, null, null)
        } catch (e: Exception) {
            if (file.exists()) {
                file.delete()
                throw Exception("Verification failed, file has been deleted", e)
            } else {
                throw e
            }
        }
    }

    private fun addUpdate(update: Update) {
        val controller = UpdaterController.getInstance(activity)
        controller.addUpdate(update, false)
    }

    private fun getTimeStamp(file: File): Long {
        try {
            val metadataContent = readMetadataFromZip(file)
            metadataContent.lines().forEach { line ->
                if (line.startsWith(METADATA_TIMESTAMP_KEY)) {
                    return line.removePrefix(METADATA_TIMESTAMP_KEY).toLong()
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read date from local update zip package", e)
        } catch (e: NumberFormatException) {
            Log.e(TAG, "Failed to parse timestamp number from zip metadata file", e)
        }

        Log.e(TAG, "Couldn't find timestamp in zip file, falling back to now")
        return System.currentTimeMillis()
    }

    @Throws(IOException::class)
    private fun readMetadataFromZip(file: File): String {
        ZipFile(file).use { zip ->
            val entry = zip.entries().asSequence().firstOrNull { it.name == METADATA_PATH }
                ?: throw IOException("Couldn't find $METADATA_PATH in ${file.name}")

            zip.getInputStream(entry).bufferedReader().use {
                return it.readText()
            }
        }
    }

    interface Callbacks {
        fun onImportStarted()
        fun onImportCompleted(update: Update?)
    }

    companion object {
        private const val REQUEST_PICK = 9061
        private const val TAG = "UpdateImporter"
        private const val MIME_ZIP = "application/zip"
        private const val FILE_NAME = "localUpdate.zip"
        private const val METADATA_PATH = "META-INF/com/android/metadata"
        private const val METADATA_TIMESTAMP_KEY = "post-timestamp="
    }
}