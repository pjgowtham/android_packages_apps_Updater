/*
 * SPDX-FileCopyrightText: 2017-2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.updater.model

import java.io.File

/**
 * Interface for an update's full state, including dynamic status.
 */
interface UpdateInfo : UpdateBaseInfo {
    var availableOnline: Boolean
    var eta: Long
    var file: File?
    var installProgress: Int
    var isFinalizing: Boolean
    var persistentStatus: Int
    var progress: Int
    var speed: Long
    var status: UpdateStatus
}
