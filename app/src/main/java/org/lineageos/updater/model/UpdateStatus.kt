/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.model

/**
 * Status codes for updates.
 *
 * Some statuses are mapped from UpdateEngine, while others are specific to this app.
 *
 * unused UpdateEngine statuses:
 * - IDLE
 * - CHECKING_FOR_UPDATE
 * - FINALIZING
 * - REPORTING_ERROR_EVENT
 * - ATTEMPTING_ROLLBACK
 * - DISABLED
 */
enum class UpdateStatus {
    UNKNOWN,
    STARTING,
    UPDATE_AVAILABLE,
    DOWNLOADING,
    PAUSED,
    PAUSED_ERROR,
    DELETED,
    VERIFYING,
    VERIFIED,
    VERIFICATION_FAILED,
    INSTALLING,
    UPDATED_NEED_REBOOT,
    INSTALLATION_FAILED,
    INSTALLATION_CANCELLED,
    INSTALLATION_SUSPENDED;
}
