/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.update

import android.content.Context
import android.content.Intent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import com.android.settingslib.spa.widget.dialog.AlertDialogButton
import com.android.settingslib.spa.widget.dialog.AlertDialogPresenter
import com.android.settingslib.spa.widget.dialog.rememberAlertDialogPresenter
import org.lineageos.updater.R
import org.lineageos.updater.controller.UpdaterController
import org.lineageos.updater.controller.UpdaterService
import org.lineageos.updater.data.Update
import org.lineageos.updater.misc.StringGenerator
import org.lineageos.updater.misc.Utils
import org.lineageos.updater.util.BatteryState
import org.lineageos.updater.util.InstallUtils
import org.lineageos.updater.util.NetworkState
import java.io.IOException
import java.text.DateFormat

/**
 * Owns all confirmation dialogs for the update list. Obtain an instance via
 * [rememberUpdateListDialogs] inside a composable.
 *
 * Each public method encodes the full decision tree for its action — pre-checks, state mutations,
 * and opening the correct SPA dialog — so [UpdateList] stays focused on layout.
 */
class UpdateListDialogs internal constructor(
    private val updaterController: UpdaterController?,
    private val networkState: NetworkState,
    private val meteredWarning: Boolean,
    private val batteryState: BatteryState,
    private val context: Context,
    // Dialog presenters — created by rememberUpdateListDialogs.
    private val deletePresenter: AlertDialogPresenter,
    private val cancelDownloadPresenter: AlertDialogPresenter,
    private val cancelInstallPresenter: AlertDialogPresenter,
    private val meteredPresenter: AlertDialogPresenter,
    private val switchPresenter: AlertDialogPresenter,
    private val batteryLowPresenter: AlertDialogPresenter,
    private val scratchPresenter: AlertDialogPresenter,
    private val installPresenter: AlertDialogPresenter,
    private val infoPresenter: AlertDialogPresenter,
    // Mutable state written before opening a dialog so the dialog's text lambda reads it.
    private val deleteTargetId: MutableState<String?>,
    private val cancelDownloadTargetId: MutableState<String?>,
    private val meteredTargetId: MutableState<String?>,
    private val meteredTargetIsResume: MutableState<Boolean>,
    private val switchTargetId: MutableState<String?>,
    private val switchIsResume: MutableState<Boolean>,
    private val installTargetId: MutableState<String?>,
    private val installIsAb: MutableState<Boolean>,
    private val infoTargetUrl: MutableState<String?>,
) {
    /** Show the "delete update" confirmation dialog. */
    fun confirmDelete(downloadId: String) {
        deleteTargetId.value = downloadId
        deletePresenter.open()
    }

    /** Show the "cancel download" confirmation dialog. */
    fun confirmCancelDownload(downloadId: String) {
        cancelDownloadTargetId.value = downloadId
        cancelDownloadPresenter.open()
    }

    /** Show the "cancel install" confirmation dialog. */
    fun confirmCancelInstall() = cancelInstallPresenter.open()

    /**
     * Start a new download, routing through the "switch active download" and/or
     * "metered network" dialogs as needed.
     */
    fun initiateDownload(downloadId: String) = initiateDownloadOrResume(downloadId, isResume = false)

    /**
     * Resume a paused download, routing through the "switch active download" and/or
     * "metered network" dialogs as needed.
     */
    fun initiateResume(downloadId: String) = initiateDownloadOrResume(downloadId, isResume = true)

    /**
     * Start the install flow: checks battery, scratch partition, and AB type, then shows the
     * install confirmation dialog if all checks pass.
     */
    fun initiateInstall(update: Update) {
        if (!batteryState.isLevelOk) { batteryLowPresenter.open(); return }
        if (InstallUtils.isScratchMounted()) { scratchPresenter.open(); return }
        val isAb = try {
            update.file?.let { Utils.isABUpdate(it) } ?: return
        } catch (_: IOException) {
            return
        }
        installTargetId.value = update.downloadId
        installIsAb.value = isAb
        installPresenter.open()
    }

    /** Show the "blocked update" info dialog. */
    fun showBlockedInfo() {
        infoTargetUrl.value = Utils.getUpgradeBlockedURL(context)
        infoPresenter.open()
    }

    // ── Private helpers ──────────────────────────────────────────────────────────────────────────

    private fun initiateDownloadOrResume(downloadId: String, isResume: Boolean) {
        when {
            updaterController?.hasActiveDownloads() == true -> {
                switchTargetId.value = downloadId
                switchIsResume.value = isResume
                switchPresenter.open()
            }
            networkState.isMetered && meteredWarning -> {
                meteredTargetId.value = downloadId
                meteredTargetIsResume.value = isResume
                meteredPresenter.open()
            }
            else -> {
                if (isResume) updaterController?.resumeDownload(downloadId)
                else updaterController?.startDownload(downloadId)
            }
        }
    }
}

/**
 * Creates and remembers all SPA [AlertDialogPresenter] instances for the update list and returns
 * an [UpdateListDialogs] that exposes named action methods.
 *
 * Call this at the top of the [UpdateList] composable. Each [AlertDialogPresenter] is long-lived
 * across recompositions; the [UpdateListDialogs] wrapper is recreated cheaply when its parameters
 * change so action methods always operate on the latest [updaterController] / [networkState].
 */
@Composable
fun rememberUpdateListDialogs(
    updaterController: UpdaterController?,
    networkState: NetworkState,
    meteredWarning: Boolean,
    batteryState: BatteryState,
): UpdateListDialogs {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    // ── Per-dialog target state ──────────────────────────────────────────────────────────────────

    val deleteTargetId = remember { mutableStateOf<String?>(null) }
    val cancelDownloadTargetId = remember { mutableStateOf<String?>(null) }
    val switchTargetId = remember { mutableStateOf<String?>(null) }
    val switchIsResume = remember { mutableStateOf(false) }
    val meteredTargetId = remember { mutableStateOf<String?>(null) }
    val meteredTargetIsResume = remember { mutableStateOf(false) }
    val installTargetId = remember { mutableStateOf<String?>(null) }
    val installIsAb = remember { mutableStateOf(false) }
    val infoTargetUrl = remember { mutableStateOf<String?>(null) }

    // ── SPA dialog presenters ────────────────────────────────────────────────────────────────────
    // meteredPresenter must be declared before switchPresenter so the switch confirm lambda
    // can call meteredPresenter.open().

    val deletePresenter = rememberAlertDialogPresenter(
        confirmButton = AlertDialogButton(stringResource(android.R.string.ok)) {
            deleteTargetId.value?.let { id ->
                updaterController?.pauseDownload(id)
                updaterController?.deleteUpdate(id)
            }
        },
        dismissButton = AlertDialogButton(stringResource(android.R.string.cancel)),
        title = stringResource(R.string.confirm_delete_dialog_title),
        text = { Text(stringResource(R.string.confirm_delete_dialog_message)) },
    )

    val cancelDownloadPresenter = rememberAlertDialogPresenter(
        confirmButton = AlertDialogButton(stringResource(android.R.string.ok)) {
            cancelDownloadTargetId.value?.let { updaterController?.cancelDownload(it) }
        },
        dismissButton = AlertDialogButton(stringResource(android.R.string.cancel)),
        title = stringResource(R.string.confirm_cancel_dialog_title),
        text = { Text(stringResource(R.string.confirm_cancel_dialog_message)) },
    )

    val cancelInstallPresenter = rememberAlertDialogPresenter(
        confirmButton = AlertDialogButton(stringResource(android.R.string.ok)) {
            context.startService(
                Intent(context, UpdaterService::class.java).apply {
                    action = UpdaterService.ACTION_INSTALL_STOP
                }
            )
        },
        dismissButton = AlertDialogButton(stringResource(android.R.string.cancel)),
        title = stringResource(R.string.cancel_installation_dialog_title),
        text = { Text(stringResource(R.string.cancel_installation_dialog_message)) },
    )

    val meteredPresenter = rememberAlertDialogPresenter(
        confirmButton = AlertDialogButton(
            text = if (meteredTargetIsResume.value) stringResource(R.string.action_resume)
                   else stringResource(R.string.action_download),
        ) {
            meteredTargetId.value?.let { id ->
                if (meteredTargetIsResume.value) updaterController?.resumeDownload(id)
                else updaterController?.startDownload(id)
            }
        },
        dismissButton = AlertDialogButton(stringResource(android.R.string.cancel)),
        title = stringResource(R.string.update_over_metered_network_title),
        text = { Text(stringResource(R.string.update_over_metered_network_message)) },
    )

    val switchPresenter = rememberAlertDialogPresenter(
        confirmButton = AlertDialogButton(stringResource(android.R.string.ok)) {
            switchTargetId.value?.let { id ->
                if (networkState.isMetered && meteredWarning) {
                    meteredTargetId.value = id
                    meteredTargetIsResume.value = switchIsResume.value
                    meteredPresenter.open()
                } else {
                    if (switchIsResume.value) updaterController?.resumeDownload(id)
                    else updaterController?.startDownload(id)
                }
            }
        },
        dismissButton = AlertDialogButton(stringResource(android.R.string.cancel)),
        title = stringResource(R.string.download_switch_confirm_title),
        text = { Text(stringResource(R.string.download_switch_confirm_message)) },
    )

    val batteryLowPresenter = rememberAlertDialogPresenter(
        confirmButton = AlertDialogButton(stringResource(android.R.string.ok)),
        title = stringResource(R.string.dialog_battery_low_title),
        text = {
            Text(
                stringResource(
                    R.string.dialog_battery_low_message_pct,
                    BatteryState.MIN_BATT_PCT_DISCHARGING,
                    BatteryState.MIN_BATT_PCT_CHARGING,
                )
            )
        },
    )

    val scratchPresenter = rememberAlertDialogPresenter(
        confirmButton = AlertDialogButton(stringResource(android.R.string.ok)),
        title = stringResource(R.string.dialog_scratch_mounted_title),
        text = { Text(stringResource(R.string.dialog_scratch_mounted_message)) },
    )

    val installPresenter = rememberAlertDialogPresenter(
        confirmButton = AlertDialogButton(stringResource(android.R.string.ok)) {
            installTargetId.value?.let { Utils.triggerUpdate(context, it) }
        },
        dismissButton = AlertDialogButton(stringResource(android.R.string.cancel)),
        title = stringResource(R.string.apply_update_dialog_title),
        text = {
            val id = installTargetId.value
            val update = if (id != null) updaterController?.getUpdate(id) else null
            if (update != null) {
                val buildDate = StringGenerator.getDateLocalizedUTC(
                    context, DateFormat.MEDIUM, update.timestamp
                )
                val buildInfo = stringResource(
                    R.string.list_build_version_date, update.version, buildDate
                )
                val msgRes = if (installIsAb.value) R.string.apply_update_dialog_message_ab
                             else R.string.apply_update_dialog_message
                Text(stringResource(msgRes, buildInfo, stringResource(android.R.string.ok)))
            }
        },
    )

    val infoPresenter = rememberAlertDialogPresenter(
        confirmButton = AlertDialogButton(stringResource(android.R.string.ok)) {
            infoTargetUrl.value?.let { uriHandler.openUri(it) }
        },
        dismissButton = AlertDialogButton(stringResource(android.R.string.cancel)),
        title = stringResource(R.string.blocked_update_dialog_title),
        text = {
            Text(stringResource(R.string.blocked_update_dialog_message, infoTargetUrl.value ?: ""))
        },
    )

    // Recreated each recomposition (cheap) so action methods always see the latest parameters.
    return UpdateListDialogs(
        updaterController = updaterController,
        networkState = networkState,
        meteredWarning = meteredWarning,
        batteryState = batteryState,
        context = context,
        deletePresenter = deletePresenter,
        cancelDownloadPresenter = cancelDownloadPresenter,
        cancelInstallPresenter = cancelInstallPresenter,
        meteredPresenter = meteredPresenter,
        switchPresenter = switchPresenter,
        batteryLowPresenter = batteryLowPresenter,
        scratchPresenter = scratchPresenter,
        installPresenter = installPresenter,
        infoPresenter = infoPresenter,
        deleteTargetId = deleteTargetId,
        cancelDownloadTargetId = cancelDownloadTargetId,
        meteredTargetId = meteredTargetId,
        meteredTargetIsResume = meteredTargetIsResume,
        switchTargetId = switchTargetId,
        switchIsResume = switchIsResume,
        installTargetId = installTargetId,
        installIsAb = installIsAb,
        infoTargetUrl = infoTargetUrl,
    )
}
