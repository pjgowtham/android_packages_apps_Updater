/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.updates

import android.content.Context
import android.text.format.Formatter
import org.lineageos.updater.R
import org.lineageos.updater.controller.UpdaterController
import org.lineageos.updater.data.Update
import org.lineageos.updater.data.UpdateStatus
import org.lineageos.updater.misc.StringGenerator
import org.lineageos.updater.misc.Utils
import org.lineageos.updater.util.NetworkState

sealed class UpdateListProgressState {
    abstract val text: String

    data class Percent(override val text: String, val value: Int) : UpdateListProgressState()
    data class Indeterminate(override val text: String) : UpdateListProgressState()
}

sealed class UpdateListItemState {
    abstract val primaryAction: UpdateListPrimaryAction?
    abstract val menuActions: Set<UpdateListMenuAction>

    data class Idle(
        override val primaryAction: UpdateListPrimaryAction? = null,
        override val menuActions: Set<UpdateListMenuAction> = emptySet(),
    ) : UpdateListItemState()

    data class Active(
        override val primaryAction: UpdateListPrimaryAction? = null,
        val secondaryAction: UpdateListSecondaryAction? = null,
        val progress: UpdateListProgressState? = null,
        override val menuActions: Set<UpdateListMenuAction> = emptySet(),
    ) : UpdateListItemState()

    companion object {
        fun create(
            context: Context,
            update: Update,
            updaterController: UpdaterController,
            networkState: NetworkState,
        ): UpdateListItemState {
            val downloadId = update.downloadId

            val menuActions = buildSet {
                if (update.status == UpdateStatus.VERIFIED) {
                    add(UpdateListMenuAction.Delete)
                    add(UpdateListMenuAction.Export)
                }
                if (update.isAvailableOnline) {
                    add(UpdateListMenuAction.ViewDownloads)
                }
            }

            if (update.status.isInProgress) {
                return updaterController.inProgressState(context, update, networkState, menuActions)
            }

            if (updaterController.isWaitingForReboot(downloadId)) {
                return Active(
                    primaryAction = UpdateListPrimaryAction.Reboot,
                    menuActions = menuActions,
                )
            }

            val canInstall = Utils.canInstall(update)
            return Idle(
                primaryAction = when {
                    update.status == UpdateStatus.VERIFIED ->
                        if (canInstall) {
                            UpdateListPrimaryAction.Start(
                                operation = UpdateListOperation.Install,
                                enabled = !updaterController.isInstallingUpdate,
                            )
                        } else {
                            null // not installable on this device; delete is available via menu
                        }

                    canInstall ->
                        UpdateListPrimaryAction.Start(
                            operation = UpdateListOperation.Download,
                            enabled = !updaterController.hasActiveDownloads() &&
                                networkState.isOnline,
                        )

                    else ->
                        UpdateListPrimaryAction.Info()
                },
                menuActions = menuActions,
            )
        }
    }
}

private fun UpdaterController.inProgressState(
    context: Context,
    update: Update,
    networkState: NetworkState,
    menuActions: Set<UpdateListMenuAction>,
): UpdateListItemState.Active {
    val downloadId = update.downloadId
    val downloaded = Formatter.formatShortFileSize(context, update.file?.length() ?: 0L)
    val total = Formatter.formatShortFileSize(context, update.fileSize)

    if (isDownloading(downloadId)) {
        val progressText = if (update.eta > 0) {
            val etaString = StringGenerator.formatETA(context, update.eta * 1000)
            context.getString(
                R.string.list_download_progress_eta_newer,
                downloaded,
                total,
                etaString,
            )
        } else {
            context.getString(R.string.list_download_progress_newer, downloaded, total)
        }
        return UpdateListItemState.Active(
            primaryAction = UpdateListPrimaryAction.Pause(UpdateListOperation.Download),
            secondaryAction = UpdateListSecondaryAction.Cancel(UpdateListOperation.Download),
            progress = if (update.status == UpdateStatus.STARTING) {
                UpdateListProgressState.Indeterminate(progressText)
            } else {
                UpdateListProgressState.Percent(progressText, update.progress)
            },
            menuActions = menuActions,
        )
    }

    if (isInstallingUpdate(downloadId) || update.status == UpdateStatus.INSTALLATION_SUSPENDED) {
        val isAbUpdate = isInstallingABUpdate
        val installText = when {
            !isAbUpdate -> context.getString(R.string.dialog_prepare_zip_message)
            update.isFinalizing -> context.getString(R.string.finalizing_package)
            else -> context.getString(R.string.preparing_ota_first_boot)
        }
        // Non-A/B installs run in recovery and cannot be paused; only cancel is offered.
        return UpdateListItemState.Active(
            primaryAction = when {
                !isAbUpdate -> null
                update.status == UpdateStatus.INSTALLATION_SUSPENDED ->
                    UpdateListPrimaryAction.Resume(
                        operation = UpdateListOperation.Install,
                        enabled = true,
                    )
                else -> UpdateListPrimaryAction.Pause(UpdateListOperation.Install)
            },
            secondaryAction = UpdateListSecondaryAction.Cancel(UpdateListOperation.Install),
            progress = UpdateListProgressState.Percent(installText, update.installProgress),
            menuActions = menuActions,
        )
    }

    if (isVerifyingUpdate(downloadId)) {
        // Show Install disabled to signal what comes next after verification.
        return UpdateListItemState.Active(
            primaryAction = UpdateListPrimaryAction.Start(
                operation = UpdateListOperation.Install,
                enabled = false,
            ),
            progress = UpdateListProgressState.Indeterminate(
                context.getString(R.string.list_verifying_update)
            ),
            menuActions = menuActions,
        )
    }

    return UpdateListItemState.Active(
        primaryAction = UpdateListPrimaryAction.Resume(
            operation = UpdateListOperation.Download,
            enabled = networkState.isOnline,
        ),
        secondaryAction = UpdateListSecondaryAction.Cancel(UpdateListOperation.Download),
        progress = UpdateListProgressState.Percent(
            context.getString(R.string.list_download_progress_newer, downloaded, total),
            update.progress,
        ),
        menuActions = menuActions,
    )
}
