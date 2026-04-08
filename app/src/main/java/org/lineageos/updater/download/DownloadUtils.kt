/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.download

import io.ktor.http.Headers
import java.net.URI
import java.util.Locale
import kotlin.math.abs

data class ContentRange(
    val start: Long,
    val end: Long,
    val totalBytes: Long?,
)

data class DuplicateLink(
    val url: String,
    val priority: Int,
)

/** Helpers for MirrorBits duplicate links, range parsing, and ETA smoothing. */
object DownloadUtils {
    private const val DEFAULT_DUPLICATE_PRIORITY = 999_999

    private val duplicateLinkRegex =
        Regex("(?i)<([^>]+)>\\s*;\\s*rel=duplicate(?:.*pri=([0-9]+).*|.*)?")

    private val contentRangeRegex = Regex("(?i)bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)")

    @JvmStatic
    fun isSuccessCode(statusCode: Int) = statusCode in 200..299

    @JvmStatic
    fun isRedirectCode(statusCode: Int) = statusCode in 300..399

    @JvmStatic
    fun isPartialContentCode(statusCode: Int) = statusCode == 206

    @JvmStatic
    fun parseContentRange(headerValue: String?): ContentRange? {
        val match = headerValue?.let(contentRangeRegex::matchEntire) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val totalBytes = match.groupValues[3]
            .takeUnless { it == "*" }
            ?.toLongOrNull()

        if (end < start) return null
        if (totalBytes != null && totalBytes <= end) return null

        return ContentRange(
            start = start,
            end = end,
            totalBytes = totalBytes,
        )
    }

    /** Parses the MirrorBits `Link: rel=duplicate` fields used by LineageOS mirrors. */
    @JvmStatic
    fun parseDuplicateLinks(headers: Headers): List<DuplicateLink> =
        headers.names()
            .asSequence()
            .filter { it.equals("Link", ignoreCase = true) }
            .flatMap { name -> headers.getAll(name).orEmpty().asSequence() }
            .mapNotNull(::parseDuplicateLink)
            .sortedBy(DuplicateLink::priority)
            .toList()

    @JvmStatic
    fun parseDuplicateLinks(headers: Map<String?, List<String>>): List<DuplicateLink> =
        headers
            .asSequence()
            .filter { (name, _) -> name.equals("Link", ignoreCase = true) }
            .flatMap { (_, values) -> values.asSequence() }
            .mapNotNull(::parseDuplicateLink)
            .sortedBy(DuplicateLink::priority)
            .toList()

    @JvmStatic
    @Throws(IllegalArgumentException::class)
    fun resolveRequestUrl(baseUrl: String, requestUrl: String): String {
        val baseUri = URI.create(baseUrl)
        val resolvedUri = baseUri.resolve(requestUrl)
        val baseScheme = baseUri.scheme?.lowercase(Locale.US)
            ?: throw IllegalArgumentException("Missing URL scheme for $baseUrl")
        val resolvedScheme = resolvedUri.scheme?.lowercase(Locale.US)
            ?: throw IllegalArgumentException("Missing URL scheme for $requestUrl")

        if (resolvedScheme != "http" && resolvedScheme != "https") {
            throw IllegalArgumentException("Unsupported URL scheme $resolvedScheme for $requestUrl")
        }
        if (baseScheme == "https" && resolvedScheme == "http") {
            throw IllegalArgumentException("Redirect from HTTPS to HTTP is not allowed")
        }

        return resolvedUri.toString()
    }

    /** Same ETA smoothing model as Firefox DownloadsCommon.sys.mjs smoothSeconds(). */
    @JvmOverloads
    @JvmStatic
    fun calculateEta(
        totalSizeBytes: Long,
        bytesDownloadedSoFar: Long,
        speed: Long,
        lastSmoothedEta: Double = -1.0,
    ): Double {
        if (speed <= 0 || totalSizeBytes <= bytesDownloadedSoFar) return -1.0

        var rawSeconds = (totalSizeBytes - bytesDownloadedSoFar).toDouble() / speed

        if (lastSmoothedEta >= 0.0) {
            val diff = rawSeconds - lastSmoothedEta
            rawSeconds = if (abs(diff) < 5 || abs(diff) / lastSmoothedEta < 0.05) {
                // Small change: nudge toward new value slowly (release).
                lastSmoothedEta - (if (diff < 0) 0.4 else 0.2)
            } else {
                // Large change: ease toward new value (attack).
                lastSmoothedEta + (if (diff < 0) 0.3 else 0.1) * diff
            }
        }

        return rawSeconds.coerceAtLeast(1.0)
    }

    private fun parseDuplicateLink(field: String): DuplicateLink? {
        val match = duplicateLinkRegex.matchEntire(field) ?: return null
        val url = match.groupValues[1]
        val priority = match.groupValues[2]
            .takeIf(String::isNotEmpty)
            ?.toIntOrNull()
            ?: DEFAULT_DUPLICATE_PRIORITY
        return DuplicateLink(
            url = url,
            priority = priority,
        )
    }
}
