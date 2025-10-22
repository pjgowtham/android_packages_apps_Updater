/*
 * SPDX-FileCopyrightText: 2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.model

import java.io.File

/**
 * Base interface for update information.
 */
interface UpdateBaseInfo {
    var downloadId: String?
    var downloadUrl: String?
    var fileSize: Long
    var name: String?
    var timestamp: Long
    var type: String?
    var version: String?
}

/**
 * Interface for an update, including its state.
 */
interface UpdateInfo : UpdateBaseInfo {
    var availableOnline: Boolean
    var eta: Long
    var file: File?
    var finalizing: Boolean // Renamed from isFinalizing
    var installProgress: Int
    var persistentStatus: Int
    var progress: Int
    var speed: Long
    var status: UpdateStatus
}

/**
 * Data class representing an update and its state.
 *
 * This class implements UpdateInfo and replaces UpdateBase and Update Java classes.
 */
data class Update(
    // UpdateBaseInfo properties
    override var downloadId: String? = null,
    override var downloadUrl: String? = null,
    override var fileSize: Long = 0,
    override var name: String? = null,
    override var timestamp: Long = 0,
    override var type: String? = null,
    override var version: String? = null,

    // UpdateInfo properties
    override var availableOnline: Boolean = false,
    override var eta: Long = 0,
    override var file: File? = null,
    override var finalizing: Boolean = false,
    override var installProgress: Int = 0,
    override var persistentStatus: Int = UpdateStatus.Persistent.UNKNOWN,
    override var progress: Int = 0,
    override var speed: Long = 0,
    override var status: UpdateStatus = UpdateStatus.UNKNOWN
) : UpdateInfo {

    /**
     * Copy constructor from any UpdateInfo implementation.
     */
    constructor(update: UpdateInfo) : this(
        downloadId = update.downloadId,
        downloadUrl = update.downloadUrl,
        fileSize = update.fileSize,
        name = update.name,
        timestamp = update.timestamp,
        type = update.type,
        version = update.version,
        availableOnline = update.availableOnline,
        eta = update.eta,
        file = update.file,
        finalizing = update.finalizing,
        installProgress = update.installProgress,
        persistentStatus = update.persistentStatus,
        progress = update.progress,
        speed = update.speed,
        status = update.status
    )

    companion object {
        const val LOCAL_ID = "local"
    }
}
