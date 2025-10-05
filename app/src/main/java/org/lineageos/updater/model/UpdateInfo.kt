/*
 * SPDX-FileCopyrightText: 2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.model

import java.io.File

/**
 * Represents a system update. This extends [UpdateBaseInfo] with information
 * about the local file, and the download/installation progress.
 */
interface UpdateInfo : UpdateBaseInfo {
    val availableOnline: Boolean
    val eta: Long
    val file: File
    override val fileSize: Long
    val finalizing: Boolean
    val installProgress: Int
    val persistentStatus: Int
    val progress: Int
    val speed: Long
    val status: UpdateStatus
}
