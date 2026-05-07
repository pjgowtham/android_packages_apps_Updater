/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.controller

import org.apache.commons.compress.archivers.zip.ZipFile
import org.lineageos.updater.download.DownloadUtils
import org.lineageos.updater.misc.Constants
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import java.util.ArrayDeque

private const val DEFAULT_TIMEOUT_MS = 20_000
private const val MAX_REDIRECTS = 5
private const val MAX_READ_SIZE = 1024 * 1024

private const val HEADER_ACCEPT_ENCODING = "Accept-Encoding"
private const val HEADER_CONNECTION = "Connection"
private const val HEADER_CONTENT_RANGE = "Content-Range"
private const val HEADER_LOCATION = "Location"
private const val HEADER_RANGE = "Range"

class Payload(
    @JvmField val url: String,
    @JvmField val offset: Long,
    @JvmField val size: Long,
    @JvmField val headerKeyValuePairs: Array<String>,
) {
    companion object {
        @JvmStatic
        @Throws(IOException::class)
        fun from(url: String): Payload = payloadFrom(url)

        @JvmStatic
        @Throws(IOException::class)
        fun from(file: File): Payload = payloadFrom(file)
    }
}

@Throws(IOException::class)
private fun payloadFrom(url: String): Payload {
    val urls = ArrayDeque<Url>()
    val seenUrls = mutableSetOf(url)
    urls.add(Url(url, MAX_REDIRECTS))

    var lastException: IOException? = null
    while (urls.isNotEmpty()) {
        val nextUrl = urls.removeFirst()
        try {
            return payloadFromUrl(nextUrl)
        } catch (e: RedirectException) {
            enqueueRedirectUrls(e, urls, seenUrls)
        } catch (e: IOException) {
            lastException = e
        }
    }

    throw lastException ?: IOException("No streaming install URLs available for $url")
}

@Throws(IOException::class)
private fun payloadFrom(file: File): Payload {
    ZipFile.builder().setFile(file).get().use { zipFile ->
        return buildPayload(
            url = "file://${file.absolutePath}",
            zipFile = zipFile,
        )
    }
}

@Throws(IOException::class)
private fun payloadFromUrl(url: Url): Payload {
    PayloadUrlChannel.open(url).use { channel ->
        ZipFile.builder().setSeekableByteChannel(channel).get().use { zipFile ->
            val payload = buildPayload(channel.url, zipFile)
            channel.verifyRange(payload.offset, payload.size)
            return payload
        }
    }
}

private fun buildPayload(
    url: String,
    zipFile: ZipFile,
): Payload {
    val payloadEntry = zipFile.getEntry(Constants.AB_PAYLOAD_BIN_PATH)
        ?: throw IOException("Missing ${Constants.AB_PAYLOAD_BIN_PATH} in $url")
    val payloadPropertiesEntry = zipFile.getEntry(Constants.AB_PAYLOAD_PROPERTIES_PATH)
        ?: throw IOException("Missing ${Constants.AB_PAYLOAD_PROPERTIES_PATH} in $url")

    if (payloadEntry.size <= 0L) {
        throw IOException("Invalid ${Constants.AB_PAYLOAD_BIN_PATH} size in $url")
    }

    val headerKeyValuePairs =
        zipFile.getInputStream(payloadPropertiesEntry).use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).useLines { lines ->
                lines.filter(String::isNotEmpty).toList().toTypedArray()
            }
        }

    return Payload(
        url = url,
        offset = payloadEntry.dataOffset,
        size = payloadEntry.size,
        headerKeyValuePairs = headerKeyValuePairs,
    )
}

@Throws(IOException::class)
private fun redirectException(
    url: Url,
    connection: HttpURLConnection,
): RedirectException {
    if (url.redirectsRemaining <= 0) {
        throw IOException("Too many redirects for ${url.value}")
    }

    val location = connection.getHeaderField(HEADER_LOCATION)
        ?: throw IOException("Redirect response missing Location for ${url.value}")

    val resolvedLocation = try {
        DownloadUtils.resolveRequestUrl(url.value, location)
    } catch (e: IllegalArgumentException) {
        throw IOException("Invalid redirect target $location for ${url.value}", e)
    }

    val duplicateUrls = mutableListOf<String>()
    DownloadUtils.parseDuplicateLinks(connection.headerFields).forEach { duplicateLink ->
        val duplicateUrl = try {
            DownloadUtils.resolveRequestUrl(url.value, duplicateLink.url)
        } catch (_: IllegalArgumentException) {
            return@forEach
        }
        if (duplicateUrl != resolvedLocation) {
            duplicateUrls.add(duplicateUrl)
        }
    }

    return RedirectException(
        url = resolvedLocation,
        duplicateUrls = duplicateUrls,
        redirectsRemaining = url.redirectsRemaining - 1,
    )
}

private fun enqueueRedirectUrls(
    exception: RedirectException,
    urls: ArrayDeque<Url>,
    seenUrls: MutableSet<String>,
) {
    exception.duplicateUrls.asReversed().forEach { value ->
        if (seenUrls.add(value)) {
            urls.addFirst(Url(value, exception.redirectsRemaining))
        }
    }
    if (seenUrls.add(exception.url)) {
        urls.addFirst(Url(exception.url, exception.redirectsRemaining))
    }
}

@Throws(IOException::class)
private fun openRangeConnection(
    url: String,
    range: String,
): HttpURLConnection {
    val connection = URL(url).openConnection() as? HttpURLConnection
        ?: throw IOException("Unsupported URL protocol for $url")
    connection.connectTimeout = DEFAULT_TIMEOUT_MS
    connection.readTimeout = DEFAULT_TIMEOUT_MS
    connection.instanceFollowRedirects = false
    connection.setRequestProperty(HEADER_ACCEPT_ENCODING, "identity")
    connection.setRequestProperty(HEADER_CONNECTION, "close")
    connection.setRequestProperty(HEADER_RANGE, range)
    return connection
}

@Throws(IOException::class)
private fun readExactly(
    inputStream: InputStream,
    expectedLength: Int,
): ByteArray {
    val data = ByteArray(expectedLength)
    var offset = 0
    while (offset < expectedLength) {
        val read = inputStream.read(data, offset, expectedLength - offset)
        if (read == -1) {
            throw IOException("Unexpected end of response")
        }
        offset += read
    }
    return data
}

private data class Url(
    val value: String,
    val redirectsRemaining: Int,
)

private class RedirectException(
    val url: String,
    val duplicateUrls: List<String>,
    val redirectsRemaining: Int,
) : IOException()

private class PayloadUrlChannel private constructor(
    val url: String,
    private val fileSize: Long,
    private val redirectsRemaining: Int,
) : SeekableByteChannel {
    private var position = 0L
    private var open = true

    override fun read(dst: ByteBuffer): Int {
        ensureOpen()
        if (!dst.hasRemaining()) return 0
        if (position >= fileSize) return -1

        val length = minOf(
            dst.remaining().toLong(),
            fileSize - position,
            MAX_READ_SIZE.toLong(),
        ).toInt()
        val data = readRange(position, length)
        dst.put(data)
        position += data.size
        return data.size
    }

    override fun write(src: ByteBuffer): Int {
        throw NonWritableChannelException()
    }

    override fun position(): Long {
        ensureOpen()
        return position
    }

    override fun position(newPosition: Long): SeekableByteChannel {
        ensureOpen()
        if (newPosition < 0) {
            throw IOException("Negative channel position: $newPosition")
        }
        position = newPosition
        return this
    }

    override fun size(): Long {
        ensureOpen()
        return fileSize
    }

    override fun truncate(size: Long): SeekableByteChannel {
        throw NonWritableChannelException()
    }

    override fun isOpen() = open

    override fun close() {
        open = false
    }

    @Throws(IOException::class)
    private fun readRange(start: Long, length: Int): ByteArray {
        val end = start + length - 1
        val connection = openRangeConnection(url, "bytes=$start-$end")
        try {
            val statusCode = connection.responseCode
            if (DownloadUtils.isRedirectCode(statusCode)) {
                throw redirectException(
                    Url(url, redirectsRemaining),
                    connection,
                )
            }
            if (statusCode != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException(
                    "Unexpected HTTP status $statusCode for range $start-$end of $url"
                )
            }

            val contentRange =
                DownloadUtils.parseContentRange(connection.getHeaderField(HEADER_CONTENT_RANGE))
            if (contentRange == null ||
                contentRange.start != start ||
                contentRange.end != end
            ) {
                throw IOException("Invalid Content-Range for $url")
            }

            return connection.inputStream.use { inputStream ->
                readExactly(inputStream, length)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun ensureOpen() {
        if (!open) {
            throw ClosedChannelException()
        }
    }

    @Throws(IOException::class)
    fun verifyRange(offset: Long, size: Long) {
        readRange(offset, 1)
        readRange(offset + size - 1, 1)
    }

    companion object {
        @Throws(IOException::class)
        fun open(url: Url): PayloadUrlChannel {
            val connection = openRangeConnection(url.value, "bytes=0-0")
            try {
                val statusCode = connection.responseCode
                if (DownloadUtils.isRedirectCode(statusCode)) {
                    throw redirectException(url, connection)
                }
                if (statusCode != HttpURLConnection.HTTP_PARTIAL) {
                    throw IOException("Server ignored range request for ${url.value}")
                }

                val contentRange =
                    DownloadUtils.parseContentRange(connection.getHeaderField(HEADER_CONTENT_RANGE))
                val fileSize = contentRange?.totalBytes
                    ?: throw IOException("Missing Content-Range total for ${url.value}")
                if (contentRange.start != 0L || contentRange.end != 0L) {
                    throw IOException("Invalid Content-Range for ${url.value}")
                }

                connection.inputStream.use { inputStream ->
                    readExactly(inputStream, 1)
                }
                return PayloadUrlChannel(
                    url.value,
                    fileSize,
                    url.redirectsRemaining,
                )
            } finally {
                connection.disconnect()
            }
        }
    }
}
