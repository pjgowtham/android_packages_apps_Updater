/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Defines the table structure for storing update information.
 */
@Entity(
    tableName = "updates",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["status"])
    ]
)
data class UpdateEntity(
    @PrimaryKey
    @ColumnInfo(name = "download_id")
    val downloadId: String,

    @ColumnInfo(name = "size")
    val fileSize: Long,

    @ColumnInfo(name = "path")
    val path: String?,

    @ColumnInfo(name = "status")
    val persistentStatus: Int,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "type")
    val type: String,

    @ColumnInfo(name = "version")
    val version: String,
)

