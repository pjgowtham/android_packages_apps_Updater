/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.download

interface DownloadClient {

    /**
     * Start the download. This method has no effect if the download already started.
     */
    fun start()

    /**
     * Resume the download. The download will fail if the server can't fulfil the
     * partial content request and DownloadCallback.onFailure() will be called.
     * This method has no effect if the download already started or the destination
     * file doesn't exist.
     */
    fun resume()

    /**
     * Cancel the download. This method has no effect if the download isn't ongoing.
     */
    fun cancel()

    interface DownloadCallback {
        fun onResponse(headers: Headers)
        fun onSuccess()
        fun onFailure(cancelled: Boolean)
    }

    interface ProgressListener {
        fun update(bytesSentTotal: Long, contentLength: Long, speed: Long, eta: Long)
    }

    interface Headers {
        operator fun get(name: String): String?
    }
}
