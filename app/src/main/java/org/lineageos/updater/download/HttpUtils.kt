/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.download

import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object HttpUtils {

    private const val TAG = "HttpUtils"

    private const val TIMEOUT_MS = 5000
    private const val DEFAULT_RETRIES = 3

    /** RFC 6249 duplicate link pattern for mirror resolution */
    private val DUPLICATE_LINK_REGEX = Regex(
        """(?i)<(.+)>\s*;\s*rel=duplicate(?:.*pri=(\d+).*|.*)"""
    )

    data class MirrorLink(val url: String, val priority: Int)

    private fun isSuccessCode(code: Int) = code in 200..299
    private fun isRedirectCode(code: Int) = code in 300..399

    /**
     * Retry a block up to [times] on IOException.
     */
    fun <T> withRetry(times: Int = DEFAULT_RETRIES, block: () -> T): T {
        var lastException: IOException? = null
        repeat(times) { attempt ->
            try {
                return block()
            } catch (e: IOException) {
                lastException = e
                Log.w(TAG, "Attempt ${attempt + 1}/$times failed", e)
            }
        }
        throw lastException!!
    }

    /**
     * Parse RFC 6249 "Link: rel=duplicate" headers for mirror URLs.
     * Returns mirrors sorted by priority (lower priority number = higher preference).
     */
    @JvmStatic
    fun parseDuplicateLinks(headers: Map<String?, List<String>>): List<MirrorLink> =
        headers.entries
            .filter { it.key.equals("Link", ignoreCase = true) }
            .flatMap { it.value }
            .mapNotNull { header ->
                DUPLICATE_LINK_REGEX.matchEntire(header)?.let { match ->
                    val url = match.groupValues[1]
                    val priority = match.groupValues[2].toIntOrNull() ?: 999999
                    Log.d(TAG, "Found duplicate link $url (pri=$priority)")
                    MirrorLink(url, priority)
                }
            }
            .sortedBy { it.priority }

    /**
     * Use extension for HttpURLConnection to ensure disconnect is called.
     */
    inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
        try { block(this) } finally { disconnect() }

    /**
     * Opens a HEAD connection to the given URL.
     */
    @JvmStatic
    fun openHeadConnection(
        url: String,
        followRedirects: Boolean = true,
        timeout: Int = TIMEOUT_MS,
    ): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = followRedirects
        connectTimeout = timeout
        readTimeout = timeout
        requestMethod = "HEAD"
    }

    /**
     * Fetches a byte range from a URL using HTTP Range requests.
     * 
     * @throws IOException if the range request fails or returns non-206 status
     */
    @JvmStatic
    @Throws(IOException::class)
    fun fetchRange(url: String, start: Long, end: Long): ByteArray {
        if (start < 0 || end < start) {
            throw IllegalArgumentException("Invalid range: $start-$end")
        }
        
        return (URL(url).openConnection() as HttpURLConnection).use { conn ->
            conn.instanceFollowRedirects = true
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Range", "bytes=$start-$end")
            
            conn.connect()
            if (conn.responseCode != 206) {
                throw IOException(
                    "Range request failed with ${conn.responseCode} (expected 206)"
                )
            }
            conn.inputStream.use { it.readBytes() }
        }
    }

    /**
     * Gets the Content-Length of a URL via HEAD request.
     * 
     * @return Content length in bytes, or -1 if not available
     * @throws IOException if the HEAD request fails
     */
    @JvmStatic
    @Throws(IOException::class)
    fun getContentLength(url: String): Long = openHeadConnection(url).use { conn ->
        conn.connect()
        if (!isSuccessCode(conn.responseCode)) {
            throw IOException("HEAD request failed with ${conn.responseCode}")
        }
        conn.contentLengthLong
    }

    /**
     * Resolve a download URL through redirects and RFC 6249 duplicate links,
     * returning a direct CDN URL suitable for Range requests.
     * 
     * This method:
     * 1. Follows Location headers from 3xx redirects
     * 2. Parses RFC 6249 Link: rel=duplicate headers for mirrors
     * 3. Tests each candidate URL to ensure it supports Range requests
     * 4. Returns the first working mirror URL
     * 
     * @param downloadUrl The initial download URL (may redirect to mirrors)
     * @return A direct URL that supports HTTP Range requests
     * @throws IOException if all mirror candidates fail or no valid URL is found
     */
    @JvmStatic
    @Throws(IOException::class)
    fun resolveMirrorUrl(downloadUrl: String): String {
        return openHeadConnection(downloadUrl, followRedirects = false).use { conn ->
            conn.connect()
            val responseCode = conn.responseCode

            // If no redirect, the original URL is already direct
            if (!isRedirectCode(responseCode)) return downloadUrl

            val protocol = URL(downloadUrl).protocol
            val locationUrl = conn.getHeaderField("Location")
            val duplicates = parseDuplicateLinks(conn.headerFields)

            val urlsToTry = buildList {
                locationUrl?.let { add(it) }
                addAll(duplicates.map { it.url })
            }

            // Try each candidate URL
            for (candidateUrl in urlsToTry) {
                try {
                    val url = URL(candidateUrl)
                    
                    // Allow HTTP -> HTTPS upgrades, but not downgrades
                    if (url.protocol != protocol) {
                        if (!(protocol == "http" && url.protocol == "https")) {
                            Log.w(TAG, "Skipping $candidateUrl: protocol change not allowed")
                            continue
                        }
                    }

                    // Test if this URL supports Range requests
                    openHeadConnection(candidateUrl, timeout = TIMEOUT_MS).use { testConn ->
                        testConn.connect()
                        if (isSuccessCode(testConn.responseCode)) {
                            val acceptRanges = testConn.getHeaderField("Accept-Ranges")
                            // Accept if header is missing or not explicitly "none"
                            if (acceptRanges == null || acceptRanges.lowercase() != "none") {
                                Log.d(TAG, "Resolved to $candidateUrl")
                                return candidateUrl
                            } else {
                                Log.w(TAG, "Skipping $candidateUrl: Range requests not supported")
                            }
                        }
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "Mirror candidate failed: $candidateUrl", e)
                }
            }

            throw IOException("All mirror candidates failed for $downloadUrl")
        }
    }
}