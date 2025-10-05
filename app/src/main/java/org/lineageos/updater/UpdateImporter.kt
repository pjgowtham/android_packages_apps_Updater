/*
 * SPDX-FileCopyrightText: 2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.RecoverySystem
import android.util.Log
import org.lineageos.updater.controller.UpdaterController
import org.lineageos.updater.misc.StringGenerator
import org.lineageos.updater.misc.Utils
import org.lineageos.updater.model.Update
import org.lineageos.updater.model.UpdateStatus
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.text.DateFormat
import java.util.zip.ZipFile
import kotlin.concurrent.thread

class UpdateImporter(
    private val activity: Activity,
    private val callbacks: Callbacks
) {

    private var workingThread: Thread? = null

    interface Callbacks {
        fun onImportStarted()
        fun onImportCompleted(update: Update?)
    }

    private fun addUpdate(update: Update) {
        val controller = UpdaterController.getInstance(activity)
        controller.addUpdate(update, false)
    }

    private fun buildLocalUpdate(file: File): Update {
        val timeStamp = getTimeStamp(file)
        val buildDate = StringGenerator.getDateLocalizedUTC(activity, DateFormat.MEDIUM, timeStamp)
        val name = activity.getString(R.string.local_update_name)
        return Update().apply {
            availableOnline = false
            this.name = name
            this.file = file
            fileSize = file.length()
            downloadId = Update.LOCAL_ID
            timestamp = timeStamp
            status = UpdateStatus.VERIFIED
            persistentStatus = UpdateStatus.Persistent.VERIFIED
            version = "$name ($buildDate)"
        }
    }

    private fun getTimeStamp(file: File): Long {
        return try {
            val metadataContent = readZippedFile(file)
            metadataContent.lines()
                .firstOrNull { it.startsWith(METADATA_TIMESTAMP_KEY) }
                ?.substringAfter(METADATA_TIMESTAMP_KEY)
                ?.toLong()
                ?: throw NumberFormatException("Timestamp not found or invalid")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read date from local update zip package", e)
            System.currentTimeMillis()
        } catch (e: NumberFormatException) {
            Log.e(TAG, "Failed to parse timestamp number from zip metadata file", e)
            System.currentTimeMillis()
        }
    }

    @SuppressLint("SetWorldReadable")
    @Throws(IOException::class)
    private fun importFile(uri: Uri): File {
        val downloadDir = Utils.getDownloadPath(activity)
        val outFile = File(downloadDir, FILE_NAME)
        if (outFile.exists()) {
            outFile.delete()
        }

        activity.contentResolver.openFileDescriptor(uri, "r")?.use { parcelDescriptor ->
            FileInputStream(parcelDescriptor.fileDescriptor).use { iStream ->
                FileOutputStream(outFile).use { oStream ->
                    iStream.copyTo(oStream)
                }
            }
        } ?: throw IOException("Failed to obtain fileDescriptor")

        outFile.setReadable(true, false)
        return outFile
    }

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

    @Throws(IOException::class)
    private fun readZippedFile(file: File): String {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry(METADATA_PATH)
                ?: throw FileNotFoundException("Couldn't find $METADATA_PATH in ${file.name}")
            return zip.getInputStream(entry).bufferedReader().readText()
        }
    }

    @Throws(Exception::class)
    @Suppress("ResultOfMethodCallIgnored")
    private fun verifyPackage(file: File) {
        try {
            RecoverySystem.verifyPackage(file, null, null)
        } catch (e: Exception) {
            if (file.exists()) {
                file.delete()
            }
            throw Exception("Verification failed, file has been deleted", e)
        }
    }

    fun onResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (resultCode != Activity.RESULT_OK || requestCode != REQUEST_PICK || data?.data == null) {
            return false
        }
        return onPicked(data.data!!)
    }

    fun openImportPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = MIME_ZIP
        }
        activity.startActivityForResult(intent, REQUEST_PICK)
    }

    fun stopImport() {
        workingThread?.interrupt()
        workingThread = null
    }



    companion object {
        private const val FILE_NAME = "localUpdate.zip"
        private const val METADATA_PATH = "META-INF/com/android/metadata"
        private const val METADATA_TIMESTAMP_KEY = "post-timestamp="
        private const val MIME_ZIP = "application/zip"
        private const val REQUEST_PICK = 9061
        private const val TAG = "UpdateImporter"
    }
}
