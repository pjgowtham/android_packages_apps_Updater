/*
 * SPDX-FileCopyrightText: 2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.controller

/**
 * Common interface for update installation strategies (AB and Legacy).
 * Provides default no-op implementations for features specific to one engine.
 */
interface UpdateInstaller {
    fun cancel()

    fun install(downloadId: String)

    fun reconnect() {}

    fun resume() {}

    fun setPerformanceMode(enable: Boolean) {}

    fun suspend() {}

    companion object {
        const val STATUS_INSTALLING = 0
        const val STATUS_SUSPENDED = 1
    }
}
