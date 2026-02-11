/*
 * SPDX-FileCopyrightText: 2017-2022 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.download

import java.io.File
import java.io.IOException

interface DownloadClient {

    interface DownloadCallback {
        fun onResponse(headers: Headers)
        fun onSuccess()
        fun onFailure(cancelled: Boolean)
    }

    interface ProgressListener {
        fun update(bytesRead: Long, contentLength: Long, speed: Long, eta: Long)
    }

    interface Headers {
        fun get(name: String): String?
    }

    /** Start the download. No effect if already started. */
    fun start()

    /**
     * Resume the download. Fails if the server can't fulfil the partial content request.
     * No effect if already started or destination file doesn't exist.
     */
    fun resume()

    /** Cancel the download. No effect if not ongoing. */
    fun cancel()

    class Builder {
        private var url: String? = null
        private var destination: File? = null
        private var callback: DownloadCallback? = null
        private var progressListener: ProgressListener? = null
        private var useDuplicateLinks: Boolean = false

        @Throws(IOException::class)
        fun build(): DownloadClient {
            val url = requireNotNull(url) { 
                "Download URL must be set via setUrl() before building" 
            }
            val destination = requireNotNull(destination) { 
                "Download destination must be set via setDestination() before building" 
            }
            val callback = requireNotNull(callback) { 
                "Download callback must be set via setDownloadCallback() before building" 
            }
            return HttpURLConnectionClient(url, destination, progressListener, callback, useDuplicateLinks)
        }

        fun setUrl(url: String) = apply { this.url = url }
        fun setDestination(destination: File) = apply { this.destination = destination }
        fun setDownloadCallback(callback: DownloadCallback) = apply { this.callback = callback }
        fun setProgressListener(listener: ProgressListener) = apply { this.progressListener = listener }
        fun setUseDuplicateLinks(use: Boolean) = apply { this.useDuplicateLinks = use }
    }
}