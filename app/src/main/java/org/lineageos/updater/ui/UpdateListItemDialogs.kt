/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.ui

import android.widget.Toast
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.android.settingslib.spa.widget.dialog.AlertDialogButton
import com.android.settingslib.spa.widget.dialog.rememberAlertDialogPresenter
import org.lineageos.updater.R
import org.lineageos.updater.viewmodel.UpdateListItemViewModel

/**
 * Observes [UpdateListItemViewModel.uiEvents] and renders any dialog or toast
 * produced by the ViewModel. Uses SPA's [rememberAlertDialogPresenter] for
 * consistent styling with the Settings app.
 */
@Composable
fun UpdateListItemDialogs(viewModel: UpdateListItemViewModel) {
    val context = LocalContext.current

    // Shared confirm callback — set before opening each presenter.
    // Only one dialog is ever active at a time, so sharing is safe.
    var onConfirmAction by remember { mutableStateOf<() -> Unit>({}) }

    // Dynamic text for dialogs that carry runtime data
    var installDialogMessage by remember { mutableStateOf("") }
    var infoDialogMessage by remember { mutableStateOf("") }

    val okText = stringResource(android.R.string.ok)
    val cancelText = stringResource(android.R.string.cancel)

    // ── Confirm + Dismiss presenters ──────────────────────────────────

    val switchDownloadPresenter = rememberAlertDialogPresenter(
        confirmButton = AlertDialogButton(okText) { onConfirmAction() },
        dismissButton = AlertDialogButton(cancelText),
        title = stringResource(R.string.download_switch_confirm_title),
        text = { Text(stringResource(R.string.download_switch_confirm_message)) },
    )

    val meteredNetworkPresenter = rememberAlertDialogPresenter(
        confirmButton = AlertDialogButton(okText) { onConfirmAction() },
        dismissButton = AlertDialogButton(cancelText),
        title = stringResource(R.string.update_over_metered_network_title),
        text = { Text(stringResource(R.string.update_over_metered_network_message)) },
    )

    val installConfirmPresenter = rememberAlertDialogPresenter(
        confirmButton = AlertDialogButton(okText) { onConfirmAction() },
        dismissButton = AlertDialogButton(cancelText),
        title = stringResource(R.string.apply_update_dialog_title),
        text = { Text(installDialogMessage) },
    )

    val cancelDownloadPresenter = rememberAlertDialogPresenter(
        confirmButton = AlertDialogButton(okText) { onConfirmAction() },
        dismissButton = AlertDialogButton(cancelText),
        title = stringResource(R.string.confirm_cancel_dialog_title),
        text = { Text(stringResource(R.string.confirm_cancel_dialog_message)) },
    )

    val cancelInstallPresenter = rememberAlertDialogPresenter(
        confirmButton = AlertDialogButton(okText) { onConfirmAction() },
        dismissButton = AlertDialogButton(cancelText),
        title = stringResource(R.string.cancel_installation_dialog_title),
        text = { Text(stringResource(R.string.cancel_installation_dialog_message)) },
    )

    val deletePresenter = rememberAlertDialogPresenter(
        confirmButton = AlertDialogButton(okText) { onConfirmAction() },
        dismissButton = AlertDialogButton(cancelText),
        title = stringResource(R.string.confirm_delete_dialog_title),
        text = { Text(stringResource(R.string.confirm_delete_dialog_message)) },
    )

    // ── Confirm-only presenters ───────────────────────────────────────

    val batteryLowPresenter = rememberAlertDialogPresenter(
        confirmButton = AlertDialogButton(okText),
        title = stringResource(R.string.dialog_battery_low_title),
        text = {
            val dischargingMin = context.resources.getInteger(
                R.integer.battery_ok_percentage_discharging,
            )
            val chargingMin = context.resources.getInteger(
                R.integer.battery_ok_percentage_charging,
            )
            Text(
                stringResource(
                    R.string.dialog_battery_low_message_pct,
                    dischargingMin,
                    chargingMin,
                ),
            )
        },
    )

    val scratchMountedPresenter = rememberAlertDialogPresenter(
        confirmButton = AlertDialogButton(okText),
        title = stringResource(R.string.dialog_scratch_mounted_title),
        text = { Text(stringResource(R.string.dialog_scratch_mounted_message)) },
    )

    val infoPresenter = rememberAlertDialogPresenter(
        confirmButton = AlertDialogButton(okText),
        title = stringResource(R.string.blocked_update_dialog_title),
        text = { Text(infoDialogMessage) },
    )

    // ── Event collector ───────────────────────────────────────────────

    LaunchedEffect(viewModel) {
        viewModel.uiEvents.collect { event ->
            when (event) {
                is UpdateListItemUiEvent.ShowSwitchDownloadDialog -> {
                    onConfirmAction = event.onConfirm
                    switchDownloadPresenter.open()
                }

                is UpdateListItemUiEvent.ShowMeteredNetworkWarning -> {
                    onConfirmAction = event.onConfirm
                    meteredNetworkPresenter.open()
                }

                is UpdateListItemUiEvent.ShowInstallConfirmDialog -> {
                    onConfirmAction = event.onConfirm
                    installDialogMessage = context.getString(
                        event.messageRes,
                        event.buildInfo,
                        context.getString(android.R.string.ok),
                    )
                    installConfirmPresenter.open()
                }

                is UpdateListItemUiEvent.ShowBatteryLowDialog -> {
                    batteryLowPresenter.open()
                }

                is UpdateListItemUiEvent.ShowScratchMountedDialog -> {
                    scratchMountedPresenter.open()
                }

                is UpdateListItemUiEvent.ShowInfoDialog -> {
                    infoDialogMessage = event.message
                    infoPresenter.open()
                }

                is UpdateListItemUiEvent.ShowCancelDownloadDialog -> {
                    onConfirmAction = event.onConfirm
                    cancelDownloadPresenter.open()
                }

                is UpdateListItemUiEvent.ShowCancelInstallationDialog -> {
                    onConfirmAction = event.onConfirm
                    cancelInstallPresenter.open()
                }

                is UpdateListItemUiEvent.ShowDeleteDialog -> {
                    onConfirmAction = event.onConfirm
                    deletePresenter.open()
                }

                is UpdateListItemUiEvent.ShowNotInstallableToast -> {
                    Toast.makeText(
                        context,
                        R.string.snack_update_not_installable,
                        Toast.LENGTH_LONG,
                    ).show()
                }

                is UpdateListItemUiEvent.ShowStatusToast -> {
                    Toast.makeText(context, event.messageRes, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
