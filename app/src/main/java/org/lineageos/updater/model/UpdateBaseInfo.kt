/*
 * SPDX-FileCopyrightText: 2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.model

/**
 * This is the base interface that is shared between a remote update and a
 * downloaded update.
 */
interface UpdateBaseInfo {
    val downloadId: String
    val downloadUrl: String
    val fileSize: Long
    val name: String
    val timestamp: Long
    val type: String
    val version: String
}
