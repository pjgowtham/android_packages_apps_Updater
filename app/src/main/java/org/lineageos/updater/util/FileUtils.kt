/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.util

import android.content.ContentResolver
import android.net.Uri
import android.os.FileUtils as OsFileUtils
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

object FileUtils {

    private val TAG = FileUtils::class.simpleName!!

    @JvmStatic
    @Throws(IOException::class)
    fun copyFile(sourceFile: File, destFile: File) {
        try {
            OsFileUtils.copy(sourceFile, destFile)
        } catch (e: IOException) {
            Log.e(TAG, "Could not copy file", e)
            if (destFile.exists()) {
                destFile.delete()
            }
            throw e
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun copyFile(cr: ContentResolver, sourceFile: File, destUri: Uri) {
        try {
            FileInputStream(sourceFile).use { input ->
                cr.openFileDescriptor(destUri, "w")!!.use { pfd ->
                    FileOutputStream(pfd.fileDescriptor).use { output ->
                        OsFileUtils.copy(input, output)
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Could not copy file", e)
            throw e
        }
    }

    @JvmStatic
    fun queryName(resolver: ContentResolver, uri: Uri): String? = try {
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            cursor.moveToFirst()
            cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME))
        }
    } catch (e: Exception) {
        null
    }
}
