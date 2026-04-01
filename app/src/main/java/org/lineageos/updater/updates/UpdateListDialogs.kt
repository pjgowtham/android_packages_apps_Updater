/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.updates

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
import org.lineageos.updater.R
import org.lineageos.updater.ui.dialog.rememberAlertDialogWithIconPresenter
import org.lineageos.updater.controller.UpdaterController
import org.lineageos.updater.controller.UpdaterService
import org.lineageos.updater.data.Update
import org.lineageos.updater.deviceinfo.DeviceInfoUtils
import org.lineageos.updater.misc.Utils
import org.lineageos.updater.util.StringUtil
import org.lineageos.updater.util.BatteryState
import org.lineageos.updater.util.InstallUtils
import org.lineageos.updater.util.NetworkState
import java.io.IOException
import java.time.format.FormatStyle

/**
 * Owns update-list dialog actions and decision gates.
 * Create with [rememberUpdateListDialogs].
 */
class UpdateListDialogs internal constructor(
    private val updaterController: UpdaterController?,
    private val networkState: NetworkState,
    private val meteredWarning: Boolean,
    private val batteryState: BatteryState,
    private val context: Context,
    private val deletePresenter: AlertDialogPresenter,
    private val cancelDownloadPresenter: AlertDialogPresenter,
    private val cancelInstallPresenter: AlertDialogPresenter,
    private val meteredPresenter: AlertDialogPresenter,
    private val switchPresenter: AlertDialogPresenter,
    private val batteryLowPresenter: AlertDialogPresenter,
    private val scratchPresenter: AlertDialogPresenter,
    private val installPresenter: AlertDialogPresenter,
    private val infoPresenter: AlertDialogPresenter,
    private val deleteTargetId: MutableState<String?>,
    private val cancelDownloadTargetId: MutableState<String?>,
    private val meteredTarget: MutableState<PendingDownloadAction?>,
    private val switchTarget: MutableState<PendingDownloadAction?>,
    private val installTarget: MutableState<PendingInstallAction?>,
    private val infoTargetUrl: MutableState<String?>,
) {
    fun confirmDelete(downloadId: String) {
        openDialogForId(deleteTargetId, downloadId, deletePresenter)
    }

    fun confirmCancelDownload(downloadId: String) {
        openDialogForId(cancelDownloadTargetId, downloadId, cancelDownloadPresenter)
    }

    fun confirmCancelInstall() = cancelInstallPresenter.open()

    fun initiateDownload(downloadId: String) = initiateDownloadOrResume(downloadId, isResume = false)

    fun initiateResume(downloadId: String) = initiateDownloadOrResume(downloadId, isResume = true)

    fun initiateInstall(update: Update) {
        if (!batteryState.isLevelOk) { batteryLowPresenter.open(); return }
        if (InstallUtils.isScratchMounted()) { scratchPresenter.open(); return }
        val isAb = try {
            update.file?.let { Utils.isABUpdate(it) } ?: return
        } catch (_: IOException) {
            return
        }
        installTarget.value = PendingInstallAction(update.downloadId, isAb)
        installPresenter.open()
    }

    fun showBlockedInfo() {
        infoTargetUrl.value = context.getString(
            R.string.blocked_update_info_url, DeviceInfoUtils.device
        )
        infoPresenter.open()
    }

    private fun initiateDownloadOrResume(downloadId: String, isResume: Boolean) {
        when {
            updaterController?.hasActiveDownloads() == true -> {
                switchTarget.value = PendingDownloadAction(downloadId, isResume)
                switchPresenter.open()
            }
            networkState.isMetered && meteredWarning -> {
                meteredTarget.value = PendingDownloadAction(downloadId, isResume)
                meteredPresenter.open()
            }
            else -> startOrResumeDownload(updaterController, downloadId, isResume)
        }
    }
}

/**
 * Creates and remembers presenters for [UpdateListDialogs].
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
    val cancelButton = AlertDialogButton(stringResource(android.R.string.cancel))

    val deleteTargetId = remember { mutableStateOf<String?>(null) }
    val cancelDownloadTargetId = remember { mutableStateOf<String?>(null) }
    val switchTarget = remember { mutableStateOf<PendingDownloadAction?>(null) }
    val meteredTarget = remember { mutableStateOf<PendingDownloadAction?>(null) }
    val installTarget = remember { mutableStateOf<PendingInstallAction?>(null) }
    val infoTargetUrl = remember { mutableStateOf<String?>(null) }

    val deletePresenter = rememberAlertDialogWithIconPresenter(
        confirmButton = AlertDialogButton(stringResource(android.R.string.ok)) {
            deleteTargetId.value?.let { id ->
                updaterController?.pauseDownload(id)
                updaterController?.deleteUpdate(id)
            }
        },
        dismissButton = cancelButton,
        title = stringResource(R.string.confirm_delete_dialog_title),
        text = { Text(stringResource(R.string.confirm_delete_dialog_message)) },
    )

    val cancelDownloadPresenter = rememberAlertDialogWithIconPresenter(
        confirmButton = AlertDialogButton(stringResource(android.R.string.ok)) {
            cancelDownloadTargetId.value?.let { updaterController?.cancelDownload(it) }
        },
        dismissButton = cancelButton,
        title = stringResource(R.string.confirm_cancel_dialog_title),
        text = { Text(stringResource(R.string.confirm_cancel_dialog_message)) },
    )

    val cancelInstallPresenter = rememberAlertDialogWithIconPresenter(
        confirmButton = AlertDialogButton(stringResource(android.R.string.ok)) {
            context.startService(
                Intent(context, UpdaterService::class.java).apply {
                    action = UpdaterService.ACTION_INSTALL_STOP
                }
            )
        },
        dismissButton = cancelButton,
        title = stringResource(R.string.cancel_installation_dialog_title),
        text = { Text(stringResource(R.string.cancel_installation_dialog_message)) },
    )

    val meteredPresenter = rememberAlertDialogWithIconPresenter(
        confirmButton = AlertDialogButton(
            text = if (meteredTarget.value?.isResume == true) stringResource(R.string.action_resume)
                   else stringResource(R.string.action_download),
        ) {
            meteredTarget.value?.let { target ->
                startOrResumeDownload(updaterController, target.downloadId, target.isResume)
            }
        },
        dismissButton = cancelButton,
        title = stringResource(R.string.update_over_metered_network_title),
        text = { Text(stringResource(R.string.update_over_metered_network_message)) },
    )

    val switchPresenter = rememberAlertDialogWithIconPresenter(
        confirmButton = AlertDialogButton(stringResource(android.R.string.ok)) {
            switchTarget.value?.let { target ->
                if (networkState.isMetered && meteredWarning) {
                    meteredTarget.value = target
                    meteredPresenter.open()
                } else {
                    startOrResumeDownload(
                        updaterController,
                        target.downloadId,
                        target.isResume,
                    )
                }
            }
        },
        dismissButton = cancelButton,
        title = stringResource(R.string.download_switch_confirm_title),
        text = { Text(stringResource(R.string.download_switch_confirm_message)) },
    )

    val batteryLowPresenter = rememberAlertDialogWithIconPresenter(
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

    val scratchPresenter = rememberAlertDialogWithIconPresenter(
        confirmButton = AlertDialogButton(stringResource(android.R.string.ok)),
        title = stringResource(R.string.dialog_scratch_mounted_title),
        text = { Text(stringResource(R.string.dialog_scratch_mounted_message)) },
    )

    val installPresenter = rememberAlertDialogWithIconPresenter(
        confirmButton = AlertDialogButton(stringResource(android.R.string.ok)) {
            installTarget.value?.let { Utils.triggerUpdate(context, it.downloadId) }
        },
        dismissButton = cancelButton,
        title = stringResource(R.string.apply_update_dialog_title),
        text = {
            val target = installTarget.value
            val update = target?.downloadId?.let { updaterController?.getUpdate(it) }
            if (update != null) {
                val buildDate = StringUtil.getDateLocalizedUTC(
                    context, FormatStyle.MEDIUM, update.timestamp
                )
                val buildInfo = stringResource(
                    R.string.list_build_version_date, update.version, buildDate
                )
                val msgRes = if (target?.isAb == true) R.string.apply_update_dialog_message_ab
                             else R.string.apply_update_dialog_message
                Text(stringResource(msgRes, buildInfo, stringResource(android.R.string.ok)))
            }
        },
    )

    val infoPresenter = rememberAlertDialogWithIconPresenter(
        confirmButton = AlertDialogButton(stringResource(android.R.string.ok)) {
            infoTargetUrl.value?.let { uriHandler.openUri(it) }
        },
        dismissButton = cancelButton,
        title = stringResource(R.string.blocked_update_dialog_title),
        text = {
            Text(stringResource(R.string.blocked_update_dialog_message, infoTargetUrl.value ?: ""))
        },
    )

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
        meteredTarget = meteredTarget,
        switchTarget = switchTarget,
        installTarget = installTarget,
        infoTargetUrl = infoTargetUrl,
    )
}

private fun startOrResumeDownload(
    updaterController: UpdaterController?,
    downloadId: String,
    isResume: Boolean,
) {
    if (isResume) updaterController?.resumeDownload(downloadId)
    else updaterController?.startDownload(downloadId)
}

private fun openDialogForId(
    targetId: MutableState<String?>,
    downloadId: String,
    presenter: AlertDialogPresenter,
) {
    targetId.value = downloadId
    presenter.open()
}

internal data class PendingDownloadAction(
    val downloadId: String,
    val isResume: Boolean,
)

internal data class PendingInstallAction(
    val downloadId: String,
    val isAb: Boolean,
)
