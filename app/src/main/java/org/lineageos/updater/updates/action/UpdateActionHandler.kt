/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.updates.action

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.text.SpannableString
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.widget.TextView
import java.time.format.FormatStyle
import org.lineageos.updater.R
import org.lineageos.updater.controller.UpdaterController
import org.lineageos.updater.controller.UpdaterService
import org.lineageos.updater.data.Update
import org.lineageos.updater.data.UserPreferencesRepository
import org.lineageos.updater.deviceinfo.DeviceInfoUtils
import org.lineageos.updater.misc.Utils
import org.lineageos.updater.util.BatteryMonitor
import org.lineageos.updater.util.BatteryState
import org.lineageos.updater.util.InstallUtils
import org.lineageos.updater.util.NetworkMonitor
import org.lineageos.updater.util.StringUtil
import org.lineageos.updater.util.currentLocale

class UpdateActionHandler(
    private val activity: Activity,
    private val updaterController: UpdaterController,
    private val exportUpdate: (Update) -> Unit,
) {

    private val batteryMonitor = BatteryMonitor.getInstance(activity)
    private val networkMonitor = NetworkMonitor.getInstance(activity)
    private var infoDialog: AlertDialog? = null

    fun perform(action: UpdateAction, update: Update) {
        val downloadId = update.downloadId
        when (action.type) {
            UpdateActionType.START_DOWNLOAD -> startDownload(downloadId)
            UpdateActionType.PAUSE_DOWNLOAD -> updaterController.pauseDownload(downloadId)
            UpdateActionType.RESUME_DOWNLOAD -> resumeDownload(update)
            UpdateActionType.CANCEL_DOWNLOAD -> updaterController.cancelDownload(downloadId)
            UpdateActionType.START_INSTALL -> install(update)
            UpdateActionType.PAUSE_INSTALL -> startInstallService(
                UpdaterService.ACTION_INSTALL_SUSPEND
            )
            UpdateActionType.RESUME_INSTALL -> startInstallService(
                UpdaterService.ACTION_INSTALL_RESUME
            )
            UpdateActionType.CANCEL_INSTALL -> startInstallService(
                UpdaterService.ACTION_INSTALL_STOP
            )
            UpdateActionType.SHOW_INFO -> showInfoDialog()
            UpdateActionType.DELETE -> updaterController.deleteUpdate(downloadId)
            UpdateActionType.EXPORT -> exportUpdate(update)
            UpdateActionType.VIEW_DOWNLOADS -> viewDownloads()
            UpdateActionType.REBOOT ->
                activity.getSystemService(PowerManager::class.java).reboot(null)
        }
    }

    private fun startDownload(downloadId: String) {
        runDownloadWithMeteredWarning {
            updaterController.startDownload(downloadId)
        }
    }

    private fun resumeDownload(update: Update) {
        val file = update.file
        if (file != null && update.fileSize > 0 && file.length() >= update.fileSize) {
            updaterController.resumeDownload(update.downloadId)
            return
        }

        runDownloadWithMeteredWarning {
            updaterController.resumeDownload(update.downloadId)
        }
    }

    private fun runDownloadWithMeteredWarning(downloadAction: () -> Unit) {
        val warn = UserPreferencesRepository.getMeteredNetworkWarningBlocking(activity)
        if (!(networkMonitor.networkState.value.isMetered && warn)) {
            downloadAction()
            return
        }

        AlertDialog.Builder(activity)
            .setTitle(R.string.update_over_metered_network_title)
            .setMessage(R.string.update_over_metered_network_message)
            .setPositiveButton(android.R.string.ok) { _, _ -> downloadAction() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun install(update: Update) {
        if (canInstall(update)) {
            getInstallDialog(update)?.show()
        }
    }

    private fun canInstall(update: Update) =
        update.downloadId == Update.LOCAL_ID || Utils.canInstall(update)

    private fun getInstallDialog(update: Update): AlertDialog.Builder? {
        if (!batteryMonitor.batteryState.value.isLevelOk) {
            val message = activity.getString(
                R.string.dialog_battery_low_message_pct,
                BatteryState.MIN_BATT_PCT_DISCHARGING,
                BatteryState.MIN_BATT_PCT_CHARGING,
            )
            return AlertDialog.Builder(activity)
                .setTitle(R.string.dialog_battery_low_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
        }

        if (InstallUtils.isScratchMounted()) {
            return AlertDialog.Builder(activity)
                .setTitle(R.string.dialog_scratch_mounted_title)
                .setMessage(R.string.dialog_scratch_mounted_message)
                .setPositiveButton(android.R.string.ok, null)
        }

        val messageRes = if (DeviceInfoUtils.isABDevice) {
            R.string.apply_update_dialog_message_ab
        } else {
            R.string.apply_update_dialog_message
        }

        val buildDate = StringUtil.getDateLocalizedUTC(
            activity,
            FormatStyle.MEDIUM,
            update.timestamp,
        )
        val buildInfoText = activity.getString(
            R.string.list_build_version_date,
            update.version,
            buildDate,
        )
        return AlertDialog.Builder(activity)
            .setTitle(R.string.apply_update_dialog_title)
            .setMessage(
                activity.getString(
                    messageRes,
                    buildInfoText,
                    activity.getString(android.R.string.ok),
                )
            )
            .setPositiveButton(android.R.string.ok) { _, _ ->
                Utils.triggerUpdate(activity, update.downloadId)
            }
            .setNegativeButton(android.R.string.cancel, null)
    }

    private fun viewDownloads() {
        activity.startActivity(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse(
                    activity.getString(
                        R.string.menu_downloads_url,
                        DeviceInfoUtils.device,
                    )
                ),
            )
        )
    }

    private fun showInfoDialog() {
        val messageString = String.format(
            activity.currentLocale,
            activity.getString(R.string.blocked_update_dialog_message),
            activity.getString(
                R.string.blocked_update_info_url,
                DeviceInfoUtils.device,
            ),
        )
        val message = SpannableString(messageString)
        Linkify.addLinks(message, Linkify.WEB_URLS)

        infoDialog?.dismiss()
        infoDialog = AlertDialog.Builder(activity)
            .setTitle(R.string.blocked_update_dialog_title)
            .setPositiveButton(android.R.string.ok, null)
            .setMessage(message)
            .show()

        infoDialog?.findViewById<TextView>(android.R.id.message)?.movementMethod =
            LinkMovementMethod.getInstance()
    }

    private fun startInstallService(action: String) {
        activity.startService(
            Intent(activity, UpdaterService::class.java).setAction(action)
        )
    }
}
