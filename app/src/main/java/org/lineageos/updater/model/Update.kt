/*
 * SPDX-FileCopyrightText: 2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.model

import java.io.File

/**
 * Represents a system update, with its online and local status.
 */
class Update : UpdateBase, UpdateInfo {

    // Reverted to a mutable 'var' to restore the setAvailableOnline() method for Java
    override var availableOnline: Boolean = false

    override var eta: Long = 0L
    override lateinit var file: File
    override var finalizing: Boolean = false
    override var installProgress: Int = 0
    override var persistentStatus: Int = UpdateStatus.Persistent.UNKNOWN
    override var progress: Int = 0
    override var speed: Long = 0L
    override var status: UpdateStatus = UpdateStatus.UNKNOWN

    constructor() : super()

    constructor(update: UpdateInfo) : super(update) {
        this.availableOnline = update.availableOnline
        this.eta = update.eta
        if (update is Update && update::file.isInitialized) {
            this.file = update.file
        }
        this.finalizing = update.finalizing
        this.installProgress = update.installProgress
        this.persistentStatus = update.persistentStatus
        this.progress = update.progress
        this.speed = update.speed
        this.status = update.status
    }

    companion object {
        const val LOCAL_ID = "local"
    }
}
