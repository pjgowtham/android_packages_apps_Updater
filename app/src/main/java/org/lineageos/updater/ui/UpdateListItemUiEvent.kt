/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.ui

sealed class UpdateListItemUiEvent {

    /**
     * Another download is already active. [onConfirm] resumes the switch-download flow;
     * call it after the user taps OK.
     */
    data class ShowSwitchDownloadDialog(
        val onConfirm: () -> Unit,
    ) : UpdateListItemUiEvent()

    /**
     * The network is metered and the warning preference is enabled. [onConfirm] proceeds
     * with the operation (download, resume, or streaming install); call it after the user
     * taps OK.
     */
    data class ShowMeteredNetworkWarning(
        val onConfirm: () -> Unit,
    ) : UpdateListItemUiEvent()

    /**
     * [onConfirm] triggers the actual install; call it after the user taps OK.
     */
    data class ShowInstallConfirmDialog(
        val messageRes: Int,
        val buildInfo: String,
        val onConfirm: () -> Unit,
    ) : UpdateListItemUiEvent()

    data object ShowBatteryLowDialog : UpdateListItemUiEvent()

    data object ShowScratchMountedDialog : UpdateListItemUiEvent()

    /** Message is pre-formatted and contains a URL — render with ClickableText / LinkMovementMethod. */
    data class ShowInfoDialog(val message: String) : UpdateListItemUiEvent()

    /**
     * [onConfirm] cancels the download; call it after the user taps OK.
     */
    data class ShowCancelDownloadDialog(
        val onConfirm: () -> Unit,
    ) : UpdateListItemUiEvent()

    /**
     * [onConfirm] stops the installation; call it after the user taps OK.
     */
    data class ShowCancelInstallationDialog(
        val onConfirm: () -> Unit,
    ) : UpdateListItemUiEvent()

    /**
     * [onConfirm] deletes the update; call it after the user taps OK.
     */
    data class ShowDeleteDialog(
        val onConfirm: () -> Unit,
    ) : UpdateListItemUiEvent()

    data object ShowNotInstallableToast : UpdateListItemUiEvent()

    data class ShowStatusToast(val messageRes: Int) : UpdateListItemUiEvent()
}
