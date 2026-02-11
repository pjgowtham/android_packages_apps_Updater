/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.download

import android.util.Log
import org.lineageos.updater.misc.Constants
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fetches payload.bin offset and payload_properties.txt content from a remote
 * ZIP using HTTP Range requests — without downloading the full file.
 */
object ZipMetadataFetcher {

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

    // Compression methods
    private const val COMPRESSION_STORED = 0 // Uncompressed
    private const val COMPRESSION_DEFLATED = 8 // Deflate compression

    // Security limits
    private const val MAX_PAYLOAD_SIZE = 5L * 1024 * 1024 * 1024 // 5GB
    private const val MAX_PROPERTIES_SIZE = 10L * 1024 * 1024 // 10MB
    private const val MAX_CD_ENTRIES = 100000 // Reasonable limit for OTA packages

    data class StreamingMetadata(
        val streamUrl: String,
        val payloadOffset: Long,
        val payloadSize: Long,
        val headerKeyValuePairs: List<String>,
    )

    @JvmStatic
    @Throws(IOException::class)
    fun fetchMetadataSync(downloadUrl: String): StreamingMetadata {
        val resolvedUrl = HttpUtils.resolveMirrorUrl(downloadUrl)

        val fileSize = HttpUtils.getContentLength(resolvedUrl)
        if (fileSize <= 0) {
            throw IOException("Could not determine file size (got $fileSize)")
        }
        
        Log.d(TAG, "File size: $fileSize bytes")

        // Fetch the end of the file to locate EOCD
        val eocdData = HttpUtils.fetchRange(
            resolvedUrl,
            maxOf(0, fileSize - EOCD_SEARCH_SIZE),
            fileSize - 1
        )

        val eocdOffset = findEocdSignature(eocdData)
            ?: throw IOException("EOCD signature not found - file may be corrupted")

        // Validate EOCD has minimum required size
        if (eocdData.size - eocdOffset < EOCD_MIN_SIZE) {
            throw IOException("Truncated EOCD record")
        }

        val eocdBuffer = ByteBuffer.wrap(eocdData, eocdOffset, eocdData.size - eocdOffset)
            .order(ByteOrder.LITTLE_ENDIAN)

        eocdBuffer.position(eocdOffset + 10)
        val totalEntries = eocdBuffer.short.toUShort().toInt()
        val cdSize = eocdBuffer.int.toUInt().toLong()
        val cdOffset = eocdBuffer.int.toUInt().toLong()

        // Validate central directory parameters
        if (totalEntries > MAX_CD_ENTRIES) {
            throw IOException("Too many ZIP entries: $totalEntries (max $MAX_CD_ENTRIES)")
        }
        if (cdOffset + cdSize > fileSize) {
            throw IOException("Central directory extends beyond file size")
        }

        Log.d(TAG, "Central directory: offset=$cdOffset, size=$cdSize, entries=$totalEntries")

        val cdData = HttpUtils.fetchRange(resolvedUrl, cdOffset, cdOffset + cdSize - 1)
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

        if (payloadEntry == null) {
            throw IOException("${Constants.AB_PAYLOAD_BIN_PATH} not found in ZIP")
        }
        if (propertiesEntry == null) {
            throw IOException("${Constants.AB_PAYLOAD_PROPERTIES_PATH} not found in ZIP")
        }

        // Validate payload entry
        validateEntry(payloadEntry, MAX_PAYLOAD_SIZE, "payload.bin")
        
        // Validate properties entry
        validateEntry(propertiesEntry, MAX_PROPERTIES_SIZE, "payload_properties.txt")

        // Fetch and parse properties file
        val propsDataOffset = getDataOffsetFromLocalHeader(resolvedUrl, propertiesEntry)
        val propsData = HttpUtils.fetchRange(
            resolvedUrl,
            propsDataOffset,
            propsDataOffset + propertiesEntry.compressedSize - 1
        )
        
        // Validate fetched size matches expected
        if (propsData.size.toLong() != propertiesEntry.compressedSize) {
            throw IOException("Properties file size mismatch")
        }
        
        val propsText = String(propsData, Charsets.UTF_8)
        val headerKeyValuePairs = propsText.lines().filter { it.isNotBlank() }

        val payloadDataOffset = getDataOffsetFromLocalHeader(resolvedUrl, payloadEntry)

        Log.d(
            TAG, "Streaming metadata: url=$resolvedUrl, " +
                    "payloadOffset=$payloadDataOffset, payloadSize=${payloadEntry.uncompressedSize}"
        )

        return StreamingMetadata(
            streamUrl = resolvedUrl,
            payloadOffset = payloadDataOffset,
            payloadSize = payloadEntry.uncompressedSize,
            headerKeyValuePairs = headerKeyValuePairs,
        )
    }

    /**
     * Validates a ZIP entry for security and correctness.
     */
    private fun validateEntry(entry: CdEntry, maxSize: Long, name: String) {
        // Check for ZIP bomb: unreasonably large uncompressed size
        if (entry.uncompressedSize > maxSize) {
            throw IOException(
                "$name size exceeds maximum: ${entry.uncompressedSize} > $maxSize bytes"
            )
        }
        
        // For streaming, we need uncompressed files
        if (entry.compressionMethod != COMPRESSION_STORED) {
            throw IOException(
                "$name must be uncompressed (method ${entry.compressionMethod})"
            )
        }
        
        // Uncompressed files should have equal compressed/uncompressed sizes
        if (entry.compressedSize != entry.uncompressedSize) {
            throw IOException(
                "$name has mismatched sizes despite being uncompressed"
            )
        }
    }

    /**
     * Searches for the EOCD signature in the provided data.
     * Searches backwards from the end as per ZIP spec.
     */
    private fun findEocdSignature(data: ByteArray): Int? {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        return (data.size - EOCD_MIN_SIZE downTo 0).firstOrNull { 
            buf.getInt(it) == EOCD_SIGNATURE 
        }
    }

    private data class CdEntry(
        val name: String,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
        val compressionMethod: Int,
    )

    /**
     * Parses a single central directory entry from the buffer.
     */
    private fun parseCdEntry(buffer: ByteBuffer): CdEntry? {
        if (buffer.remaining() < CD_ENTRY_FIXED_SIZE) return null

        val signature = buffer.int
        if (signature != CD_SIGNATURE) {
            Log.e(TAG, "Expected CD signature 0x${CD_SIGNATURE.toString(16)}, got 0x${signature.toString(16)}")
            return null
        }

        buffer.position(buffer.position() + 4)  // Skip version made by (2), version needed (2)
        val flags = buffer.short.toUShort().toInt()
        val compressionMethod = buffer.short.toUShort().toInt()
        buffer.position(buffer.position() + 8)  // Skip time, date, CRC

        val compressedSize = buffer.int.toUInt().toLong()
        val uncompressedSize = buffer.int.toUInt().toLong()
        val nameLength = buffer.short.toUShort().toInt()
        val extraLength = buffer.short.toUShort().toInt()
        val commentLength = buffer.short.toUShort().toInt()

        buffer.position(buffer.position() + 8)  // Skip disk number, internal attrs, first 4 bytes of external attrs

        val localHeaderOffset = buffer.int.toUInt().toLong()

        // Validate we have enough data for variable fields
        if (buffer.remaining() < nameLength + extraLength + commentLength) {
            Log.e(TAG, "Insufficient data for CD entry variable fields")
            return null
        }

        val nameBytes = ByteArray(nameLength)
        buffer.get(nameBytes)
        val name = String(nameBytes, Charsets.UTF_8)

        buffer.position(buffer.position() + extraLength + commentLength)

        return CdEntry(name, compressedSize, uncompressedSize, localHeaderOffset, compressionMethod)
    }

    /**
     * Fetch the local file header to compute the actual data offset.
     * The local header may have different name/extra lengths than the central directory.
     */
    private fun getDataOffsetFromLocalHeader(url: String, entry: CdEntry): Long {
        val headerData = HttpUtils.fetchRange(
            url,
            entry.localHeaderOffset,
            entry.localHeaderOffset + LOCAL_HEADER_FIXED_SIZE - 1
        )

        val buf = ByteBuffer.wrap(headerData).order(ByteOrder.LITTLE_ENDIAN)
        val sig = buf.int
        if (sig != LOCAL_HEADER_SIGNATURE) {
            throw IOException(
                "Invalid local file header signature for ${entry.name}: " +
                "expected 0x${LOCAL_HEADER_SIGNATURE.toString(16)}, " +
                "got 0x${sig.toString(16)}"
            )
        }

        buf.position(26)
        val nameLen = buf.short.toUShort().toInt()
        val extraLen = buf.short.toUShort().toInt()

        return entry.localHeaderOffset + LOCAL_HEADER_FIXED_SIZE + nameLen + extraLen
    }
}