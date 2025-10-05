/*
 * SPDX-FileCopyrightText: 2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.download

import java.io.File
import java.io.IOException

interface DownloadClient {

    class Builder {
        private var callback: DownloadCallback? = null
        private var destination: File? = null
        private var progressListener: ProgressListener? = null
        private var url: String? = null
        private var useDuplicateLinks: Boolean = false

        @Throws(IOException::class)
        fun build(): DownloadClient {
            val url = this.url ?: throw IllegalStateException("No download URL defined")
            val destination = this.destination
                ?: throw IllegalStateException("No download destination defined")
            val callback = this.callback
                ?: throw IllegalStateException("No download callback defined")
            return HttpURLConnectionClient(
                url, destination, progressListener, callback, useDuplicateLinks
            )
        }

        fun setDownloadCallback(downloadCallback: DownloadCallback) = apply {
            this.callback = downloadCallback
        }

        fun setDestination(destination: File) = apply {
            this.destination = destination
        }

        fun setProgressListener(progressListener: ProgressListener) = apply {
            this.progressListener = progressListener
        }

        fun setUrl(url: String) = apply {
            this.url = url
        }

        fun setUseDuplicateLinks(useDuplicateLinks: Boolean) = apply {
            this.useDuplicateLinks = useDuplicateLinks
        }
    }

    interface DownloadCallback {
        fun onFailure(cancelled: Boolean)
        fun onResponse(headers: Headers)
        fun onSuccess()
    }

    fun interface Headers {
        fun get(name: String): String?
    }


    fun interface ProgressListener {
        fun update(bytesRead: Long, contentLength: Long, speed: Long, eta: Long)
    }

    /**
     * Cancel the download. This method has no effect if the download isn't ongoing.
     */
    fun cancel()

    /**
     * Resume the download. The download will fail if the server can't fulfil the
     * partial content request and DownloadCallback.onFailure() will be called.
     * This method has no effect if the download already started or the destination
     * file doesn't exist.
     */
    fun resume()

    /**
     * Start the download. This method has no effect if the download already started.
     */
    fun start()
}
