/*
 * SPDX-FileCopyrightText: 2017-2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.updater.model

/**
 * Basic interface for an update's static information.
 */
interface UpdateBaseInfo {
    var downloadId: String
    var downloadUrl: String
    var fileSize: Long
    var name: String
    var timestamp: Long
    var type: String
    var version: String
}
