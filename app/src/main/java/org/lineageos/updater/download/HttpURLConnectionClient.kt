/*
 * SPDX-FileCopyrightText: 2017-2022 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.download

import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class HttpURLConnectionClient(
    url: String,
    private val destination: File,
    private val progressListener: DownloadClient.ProgressListener?,
    private val callback: DownloadClient.DownloadCallback,
    private val useDuplicateLinks: Boolean,
) : DownloadClient {

    companion object {
        private const val TAG = "HttpURLConnectionClient"
        private const val TIMEOUT_MS = 5000
        private const val BUFFER_SIZE = 8192
        
        private fun isSuccessCode(code: Int) = code in 200..299
        private fun isRedirectCode(code: Int) = code in 300..399
    }

    private var client: HttpURLConnection = (URL(url).openConnection() as HttpURLConnection)
    
    @Volatile
    private var downloadThread: DownloadThread? = null
    
    private val threadLock = Any()

    private inner class HeadersImpl : DownloadClient.Headers {
        override fun get(name: String): String? = client.getHeaderField(name)
    }

    override fun start() {
        synchronized(threadLock) {
            if (downloadThread != null) {
                Log.e(TAG, "Already downloading")
                return
            }
            startDownload(resume = false)
        }
    }

    override fun resume() {
        synchronized(threadLock) {
            if (downloadThread != null) {
                Log.e(TAG, "Already downloading")
                return
            }
            if (!destination.exists()) {
                callback.onFailure(false)
                return
            }
            val offset = destination.length()
            client.setRequestProperty("Range", "bytes=$offset-")
            startDownload(resume = true)
        }
    }

    override fun cancel() {
        synchronized(threadLock) {
            downloadThread?.let {
                it.interrupt()
                downloadThread = null
            } ?: Log.e(TAG, "Not downloading")
        }
    }

    private fun startDownload(resume: Boolean) {
        downloadThread = DownloadThread(resume).also { it.start() }
    }

    private fun changeClientUrl(newUrl: URL) {
        val range = client.getRequestProperty("Range")
        client.disconnect()
        client = (newUrl.openConnection() as HttpURLConnection)
        range?.let { client.setRequestProperty("Range", it) }
    }

    private fun handleDuplicateLinks() {
        val protocol = client.url.protocol
        val locationUrl = client.getHeaderField("Location")
        val duplicates = HttpUtils.parseDuplicateLinks(client.headerFields)

        val urlsToTry = buildList {
            locationUrl?.let { add(it) }
            addAll(duplicates.map { it.url })
        }

        for (candidateUrl in urlsToTry) {
            try {
                val url = URL(candidateUrl)
                // Allow HTTP -> HTTPS upgrades, but not downgrades
                if (url.protocol != protocol) {
                    if (!(protocol == "http" && url.protocol == "https")) {
                        throw IOException("Protocol changes not allowed (attempted: $protocol -> ${url.protocol})")
                    }
                }
                Log.d(TAG, "Downloading from $candidateUrl")
                changeClientUrl(url)
                client.connectTimeout = TIMEOUT_MS
                client.connect()
                if (isSuccessCode(client.responseCode)) {
                    return
                }
                throw IOException("Server replied with ${client.responseCode}")
            } catch (e: IOException) {
                if (candidateUrl == urlsToTry.last()) throw e
                Log.e(TAG, "Using next duplicate link", e)
            }
        }
    }

    private inner class DownloadThread(private val resume: Boolean) : Thread() {

        private var totalBytes = 0L
        private var totalBytesRead = 0L
        private var curSampleBytes = 0L
        private var lastMillis = 0L
        private var speed = -1L
        private var eta = -1L

        private fun calculateSpeed(justResumed: Boolean) {
            val millis = SystemClock.elapsedRealtime()
            if (justResumed) {
                lastMillis = millis
                speed = -1
                curSampleBytes = totalBytesRead
                return
            }
            val delta = millis - lastMillis
            if (delta > 500) {
                // Use Long literals to prevent integer overflow
                val bytesDelta = totalBytesRead - curSampleBytes
                val curSpeed = (bytesDelta * 1000L) / delta
                speed = if (speed == -1L) curSpeed else ((speed * 3L) + curSpeed) / 4L
                lastMillis = millis
                curSampleBytes = totalBytesRead
            }
        }

        private fun calculateEta() {
            if (speed > 0) {
                eta = (totalBytes - totalBytesRead) / speed
            }
        }

        override fun run() {
            var justResumed = false
            try {
                client.instanceFollowRedirects = !useDuplicateLinks
                client.connect()
                var responseCode = client.responseCode

                if (useDuplicateLinks && isRedirectCode(responseCode)) {
                    handleDuplicateLinks()
                    responseCode = client.responseCode
                }

                callback.onResponse(HeadersImpl())

                if (resume && responseCode == 206) {
                    justResumed = true
                    totalBytesRead = destination.length()
                    Log.d(TAG, "Server fulfilled partial content request")
                } else if (resume || !isSuccessCode(responseCode)) {
                    Log.e(TAG, "Server replied with $responseCode")
                    callback.onFailure(isInterrupted)
                    return
                }

                client.inputStream.use { input ->
                    FileOutputStream(destination, resume).use { output ->
                        val contentLength = client.contentLengthLong
                        totalBytes = if (contentLength > 0) {
                            contentLength + totalBytesRead
                        } else {
                            // Unknown content length
                            -1
                        }
                        
                        val buffer = ByteArray(BUFFER_SIZE)
                        var count = 0

                        while (!isInterrupted && input.read(buffer).also { count = it } > 0) {
                            output.write(buffer, 0, count)
                            totalBytesRead += count
                            calculateSpeed(justResumed)
                            if (totalBytes > 0) {
                                calculateEta()
                            }
                            justResumed = false
                            progressListener?.update(totalBytesRead, totalBytes, speed, eta)
                        }

                        progressListener?.update(totalBytesRead, totalBytes, speed, eta)
                        output.flush()

                        if (isInterrupted) {
                            callback.onFailure(true)
                        } else {
                            callback.onSuccess()
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error downloading file", e)
                callback.onFailure(isInterrupted)
            } finally {
                client.disconnect()
                synchronized(threadLock) {
                    downloadThread = null
                }
            }
        }
    }
}