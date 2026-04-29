/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.updates.action

import org.lineageos.updater.R
import org.lineageos.updater.controller.UpdaterController
import org.lineageos.updater.data.Update
import org.lineageos.updater.updates.state.UpdateOperationState
import org.lineageos.updater.util.NetworkState

class UpdateActionResolver(
    private val updaterController: UpdaterController,
    private val networkState: NetworkState,
) {

    private val downloadSwitchConfirmation
        get() = if (updaterController.hasActiveDownloads()) {
            UpdateActionConfirmation(
                titleRes = R.string.download_switch_confirm_title,
                messageRes = R.string.download_switch_confirm_message,
            )
        } else {
            null
        }

    fun resolve(update: Update, state: UpdateOperationState): UpdateActions {
        var secondary: UpdateAction? = null

        val primary = when {
            state.isDownloading -> {
                secondary = action(UpdateActionType.CANCEL_DOWNLOAD)
                action(UpdateActionType.PAUSE_DOWNLOAD)
            }

            state.isDownloadPaused -> {
                secondary = action(UpdateActionType.CANCEL_DOWNLOAD)
                action(
                    type = UpdateActionType.RESUME_DOWNLOAD,
                    enabled = networkState.isOnline,
                    confirmation = downloadSwitchConfirmation,
                )
            }

            state.isVerifying -> {
                action(
                    type = UpdateActionType.START_INSTALL,
                    enabled = false,
                )
            }

            state.isVerified -> {
                if (state.canInstall) {
                    action(
                        type = UpdateActionType.START_INSTALL,
                        enabled = !state.isBusy,
                    )
                } else if (state.canDelete) {
                    action(
                        type = UpdateActionType.DELETE,
                        enabled = !state.isBusy,
                    )
                } else {
                    action(
                        type = UpdateActionType.SHOW_INFO,
                        enabled = !state.isBusy,
                    )
                }
            }

            state.isInstalling && !state.isInstallationSuspended -> {
                if (state.isABInstall) {
                    secondary = action(UpdateActionType.CANCEL_INSTALL)
                    action(UpdateActionType.PAUSE_INSTALL)
                } else {
                    action(UpdateActionType.CANCEL_INSTALL)
                }
            }

            state.isInstallationSuspended -> {
                if (state.isABInstall) {
                    secondary = action(UpdateActionType.CANCEL_INSTALL)
                    action(UpdateActionType.RESUME_INSTALL)
                } else {
                    action(UpdateActionType.CANCEL_INSTALL)
                }
            }

            state.isWaitingForReboot -> {
                action(UpdateActionType.REBOOT)
            }

            !state.canInstall -> {
                action(
                    type = UpdateActionType.SHOW_INFO,
                    enabled = !state.isBusy,
                )
            }

            else -> {
                val canDownload = networkState.isOnline && update.downloadUrl != null
                action(
                    type = UpdateActionType.START_DOWNLOAD,
                    enabled = canDownload,
                    confirmation = downloadSwitchConfirmation,
                )
            }
        }

        return UpdateActions(
            primary = primary,
            secondary = secondary,
            overflow = overflowActions(
                update = update,
                state = state,
                canDelete = state.canDelete && primary.type != UpdateActionType.DELETE,
            ),
        )
    }

    private fun overflowActions(
        update: Update,
        state: UpdateOperationState,
        canDelete: Boolean,
    ): List<UpdateAction> {
        val actions = mutableListOf<UpdateAction>()

        if (state.isVerified) {
            actions += action(UpdateActionType.EXPORT)
        }
        if (canDelete) {
            actions += action(
                type = UpdateActionType.DELETE,
                enabled = !state.isBusy,
            )
        }
        if (update.isAvailableOnline) {
            actions += action(
                type = UpdateActionType.VIEW_DOWNLOADS,
                titleArgRes = listOf(R.string.brand_name),
            )
        }

        return actions
    }

    private fun action(
        type: UpdateActionType,
        titleArgRes: List<Int> = emptyList(),
        enabled: Boolean = true,
        confirmation: UpdateActionConfirmation? = type.confirmation,
    ) = UpdateAction(
        type = type,
        titleArgRes = titleArgRes,
        enabled = enabled,
        confirmation = confirmation,
    )
}
