/*
 * SPDX-FileCopyrightText: 2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.misc

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel

object FileUtils {

    private const val TAG = "FileUtils"

    /**
     * A functional interface for progress callbacks.
     */
    fun interface ProgressCallBack {
        fun update(progress: Int)
    }

    private class CallbackByteChannel(
        private val readableByteChannel: ReadableByteChannel,
        private val size: Long,
        private val callback: ProgressCallBack,
    ) : ReadableByteChannel {
        private var sizeRead: Long = 0
        private var progress: Int = 0

        override fun close() = readableByteChannel.close()

        override fun isOpen(): Boolean = readableByteChannel.isOpen

        override fun read(bb: ByteBuffer): Int {
            val read = readableByteChannel.read(bb)
            if (read > 0) {
                sizeRead += read
                val currentProgress = if (size > 0) (sizeRead * 100f / size).toInt() else -1
                if (progress != currentProgress) {
                    callback.update(currentProgress)
                    progress = currentProgress
                }
            }
            return read
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun copyFile(sourceFile: File, destFile: File, progressCallBack: ProgressCallBack?) {
        try {
            FileInputStream(sourceFile).channel.use { sourceChannel ->
                FileOutputStream(destFile).channel.use { destChannel ->
                    val byteChannel = progressCallBack?.let {
                        CallbackByteChannel(sourceChannel, sourceFile.length(), it)
                    } ?: sourceChannel
                    destChannel.transferFrom(byteChannel, 0, sourceChannel.size())
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Could not copy file", e)
            destFile.delete()
            throw e
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun copyFile(
        cr: ContentResolver,
        sourceFile: File,
        destUri: Uri,
        progressCallBack: ProgressCallBack?
    ) {
        try {
            FileInputStream(sourceFile).channel.use { sourceChannel ->
                cr.openFileDescriptor(destUri, "w")?.use { pfd ->
                    FileOutputStream(pfd.fileDescriptor).channel.use { destChannel ->
                        val byteChannel = progressCallBack?.let {
                            CallbackByteChannel(sourceChannel, sourceFile.length(), it)
                        } ?: sourceChannel
                        destChannel.transferFrom(byteChannel, 0, sourceChannel.size())
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Could not copy file", e)
            throw e
        }
    }

    @JvmStatic
    fun queryName(resolver: ContentResolver, uri: Uri): String? {
        return try {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        cursor.getString(nameIndex)
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            // ignore
            null
        }
    }
}
