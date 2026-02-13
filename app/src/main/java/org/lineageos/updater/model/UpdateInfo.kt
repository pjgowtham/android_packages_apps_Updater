/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.model

import java.io.File

data class UpdateInfo(
    var availableOnline: Boolean = false,
    var downloadId: String = "",
    var downloadUrl: String = "",
    var eta: Long = 0,
    var file: File? = null,
    var fileSize: Long = 0,
    var finalizing: Boolean = false,
    var installProgress: Int = 0,
    var name: String = "",
    var progress: Int = 0,
    var speed: Long = 0,
    var status: UpdateStatus = UpdateStatus.UNKNOWN,
    var timestamp: Long = 0,
    var type: String = "",
    var version: String = "",
) {

    constructor(update: UpdateInfo) : this(
        availableOnline = update.availableOnline,
        downloadId = update.downloadId,
        downloadUrl = update.downloadUrl,
        eta = update.eta,
        file = update.file,
        fileSize = update.fileSize,
        finalizing = update.finalizing,
        installProgress = update.installProgress,
        name = update.name,
        progress = update.progress,
        speed = update.speed,
        status = update.status,
        timestamp = update.timestamp,
        type = update.type,
        version = update.version
    )

    companion object {
        const val LOCAL_ID = "local"
    }
}
