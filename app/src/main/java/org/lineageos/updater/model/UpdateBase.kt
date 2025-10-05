/*
 * SPDX-FileCopyrightText: 2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.model

/**
 * A base class for an update.
 */
open class UpdateBase(
    override var downloadId: String = "",
    override var downloadUrl: String = "",
    override var fileSize: Long = 0L,
    override var name: String = "",
    override var timestamp: Long = 0L,
    override var type: String = "",
    override var version: String = "",
) : UpdateBaseInfo {

    constructor(update: UpdateBaseInfo) : this(
        downloadId = update.downloadId,
        downloadUrl = update.downloadUrl,
        fileSize = update.fileSize,
        name = update.name,
        timestamp = update.timestamp,
        type = update.type,
        version = update.version,
    )
}
