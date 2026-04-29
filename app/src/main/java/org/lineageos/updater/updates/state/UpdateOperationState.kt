/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.updates.state

import androidx.annotation.StringRes
import org.lineageos.updater.R
import org.lineageos.updater.controller.UpdaterController
import org.lineageos.updater.data.Update
import org.lineageos.updater.data.UpdateStatus
import org.lineageos.updater.deviceinfo.DeviceInfoUtils
import org.lineageos.updater.misc.Utils

/**
 * Snapshot of the controller's per-update state, queried once and shared across
 * the action resolver and state mapper to avoid duplicate controller calls.
 */
data class UpdateOperationState(
    val isDownloading: Boolean,
    val isDownloadPaused: Boolean,
    val isVerifying: Boolean,
    val isVerified: Boolean,
    val canInstall: Boolean,
    val isABInstall: Boolean,
    val isInstalling: Boolean,
    val isInstallationSuspended: Boolean,
    val isWaitingForReboot: Boolean,
    val isFullyDownloaded: Boolean,
    val isLocal: Boolean,
    val isBusy: Boolean,
    @param:StringRes val statusRes: Int?,
) {
    val canDelete: Boolean
        get() = isFullyDownloaded && !isVerifying && !isInstalling

    val canExport: Boolean
        get() = isVerified && !isLocal

    companion object {
        fun from(controller: UpdaterController, update: Update): UpdateOperationState {
            val downloadId = update.downloadId

            val isDownloading = controller.isDownloading(downloadId)
            val isDownloadPaused = update.status == UpdateStatus.PAUSED ||
                    update.status == UpdateStatus.PAUSED_ERROR
            val isDownloadError = update.status == UpdateStatus.PAUSED_ERROR

            val isVerifying = controller.isVerifyingUpdate(downloadId)
            val isVerified = update.status == UpdateStatus.VERIFIED
            val isVerificationFailed = update.status == UpdateStatus.VERIFICATION_FAILED

            val isInstalling = controller.isInstallingUpdate(downloadId)
            val isInstallationSuspended =
                update.status == UpdateStatus.INSTALLATION_SUSPENDED
            val isInstallationFailed = update.status == UpdateStatus.INSTALLATION_FAILED

            val isWaitingForReboot = controller.isWaitingForReboot(downloadId)
            val isExporting = false
            val isLocal = downloadId == Update.LOCAL_ID

            val statusRes = when {
                isDownloading -> R.string.downloading_notification
                isDownloadError -> R.string.download_paused_error_notification
                isDownloadPaused -> R.string.download_paused_notification

                isVerifying -> R.string.list_verifying_update
                isVerificationFailed -> R.string.verification_failed_notification

                isInstallationSuspended -> R.string.installation_suspended_notification
                isInstalling && !DeviceInfoUtils.isABDevice ->
                    R.string.dialog_prepare_zip_message
                isInstalling && update.isFinalizing ->
                    R.string.finalizing_package
                isInstalling ->
                    R.string.preparing_ota_first_boot
                isInstallationFailed -> R.string.update_failed_notification

                isWaitingForReboot -> R.string.installing_update_finished
                isExporting -> R.string.dialog_export_title
                isVerified -> R.string.download_completed_notification

                else -> null
            }

            return UpdateOperationState(
                isDownloading = isDownloading,
                isDownloadPaused = isDownloadPaused,
                isVerifying = isVerifying,
                isVerified = isVerified,
                canInstall = Utils.canInstall(update) || isLocal,
                isABInstall = controller.isInstallingABUpdate(),
                isInstalling = isInstalling || isInstallationSuspended,
                isInstallationSuspended = isInstallationSuspended,
                isWaitingForReboot = isWaitingForReboot,
                isFullyDownloaded = controller.isFullyDownloaded(update),
                isLocal = isLocal,
                isBusy = controller.isBusy(),
                statusRes = statusRes,
            )
        }
    }
}
