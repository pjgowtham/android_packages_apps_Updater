/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

@file:JvmName("HttpUtils")

package org.lineageos.updater.download

import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mirror resolution and HTTP Range utilities.
 *
 * Expects the download server to redirect to the best mirror,
 * optionally with RFC 6249 `Link: rel=duplicate` headers for alternatives.
 * All Range/HEAD requests target resolved mirror URLs, not the redirector.
 *
 * See [RFC 6249 Section 3](https://datatracker.ietf.org/doc/html/rfc6249#section-3).
 */

private const val TAG = "HttpUtils"

const val TIMEOUT_MS = 5000

fun isSuccessCode(statusCode: Int): Boolean = statusCode / 100 == 2

fun isRedirectCode(statusCode: Int): Boolean = statusCode / 100 == 3

fun isPartialContentCode(statusCode: Int): Boolean = statusCode == 206

// https://tools.ietf.org/html/rfc6249
// https://tools.ietf.org/html/rfc5988#section-5
private val DUPLICATE_LINK_REGEX = Regex(
    """(?i)<(.+)>\s*;\s*rel=duplicate(?:.*pri=(\d+).*|.*)?"""
)

data class MirrorLink(val url: String, val priority: Int)

/**
 * Parse RFC 6249 `Link: rel=duplicate` headers for mirror URLs.
 * Returns mirrors sorted by priority (lower = higher preference).
 */
fun parseDuplicateLinks(headers: Map<String?, List<String>>): List<MirrorLink> =
    headers.entries
        .asSequence()
        .filter { it.key.equals("Link", ignoreCase = true) }
        .flatMap { it.value }
        .mapNotNull { header ->
            DUPLICATE_LINK_REGEX.matchEntire(header)?.let { match ->
                val url = match.groupValues[1]
                val priority = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 999999
                Log.d(TAG, "Found duplicate link $url (pri=$priority)")
                MirrorLink(url, priority)
            }
        }
        .sortedBy { it.priority }
        .toList()

/** Opens a HEAD connection to the given URL. */
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
 * Fetches a byte range from [url] (must be a resolved mirror, not a redirector).
 *
 * @throws IOException if the server returns anything other than 206
 */
@Throws(IOException::class)
fun fetchRange(url: String, start: Long, end: Long): ByteArray {
    require(start in 0..end) { "Invalid range: $start-$end" }

    val conn = URL(url).openConnection() as HttpURLConnection
    try {
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
        return conn.inputStream.use { it.readBytes() }
    } finally {
        conn.disconnect()
    }
}

/**
 * Gets the Content-Length of [url] via HEAD request.
 *
 * @return Content length in bytes, or -1 if the server omits the header
 * @throws IOException if the HEAD request does not return 200
 */
@Throws(IOException::class)
fun getContentLength(url: String): Long {
    val conn = openHeadConnection(url)
    try {
        conn.connect()
        if (conn.responseCode != 200) {
            throw IOException("HEAD request failed with ${conn.responseCode}")
        }
        return conn.contentLengthLong
    } finally {
        conn.disconnect()
    }
}

/**
 * Resolves [downloadUrl] through redirects and RFC 6249 duplicate links,
 * returning a direct mirror URL suitable for Range requests.
 *
 * Sends a HEAD with redirects disabled, extracts Location and
 * `Link: rel=duplicate` headers, then validates each candidate for
 * Range support. Returns the original URL if no redirect occurs.
 *
 * @throws IOException if all mirror candidates fail or none support Range
 */
@Throws(IOException::class)
fun handleDuplicateLinks(downloadUrl: String): String {
    val conn = openHeadConnection(downloadUrl, followRedirects = false)
    try {
        conn.connect()
        val responseCode = conn.responseCode

        // If no redirect, the original URL is already direct
        if (!isRedirectCode(responseCode)) return downloadUrl

        val protocol = URL(downloadUrl).protocol
        val locationUrl = conn.getHeaderField("Location")
        val duplicates = parseDuplicateLinks(conn.headerFields)

        val urlsToTry = sequence {
            locationUrl?.let { yield(it) }
            yieldAll(duplicates.map { it.url })
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
                val testConn = openHeadConnection(candidateUrl, timeout = TIMEOUT_MS)
                try {
                    testConn.connect()
                    if (testConn.responseCode == 200) {
                        val acceptRanges = testConn.getHeaderField("Accept-Ranges")
                        // Accept if header is missing or not explicitly "none"
                        if (acceptRanges?.lowercase() != "none") {
                            Log.d(TAG, "Resolved to $candidateUrl")
                            return candidateUrl
                        } else {
                            Log.w(TAG, "Skipping $candidateUrl: Range requests not supported")
                        }
                    }
                } finally {
                    testConn.disconnect()
                }
            } catch (e: IOException) {
                Log.w(TAG, "Mirror candidate failed: $candidateUrl", e)
            }
        }

        throw IOException("All mirror candidates failed for $downloadUrl")
    } finally {
        conn.disconnect()
    }
}
