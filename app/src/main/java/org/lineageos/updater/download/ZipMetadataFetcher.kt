/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

@file:JvmName("ZipMetadataFetcher")

package org.lineageos.updater.download

import android.util.Log
import org.lineageos.updater.misc.Constants
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fetches OTA streaming metadata from ZIP files.
 */

private const val TAG = "ZipMetadataFetcher"

// ZIP format constants
private const val EOCD_SIGNATURE = 0x06054b50
private const val EOCD_MIN_SIZE = 22
private const val EOCD_MAX_COMMENT = 65535
private const val EOCD_SEARCH_SIZE = EOCD_MIN_SIZE + EOCD_MAX_COMMENT

private const val CD_SIGNATURE = 0x02014b50
private const val CD_ENTRY_FIXED_SIZE = 46

private const val LOCAL_HEADER_FIXED_SIZE = 30
private const val LOCAL_HEADER_SIGNATURE = 0x04034b50

private const val COMPRESSION_STORED = 0

// Security limits based on actual OTA ZIP structure (~7 entries, ~150B properties)
private const val MAX_PAYLOAD_SIZE = 4L * 1024 * 1024 * 1024 // 32-bit ZIP max
private const val MAX_PROPERTIES_SIZE = 1024L
private const val MAX_CD_ENTRIES = 100

/** Parameters for [UpdateEngine.applyPayload][android.os.UpdateEngine.applyPayload]. */
data class StreamingMetadata(
    val headerKeyValuePairs: List<String>,
    val payloadOffset: Long,
    val payloadSize: Long,
    val streamUrl: String,
)

/**
 * Fetches streaming metadata for an OTA package at [downloadUrl].
 *
 * See [PKWARE APPNOTE.TXT Section 4.3](https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT).
 *
 * @throws IOException if mirror resolution, ZIP parsing, or entry lookup fails
 */
@Throws(IOException::class)
fun fetchMetadataSync(downloadUrl: String): StreamingMetadata {
    val resolvedUrl = handleDuplicateLinks(downloadUrl)

    val fileSize = getContentLength(resolvedUrl)
    require(fileSize > 0) { "Could not determine file size (got $fileSize)" }

    Log.d(TAG, "File size: $fileSize bytes")

    // Fetch the end of the file to locate EOCD
    val eocdData = fetchRange(
        resolvedUrl,
        maxOf(0, fileSize - EOCD_SEARCH_SIZE),
        fileSize - 1
    )

    val eocdOffset = findEocdSignature(eocdData)
        ?: throw IOException("EOCD signature not found - file may be corrupted")

    // Validate EOCD has minimum required size
    require(eocdData.size - eocdOffset >= EOCD_MIN_SIZE) { "Truncated EOCD record" }

    val eocdBuffer = ByteBuffer.wrap(eocdData, eocdOffset, eocdData.size - eocdOffset)
        .order(ByteOrder.LITTLE_ENDIAN)

    eocdBuffer.skip(10)
    val totalEntries = eocdBuffer.short.toUShort().toInt()
    val cdSize = eocdBuffer.int.toUInt().toLong()
    val cdOffset = eocdBuffer.int.toUInt().toLong()

    // Validate central directory parameters
    require(totalEntries <= MAX_CD_ENTRIES) {
        "Too many ZIP entries: $totalEntries (max $MAX_CD_ENTRIES)"
    }
    require(cdOffset + cdSize <= fileSize) { "Central directory extends beyond file size" }

    Log.d(TAG, "Central directory: offset=$cdOffset, size=$cdSize, entries=$totalEntries")

    val cdData = fetchRange(resolvedUrl, cdOffset, cdOffset + cdSize - 1)
    val cdBuffer = ByteBuffer.wrap(cdData).order(ByteOrder.LITTLE_ENDIAN)

    var payloadEntry: CdEntry? = null
    var propertiesEntry: CdEntry? = null

    for (i in 0 until totalEntries) {
        val entry = parseCdEntry(cdBuffer)
            ?: throw IOException("Failed to parse central directory entry $i")

        when (entry.name) {
            Constants.AB_PAYLOAD_BIN_PATH -> payloadEntry = entry
            Constants.AB_PAYLOAD_PROPERTIES_PATH -> propertiesEntry = entry
        }

        if (payloadEntry != null && propertiesEntry != null) break
    }

    requireNotNull(payloadEntry) { "${Constants.AB_PAYLOAD_BIN_PATH} not found in ZIP" }
    requireNotNull(propertiesEntry) { "${Constants.AB_PAYLOAD_PROPERTIES_PATH} not found in ZIP" }

    // Validate payload entry
    validateEntry(payloadEntry, MAX_PAYLOAD_SIZE, "payload.bin")

    // Validate properties entry
    validateEntry(propertiesEntry, MAX_PROPERTIES_SIZE, "payload_properties.txt")

    // Fetch and parse properties file
    val propsDataOffset = getDataOffsetFromLocalHeader(resolvedUrl, propertiesEntry)
    val propsData = fetchRange(
        resolvedUrl,
        propsDataOffset,
        propsDataOffset + propertiesEntry.compressedSize - 1
    )

    // Validate fetched size matches expected
    require(propsData.size.toLong() == propertiesEntry.compressedSize) {
        "Properties file size mismatch"
    }

    val propsText = String(propsData, Charsets.UTF_8)
    val headerKeyValuePairs = propsText.lines().filter { it.isNotBlank() }

    val payloadDataOffset = getDataOffsetFromLocalHeader(resolvedUrl, payloadEntry)

    Log.d(
        TAG, "Streaming metadata: url=$resolvedUrl, " +
                "payloadOffset=$payloadDataOffset, payloadSize=${payloadEntry.uncompressedSize}"
    )

    return StreamingMetadata(
        headerKeyValuePairs = headerKeyValuePairs,
        payloadOffset = payloadDataOffset,
        payloadSize = payloadEntry.uncompressedSize,
        streamUrl = resolvedUrl,
    )
}

/** Validates size limits, STORED compression, and size consistency. */
private fun validateEntry(entry: CdEntry, maxSize: Long, name: String) {
    // Check for ZIP bomb: unreasonably large uncompressed size
    require(entry.uncompressedSize <= maxSize) {
        "$name size exceeds maximum: ${entry.uncompressedSize} > $maxSize bytes"
    }

    // For streaming, we need uncompressed files
    require(entry.compressionMethod == COMPRESSION_STORED) {
        "$name must be uncompressed (method ${entry.compressionMethod})"
    }

    // Uncompressed files should have equal compressed/uncompressed sizes
    require(entry.compressedSize == entry.uncompressedSize) {
        "$name has mismatched sizes despite being uncompressed"
    }
}

/** Searches backwards for the EOCD signature as per ZIP spec. */
private fun findEocdSignature(data: ByteArray): Int? {
    val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    return (data.size - EOCD_MIN_SIZE downTo 0).firstOrNull {
        buf.getInt(it) == EOCD_SIGNATURE
    }
}

private data class CdEntry(
    val compressedSize: Long,
    val compressionMethod: Int,
    val localHeaderOffset: Long,
    val name: String,
    val uncompressedSize: Long,
)

/** Parses a single central directory entry from the buffer. */
private fun parseCdEntry(buffer: ByteBuffer): CdEntry? {
    if (buffer.remaining() < CD_ENTRY_FIXED_SIZE) return null

    val signature = buffer.int
    if (signature != CD_SIGNATURE) {
        Log.e(
            TAG,
            "Expected CD signature 0x${CD_SIGNATURE.toString(16)}, got 0x${signature.toString(16)}"
        )
        return null
    }

    buffer.skip(6)  // Skip version made by (2), version needed (2), flags (2)
    val compressionMethod = buffer.short.toUShort().toInt()
    buffer.skip(8)  // Skip time, date, CRC

    val compressedSize = buffer.int.toUInt().toLong()
    val uncompressedSize = buffer.int.toUInt().toLong()
    val nameLength = buffer.short.toUShort().toInt()
    val extraLength = buffer.short.toUShort().toInt()
    val commentLength = buffer.short.toUShort().toInt()

    buffer.skip(8)  // Skip disk number, internal attrs, first 4 bytes of external attrs

    val localHeaderOffset = buffer.int.toUInt().toLong()

    // Validate we have enough data for variable fields
    if (buffer.remaining() < nameLength + extraLength + commentLength) {
        Log.e(TAG, "Insufficient data for CD entry variable fields")
        return null
    }

    val nameBytes = ByteArray(nameLength)
    buffer.get(nameBytes)
    val name = String(nameBytes, Charsets.UTF_8)

    buffer.skip(extraLength + commentLength)

    return CdEntry(compressedSize, compressionMethod, localHeaderOffset, name, uncompressedSize)
}

/** Fetches local file header to compute data offset (may differ from CD). */
private fun getDataOffsetFromLocalHeader(url: String, entry: CdEntry): Long {
    val headerData = fetchRange(
        url,
        entry.localHeaderOffset,
        entry.localHeaderOffset + LOCAL_HEADER_FIXED_SIZE - 1
    )

    val buf = ByteBuffer.wrap(headerData).order(ByteOrder.LITTLE_ENDIAN)
    val sig = buf.int
    require(sig == LOCAL_HEADER_SIGNATURE) {
        "Invalid local file header signature for ${entry.name}: " +
                "expected 0x${LOCAL_HEADER_SIGNATURE.toString(16)}, " +
                "got 0x${sig.toString(16)}"
    }

    buf.position(26)
    val nameLen = buf.short.toUShort().toInt()
    val extraLen = buf.short.toUShort().toInt()

    return entry.localHeaderOffset + LOCAL_HEADER_FIXED_SIZE + nameLen + extraLen
}

private fun ByteBuffer.skip(count: Int) {
    position(position() + count)
}
