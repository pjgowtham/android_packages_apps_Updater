/*
 * SPDX-FileCopyrightText: 2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.model

enum class UpdateStatus {
    UNKNOWN,
    STARTING,
    DOWNLOADING,
    PAUSED,
    PAUSED_ERROR,
    DELETED,
    VERIFYING,
    VERIFIED,
    VERIFICATION_FAILED,
    INSTALLING,
    INSTALLED,
    INSTALLATION_FAILED,
    INSTALLATION_CANCELLED,
    INSTALLATION_SUSPENDED;

    /**
     * Persistent status constants, moved from the inner Java class.
     */
    object Persistent {
        const val UNKNOWN = 0
        const val INCOMPLETE = 1
        const val VERIFIED = 2
    }
}
