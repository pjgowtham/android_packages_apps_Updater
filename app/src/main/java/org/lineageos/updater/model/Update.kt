/*
 * SPDX-FileCopyrightText: 2017-2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.updater.model

import java.io.File

/**
 * Data class representing a system update, holding all its state.
 * This replaces Update.java, UpdateBase.java, and implements the Kotlin interfaces.
 */
data class Update(
    // UpdateBaseInfo
    override var downloadId: String,
    override var downloadUrl: String,
    override var fileSize: Long,
    override var name: String,
    override var timestamp: Long,
    override var type: String,
    override var version: String,

    // UpdateInfo
    override var availableOnline: Boolean = false,
    override var eta: Long = 0,
    override var file: File? = null,
    override var installProgress: Int = 0,
    override var isFinalizing: Boolean = false,
    override var persistentStatus: Int = UpdateStatus.Persistent.UNKNOWN,
    override var progress: Int = 0,
    override var speed: Long = 0,
    override var status: UpdateStatus = UpdateStatus.UNKNOWN,
) : UpdateInfo {
    companion object {
        const val LOCAL_ID = "local"

        /**
         * Creates a new Update instance from any UpdateInfo object.
         * Replaces the old Update(UpdateInfo) constructor.
         */
        fun from(updateInfo: UpdateInfo) = Update(
            downloadId = updateInfo.downloadId,
            downloadUrl = updateInfo.downloadUrl,
            fileSize = updateInfo.fileSize,
            name = updateInfo.name,
            timestamp = updateInfo.timestamp,
            type = updateInfo.type,
            version = updateInfo.version,
            availableOnline = updateInfo.availableOnline,
            eta = updateInfo.eta,
            file = updateInfo.file,
            installProgress = updateInfo.installProgress,
            isFinalizing = updateInfo.isFinalizing,
            persistentStatus = updateInfo.persistentStatus,
            progress = updateInfo.progress,
            speed = updateInfo.speed,
            status = updateInfo.status
        )
    }
}
