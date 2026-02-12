/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.text.SpannableString
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import org.lineageos.updater.controller.UpdaterController
import org.lineageos.updater.controller.UpdaterService
import org.lineageos.updater.misc.Constants
import org.lineageos.updater.misc.StringGenerator
import org.lineageos.updater.misc.Utils
import org.lineageos.updater.model.UpdateInfo
import org.lineageos.updater.model.UpdateStatus
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.text.DateFormat

class UpdateActionHandler(
    private val activity: UpdatesListActivity,
    private val controller: UpdaterController
) {

    enum class Action {
        DOWNLOAD,
        PAUSE,
        RESUME,
        INSTALL,
        INFO,
        DELETE,
        CANCEL_INSTALLATION,
        REBOOT,
        CANCEL,
        SUSPEND_INSTALLATION,
        RESUME_INSTALLATION,
        STREAM_INSTALL,
    }

    data class ActionState(val action: Action, val enabled: Boolean)

    private var activeDialog: AlertDialog? = null
    private var infoDialog: AlertDialog? = null

    fun dismissDialogs() {
        activeDialog?.dismiss()
        activeDialog = null
        infoDialog?.dismiss()
        infoDialog = null
    }

    fun isBusy(): Boolean =
        controller.hasActiveDownloads() || controller.isVerifyingUpdate
                || controller.isInstallingUpdate

    fun resolveIdleAction(downloadId: String): ActionState {
        val update = controller.getUpdate(downloadId)
            ?: return ActionState(Action.DOWNLOAD, false)
        return when {
            controller.isWaitingForReboot(downloadId) ||
                    update.status == UpdateStatus.INSTALLED ->
                ActionState(Action.REBOOT, true)

            update.persistentStatus == UpdateStatus.Persistent.VERIFIED ->
                ActionState(
                    if (Utils.canInstall(update)) Action.INSTALL else Action.DELETE,
                    !isBusy()
                )

            !Utils.canInstall(update) ->
                ActionState(Action.INFO, !isBusy())

            PreferenceManager.getDefaultSharedPreferences(activity)
                    .getBoolean(Constants.PREF_AB_STREAMING_MODE, false) -> {
                val canStream = !controller.isVerifyingUpdate(downloadId) &&
                        !controller.isInstallingUpdate(downloadId) &&
                        Utils.isNetworkAvailable(activity) && update.availableOnline
                ActionState(Action.STREAM_INSTALL, canStream)
            }

            else -> {
                val canDownload = !controller.isVerifyingUpdate(downloadId) &&
                        !controller.isInstallingUpdate(downloadId) &&
                        Utils.isNetworkAvailable(activity)
                ActionState(Action.DOWNLOAD, canDownload)
            }
        }
    }

    fun canDeleteUpdate(downloadId: String): Boolean {
        val update = controller.getUpdate(downloadId) ?: return false
        if (update.persistentStatus != UpdateStatus.Persistent.VERIFIED) return false
        if (controller.isWaitingForReboot(downloadId) ||
            update.status == UpdateStatus.INSTALLED) return false
        // Don't offer delete for verified updates that can't be installed
        // and aren't available online (may be user's only copy)
        if (!Utils.canInstall(update) && !update.availableOnline) return false
        return true
    }

    fun exportUpdate(update: UpdateInfo) {
        activity.exportUpdate(update)
    }

    fun performAction(action: Action, downloadId: String) {
        activeDialog?.dismiss()
        activeDialog = null

        when (action) {
            Action.DOWNLOAD -> downloadWithConfirmation(downloadId, false)
            Action.PAUSE -> controller.pauseDownload(downloadId)
            Action.RESUME -> {
                val update = controller.getUpdate(downloadId)
                if (update != null && (Utils.canInstall(update) ||
                            update.file.length() == update.fileSize)) {
                    downloadWithConfirmation(downloadId, true)
                } else {
                    activity.showToast(R.string.snack_update_not_installable, Toast.LENGTH_LONG)
                }
            }
            Action.INSTALL -> {
                val update = controller.getUpdate(downloadId)
                if (update != null && Utils.canInstall(update)) {
                    activeDialog = getInstallDialog(downloadId)?.show()
                } else {
                    activity.showToast(R.string.snack_update_not_installable, Toast.LENGTH_LONG)
                }
            }
            Action.INFO -> showInfoDialog()
            Action.DELETE -> activeDialog = getDeleteDialog(downloadId).show()
            Action.CANCEL_INSTALLATION ->
                activeDialog = getCancelInstallationDialog().show()
            Action.REBOOT -> {
                val pm = activity.getSystemService(PowerManager::class.java)
                pm.reboot(null)
            }
            Action.CANCEL -> activeDialog = getCancelDownloadDialog(downloadId).show()
            Action.SUSPEND_INSTALLATION -> {
                val intent = Intent(activity, UpdaterService::class.java)
                intent.action = UpdaterService.ACTION_INSTALL_SUSPEND
                activity.startService(intent)
            }
            Action.RESUME_INSTALLATION -> {
                val intent = Intent(activity, UpdaterService::class.java)
                intent.action = UpdaterService.ACTION_INSTALL_RESUME
                activity.startService(intent)
            }
            Action.STREAM_INSTALL -> {
                val update = controller.getUpdate(downloadId)
                if (update != null && Utils.canInstall(update)) {
                    activeDialog = getStreamInstallDialog(downloadId)?.show()
                } else {
                    activity.showToast(R.string.snack_update_not_installable, Toast.LENGTH_LONG)
                }
            }
        }
    }

    fun downloadWithConfirmation(downloadId: String, isResume: Boolean) {
        if (controller.hasActiveDownloads()) {
            activeDialog = AlertDialog.Builder(activity)
                .setTitle(R.string.download_switch_confirm_title)
                .setMessage(R.string.download_switch_confirm_message)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    downloadWithWarning(downloadId, isResume)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            downloadWithWarning(downloadId, isResume)
        }
    }

    private fun downloadWithWarning(downloadId: String, isResume: Boolean) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(activity)
        val warn = preferences.getBoolean(Constants.PREF_METERED_NETWORK_WARNING, true)
        if (!(Utils.isNetworkMetered(activity) && warn)) {
            if (isResume) controller.resumeDownload(downloadId)
            else controller.startDownload(downloadId)
            return
        }

        activeDialog = AlertDialog.Builder(activity)
            .setTitle(R.string.update_over_metered_network_title)
            .setMessage(R.string.update_over_metered_network_message)
            .setPositiveButton(
                if (isResume) R.string.action_resume else R.string.action_download
            ) { _, _ ->
                if (isResume) controller.resumeDownload(downloadId)
                else controller.startDownload(downloadId)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun getInstallDialog(downloadId: String): AlertDialog.Builder? {
        getPreInstallBlockingDialog()?.let { return it }

        val update = controller.getUpdate(downloadId) ?: return null
        val resId = try {
            if (Utils.isABUpdate(update.file)) {
                R.string.apply_update_dialog_message_ab
            } else {
                R.string.apply_update_dialog_message
            }
        } catch (e: IOException) {
            return null
        }

        val buildDate = StringGenerator.getDateLocalizedUTC(
            activity, DateFormat.MEDIUM, update.timestamp
        )
        val buildInfoText = activity.getString(
            R.string.list_build_version_date, update.version, buildDate
        )
        return AlertDialog.Builder(activity)
            .setTitle(R.string.apply_update_dialog_title)
            .setMessage(
                activity.getString(
                    resId, buildInfoText, activity.getString(android.R.string.ok)
                )
            )
            .setPositiveButton(android.R.string.ok) { _, _ ->
                Utils.triggerUpdate(activity, downloadId)
                maybeShowInfoDialog()
            }
            .setNegativeButton(android.R.string.cancel, null)
    }

    fun getStreamInstallDialog(downloadId: String): AlertDialog.Builder? {
        getPreInstallBlockingDialog()?.let { return it }

        val update = controller.getUpdate(downloadId) ?: return null
        val buildDate = StringGenerator.getDateLocalizedUTC(
            activity, DateFormat.MEDIUM, update.timestamp
        )
        val buildInfoText = activity.getString(
            R.string.list_build_version_date, update.version, buildDate
        )
        return AlertDialog.Builder(activity)
            .setTitle(R.string.apply_update_dialog_title)
            .setMessage(
                activity.getString(
                    R.string.apply_update_dialog_message_streaming,
                    buildInfoText,
                    activity.getString(android.R.string.ok)
                )
            )
            .setPositiveButton(android.R.string.ok) { _, _ ->
                Utils.triggerStreamingUpdate(activity, downloadId)
                maybeShowInfoDialog()
            }
            .setNegativeButton(android.R.string.cancel, null)
    }

    fun getDeleteDialog(downloadId: String): AlertDialog.Builder =
        AlertDialog.Builder(activity)
            .setTitle(R.string.confirm_delete_dialog_title)
            .setMessage(R.string.confirm_delete_dialog_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                controller.pauseDownload(downloadId)
                controller.deleteUpdate(downloadId)
            }
            .setNegativeButton(android.R.string.cancel, null)

    fun getCancelDownloadDialog(downloadId: String): AlertDialog.Builder =
        AlertDialog.Builder(activity)
            .setTitle(R.string.confirm_cancel_dialog_title)
            .setMessage(R.string.confirm_cancel_dialog_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                controller.cancelDownload(downloadId)
            }
            .setNegativeButton(android.R.string.cancel, null)

    fun getCancelInstallationDialog(): AlertDialog.Builder =
        AlertDialog.Builder(activity)
            .setTitle(R.string.cancel_installation_dialog_title)
            .setMessage(R.string.cancel_installation_dialog_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val intent = Intent(activity, UpdaterService::class.java)
                intent.action = UpdaterService.ACTION_INSTALL_STOP
                activity.startService(intent)
            }
            .setNegativeButton(android.R.string.cancel, null)

    fun showInfoDialog() {
        val messageString = String.format(
            StringGenerator.getCurrentLocale(activity),
            activity.getString(R.string.blocked_update_dialog_message),
            Utils.getUpgradeBlockedURL(activity)
        )
        val message = SpannableString(messageString)
        Linkify.addLinks(message, Linkify.WEB_URLS)
        activeDialog?.dismiss()
        infoDialog?.dismiss()
        infoDialog = AlertDialog.Builder(activity)
            .setTitle(R.string.blocked_update_dialog_title)
            .setPositiveButton(android.R.string.ok, null)
            .setMessage(message)
            .show()
        infoDialog?.findViewById<TextView>(android.R.id.message)
            ?.movementMethod = LinkMovementMethod.getInstance()
    }

    fun maybeShowInfoDialog() {
        val preferences = PreferenceManager.getDefaultSharedPreferences(activity)
        val alreadySeen = preferences.getBoolean(Constants.PREF_HAS_SEEN_INFO_DIALOG, false)
        if (alreadySeen) return

        activeDialog = AlertDialog.Builder(activity)
            .setTitle(R.string.info_dialog_title)
            .setMessage(R.string.info_dialog_message)
            .setPositiveButton(R.string.info_dialog_ok) { _, _ ->
                preferences.edit()
                    .putBoolean(Constants.PREF_HAS_SEEN_INFO_DIALOG, true)
                    .apply()
            }
            .show()
    }

    fun isBatteryLevelOk(): Boolean {
        val intent = activity.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: return true
        if (!intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)) return true

        val percent = Math.round(
            100f * intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) /
                    intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        )
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val required = if (plugged and BATTERY_PLUGGED_ANY != 0) {
            activity.resources.getInteger(R.integer.battery_ok_percentage_charging)
        } else {
            activity.resources.getInteger(R.integer.battery_ok_percentage_discharging)
        }
        return percent >= required
    }

    private fun getPreInstallBlockingDialog(): AlertDialog.Builder? {
        if (!isBatteryLevelOk()) {
            val resources = activity.resources
            val message = resources.getString(
                R.string.dialog_battery_low_message_pct,
                resources.getInteger(R.integer.battery_ok_percentage_discharging),
                resources.getInteger(R.integer.battery_ok_percentage_charging)
            )
            return AlertDialog.Builder(activity)
                .setTitle(R.string.dialog_battery_low_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
        }
        if (isScratchMounted()) {
            return AlertDialog.Builder(activity)
                .setTitle(R.string.dialog_scratch_mounted_title)
                .setMessage(R.string.dialog_scratch_mounted_message)
                .setPositiveButton(android.R.string.ok, null)
        }
        return null
    }

    companion object {
        private const val BATTERY_PLUGGED_ANY = (BatteryManager.BATTERY_PLUGGED_AC
                or BatteryManager.BATTERY_PLUGGED_USB
                or BatteryManager.BATTERY_PLUGGED_WIRELESS)

        @JvmStatic
        fun isScratchMounted(): Boolean {
            return try {
                Files.lines(Path.of("/proc/mounts")).use { lines ->
                    lines.anyMatch { it.split(" ")[1] == "/mnt/scratch" }
                }
            } catch (e: IOException) {
                false
            }
        }
    }
}
