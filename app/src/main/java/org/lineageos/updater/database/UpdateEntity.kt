/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import org.lineageos.updater.model.UpdateInfo
import org.lineageos.updater.model.UpdateStatus
import java.io.File

/**
 * Room entity mapping to the `updates` table.
 *
 * Only the Data Layer should use this class directly.
 * All other layers should use [UpdateInfo].
 */
@Entity(tableName = "updates")
data class UpdateEntity(
    @PrimaryKey
    @ColumnInfo(name = "download_id")
    val downloadId: String,

    @ColumnInfo(name = "status")
    val status: Int,

    @ColumnInfo(name = "path")
    val path: String?,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "type")
    val type: String?,

    @ColumnInfo(name = "version")
    val version: String,

    @ColumnInfo(name = "size")
    val size: Long,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "download_url")
    val downloadUrl: String?
)

fun UpdateInfo.toEntity() = UpdateEntity(
    downloadId = downloadId,
    status = status.persistentStatus,
    path = file?.absolutePath,
    timestamp = timestamp,
    type = type,
    version = version,
    size = fileSize,
    name = name,
    downloadUrl = downloadUrl
)

fun UpdateEntity.toUpdateInfo() = UpdateInfo(
    downloadId = downloadId,
    status = UpdateStatus.fromPersistentStatus(status),
    file = path?.let { File(it) },
    timestamp = timestamp,
    type = type,
    version = version,
    fileSize = size,
    name = name,
    downloadUrl = downloadUrl
)
