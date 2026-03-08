/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.PowerManager
import android.text.format.Formatter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.lineageos.updater.R
import org.lineageos.updater.controller.UpdaterController
import org.lineageos.updater.controller.UpdaterService
import org.lineageos.updater.misc.Constants
import org.lineageos.updater.misc.StringGenerator
import org.lineageos.updater.misc.Utils
import org.lineageos.updater.misc.UtilsKt
import org.lineageos.updater.model.UpdateInfo
import org.lineageos.updater.model.UpdateStatus
import org.lineageos.updater.ui.StringWrapper
import org.lineageos.updater.ui.UpdateListItemUiEvent
import org.lineageos.updater.ui.UpdateListItemUiEvent.ShowBatteryLowDialog
import org.lineageos.updater.ui.UpdateListItemUiEvent.ShowCancelDownloadDialog
import org.lineageos.updater.ui.UpdateListItemUiEvent.ShowCancelInstallationDialog
import org.lineageos.updater.ui.UpdateListItemUiEvent.ShowDeleteDialog
import org.lineageos.updater.ui.UpdateListItemUiEvent.ShowInfoDialog
import org.lineageos.updater.ui.UpdateListItemUiEvent.ShowInstallConfirmDialog
import org.lineageos.updater.ui.UpdateListItemUiEvent.ShowMeteredNetworkWarning
import org.lineageos.updater.ui.UpdateListItemUiEvent.ShowNotInstallableToast
import org.lineageos.updater.ui.UpdateListItemUiEvent.ShowScratchMountedDialog
import org.lineageos.updater.ui.UpdateListItemUiEvent.ShowSwitchDownloadDialog
import org.lineageos.updater.ui.UpdateListItemUiState
import org.lineageos.updater.ui.UpdateListItemUiState.Active
import org.lineageos.updater.ui.UpdateListItemUiState.CancelAction
import org.lineageos.updater.ui.UpdateListItemUiState.Inactive
import org.lineageos.updater.ui.UpdateListItemUiState.MenuState
import org.lineageos.updater.ui.UpdateListItemUiState.PrimaryAction
import java.io.IOException
import java.text.DateFormat
import java.text.NumberFormat

class UpdateListItemViewModel(
    application: Application,
    private val preferences: SharedPreferences,
) : AndroidViewModel(application) {

    private val context: Context get() = getApplication()

    private val percentFormat = NumberFormat.getPercentInstance()

    private val _controller = MutableStateFlow<UpdaterController?>(null)

    fun setController(controller: UpdaterController?) {
        _controller.value = controller
    }

    private val controller: UpdaterController? get() = _controller.value

    private val _selectedDownloadId = MutableStateFlow<String?>(null)
    val selectedDownloadId: StateFlow<String?> = _selectedDownloadId.asStateFlow()

    fun selectDownload(downloadId: String) { _selectedDownloadId.value = downloadId }
    fun clearSelection() { _selectedDownloadId.value = null }

    private val _uiEvents = MutableSharedFlow<UpdateListItemUiEvent>(replay = 1)
    val uiEvents: SharedFlow<UpdateListItemUiEvent> = _uiEvents.asSharedFlow()

    private var eventsJob: Job? = null

    @Deprecated("Collect uiEvents directly from Compose once the adapter is removed.")
    fun observeEvents(owner: LifecycleOwner, observer: Observer<UpdateListItemUiEvent>) {
        eventsJob?.cancel()
        eventsJob = owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                uiEvents.collect { observer.onChanged(it) }
            }
        }
    }

    @Deprecated("Collect uiEvents directly from Compose once the adapter is removed.")
    fun removeEventObserver() {
        eventsJob?.cancel()
        eventsJob = null
    }

    override fun onCleared() {
        super.onCleared()
        eventsJob?.cancel()
    }

    fun buildUiState(downloadId: String): UpdateListItemUiState {
        val c = controller ?: return UpdateListItemUiState.LOADING
        val update = c.getUpdate(downloadId) ?: return UpdateListItemUiState.LOADING

        val buildDate    = StringGenerator.getDateLocalizedUTC(context, DateFormat.LONG, update.timestamp)
        val buildVersion = context.getString(R.string.list_build_version, update.version)
        val isSelected   = downloadId == _selectedDownloadId.value
        val isVerifying  = c.isVerifyingUpdate(downloadId)
        val isABUpdate   = c.isInstallingABUpdate()
        val isInstalling = c.isInstallingUpdate(downloadId)
                || update.status == UpdateStatus.INSTALLATION_SUSPENDED

        return if (update.status.isActiveLayout) {
            buildActiveState(
                update       = update,
                downloadId   = downloadId,
                controller   = c,
                buildDate    = buildDate,
                buildVersion = buildVersion,
                isSelected   = isSelected,
                isVerifying  = isVerifying,
                isABUpdate   = isABUpdate,
                isInstalling = isInstalling,
            )
        } else {
            buildInactiveState(
                update       = update,
                downloadId   = downloadId,
                controller   = c,
                buildDate    = buildDate,
                buildVersion = buildVersion,
                isSelected   = isSelected,
            )
        }
    }

    private fun buildActiveState(
        update: UpdateInfo,
        downloadId: String,
        controller: UpdaterController,
        buildDate: String,
        buildVersion: String,
        isSelected: Boolean,
        isVerifying: Boolean,
        isABUpdate: Boolean,
        isInstalling: Boolean,
    ): Active {
        val downloadedBytes = (update.file?.length() ?: 0L).toFileSize()
        val totalBytes      = update.fileSize.toFileSize()

        val (action, enabled, indeterminate, value, text, pct) = when {
            controller.isDownloading(downloadId) -> ActiveFields(
                action        = PrimaryAction.PAUSE,
                enabled       = true,
                indeterminate = update.status == UpdateStatus.STARTING,
                value         = update.progress,
                text          = if (update.eta > 0)
                    StringWrapper.Resource(
                        R.string.list_download_progress_eta_newer,
                        downloadedBytes,
                        totalBytes,
                        StringGenerator.formatETA(context, update.eta * 1000),
                    )
                else
                    StringWrapper.Resource(
                        R.string.list_download_progress_newer,
                        downloadedBytes,
                        totalBytes,
                    ),
                pct           = update.progress.toPct(),
            )
            isInstalling -> ActiveFields(
                action        = when {
                    !isABUpdate -> PrimaryAction.CANCEL_INSTALLATION
                    update.status == UpdateStatus.INSTALLATION_SUSPENDED -> PrimaryAction.RESUME_INSTALLATION
                    else -> PrimaryAction.SUSPEND_INSTALLATION
                },
                enabled       = true,
                indeterminate = false,
                value         = update.installProgress,
                text          = StringWrapper.Resource(when {
                    !isABUpdate       -> R.string.dialog_prepare_zip_message
                    update.finalizing -> R.string.finalizing_package
                    else              -> R.string.preparing_ota_first_boot
                }),
                pct           = update.installProgress.toPct(),
            )
            isVerifying -> ActiveFields(
                action        = PrimaryAction.INSTALL,
                enabled       = false,
                indeterminate = true,
                value         = 0,
                text          = StringWrapper.Resource(R.string.list_verifying_update),
                pct           = "",
            )
            else -> ActiveFields(
                action        = PrimaryAction.RESUME,
                enabled       = Utils.isNetworkAvailable(context),
                indeterminate = false,
                value         = update.progress,
                text          = StringWrapper.Resource(
                    R.string.list_download_progress_newer,
                    downloadedBytes,
                    totalBytes,
                ),
                pct           = update.progress.toPct(),
            )
        }

        return Active(
            buildDate               = buildDate,
            buildVersion            = buildVersion,
            isSelected              = isSelected,
            menuState               = buildMenuState(update),
            primaryAction           = action,
            isPrimaryActionEnabled  = enabled,
            isCancelVisible         = !isVerifying && (!isInstalling || isABUpdate),
            cancelAction            = if (isInstalling) CancelAction.CANCEL_INSTALLATION
                                      else CancelAction.CANCEL_DOWNLOAD,
            isProgressIndeterminate = indeterminate,
            progressValue           = value,
            progressText            = text,
            percentageText          = pct,
        )
    }

    private fun buildInactiveState(
        update: UpdateInfo,
        downloadId: String,
        controller: UpdaterController,
        buildDate: String,
        buildVersion: String,
        isSelected: Boolean,
    ): Inactive {
        val isStreamingMode = preferences.getBoolean(Constants.PREF_AB_STREAMING_MODE, false)

        val (action, enabled) = when {
            controller.isWaitingForReboot(downloadId) ->
                PrimaryAction.REBOOT to true
            update.status == UpdateStatus.VERIFIED ->
                (if (Utils.canInstall(update)) PrimaryAction.INSTALL else PrimaryAction.DELETE) to true
            !Utils.canInstall(update) ->
                PrimaryAction.INFO to true
            isStreamingMode ->
                PrimaryAction.INSTALL to (Utils.isNetworkAvailable(context) && update.availableOnline)
            else ->
                PrimaryAction.DOWNLOAD to Utils.isNetworkAvailable(context)
        }

        return Inactive(
            buildDate              = buildDate,
            buildVersion           = buildVersion,
            isSelected             = isSelected,
            menuState              = buildMenuState(update),
            primaryAction          = action,
            isPrimaryActionEnabled = enabled,
            buildSize              = update.fileSize.toFileSize(),
        )
    }

    fun onDownloadClicked(downloadId: String) {
        val c = controller ?: return
        val proceed = { downloadWithMeteredWarning(downloadId, isResume = false) }
        if (c.hasActiveDownloads()) {
            dispatch(ShowSwitchDownloadDialog(onConfirm = proceed))
        } else {
            proceed()
        }
    }

    fun onPause(downloadId: String) {
        controller?.pauseDownload(downloadId)
    }

    fun onResumeClicked(downloadId: String) {
        val c = controller ?: return
        if (!canResume(downloadId, c)) {
            dispatch(ShowNotInstallableToast)
            return
        }
        val proceed = { downloadWithMeteredWarning(downloadId, isResume = true) }
        if (c.hasActiveDownloads()) {
            dispatch(ShowSwitchDownloadDialog(onConfirm = proceed))
        } else {
            proceed()
        }
    }

    fun downloadWithMeteredWarning(downloadId: String, isResume: Boolean) {
        val c = controller ?: return
        val proceed = {
            if (isResume) c.resumeDownload(downloadId)
            else c.startDownload(downloadId)
        }
        if (shouldWarnMeteredNetwork()) {
            dispatch(ShowMeteredNetworkWarning(onConfirm = proceed))
        } else {
            proceed()
        }
    }

    fun onInstallClicked(downloadId: String) {
        val c = controller ?: return

        if (!UtilsKt.isBatteryLevelOk(context)) {
            dispatch(ShowBatteryLowDialog)
            return
        }
        if (UtilsKt.isScratchMounted()) {
            dispatch(ShowScratchMountedDialog)
            return
        }

        val update = c.getUpdate(downloadId) ?: return
        val canInstall = Utils.canInstall(update)

        if (!canInstall && !(update.status != UpdateStatus.VERIFIED && update.availableOnline)) {
            dispatch(ShowNotInstallableToast)
            return
        }

        val isStreaming = preferences.getBoolean(Constants.PREF_AB_STREAMING_MODE, false)
                && update.availableOnline

        val messageRes = try {
            when {
                isStreaming                   -> R.string.apply_update_dialog_message_streaming
                Utils.isABUpdate(update.file) -> R.string.apply_update_dialog_message_ab
                else                          -> R.string.apply_update_dialog_message
            }
        } catch (_: IOException) {
            dispatch(ShowNotInstallableToast)
            return
        }

        val buildDate = StringGenerator.getDateLocalizedUTC(context, DateFormat.MEDIUM, update.timestamp)
        val buildInfo = context.getString(R.string.list_build_version_date, update.version, buildDate)

        dispatch(ShowInstallConfirmDialog(
            messageRes = messageRes,
            buildInfo  = buildInfo,
            onConfirm  = {
                if (isStreaming) triggerWithMeteredWarning(downloadId)
                else Utils.triggerUpdate(context, downloadId)
            },
        ))
    }

    fun onInfoClicked() = dispatch(ShowInfoDialog(blockedUpdateMessage()))

    fun onDeleteClicked(downloadId: String) {
        val c = controller ?: return
        dispatch(ShowDeleteDialog(onConfirm = {
            c.pauseDownload(downloadId)
            c.deleteUpdate(downloadId)
        }))
    }

    fun onCancelDownloadClicked(downloadId: String) {
        val c = controller ?: return
        dispatch(ShowCancelDownloadDialog(onConfirm = { c.cancelDownload(downloadId) }))
    }

    fun onCancelInstallationClicked() = dispatch(ShowCancelInstallationDialog(
        onConfirm = { sendServiceAction(UpdaterService.ACTION_INSTALL_STOP) }
    ))

    fun onSuspendInstallation() = sendServiceAction(UpdaterService.ACTION_INSTALL_SUSPEND)

    fun onResumeInstallation() = sendServiceAction(UpdaterService.ACTION_INSTALL_RESUME)

    fun onReboot() = context.getSystemService(PowerManager::class.java)?.reboot(null)

    fun copyDownloadUrl(downloadId: String) {
        val url = controller?.getUpdate(downloadId)?.downloadUrl ?: return
        Utils.addToClipboard(context, context.getString(R.string.label_download_url), url)
    }

    fun getUpdateForExport(downloadId: String): UpdateInfo? =
        controller?.getUpdate(downloadId)

    private fun buildMenuState(update: UpdateInfo): MenuState {
        val isVerified = update.status == UpdateStatus.VERIFIED
        val canDelete  = isVerified && (Utils.canInstall(update) || update.availableOnline)
        return MenuState(
            showDelete  = canDelete,
            showCopyUrl = update.availableOnline,
            showExport  = isVerified && update.file?.exists() == true,
        )
    }

    private fun canResume(downloadId: String, controller: UpdaterController): Boolean {
        val update = controller.getUpdate(downloadId) ?: return false
        return Utils.canInstall(update) || (update.file?.length() ?: 0L) == update.fileSize
    }

    private fun shouldWarnMeteredNetwork() =
        preferences.getBoolean(Constants.PREF_METERED_NETWORK_WARNING, true)
                && Utils.isNetworkMetered(context)

    private fun triggerWithMeteredWarning(downloadId: String) {
        if (shouldWarnMeteredNetwork()) {
            dispatch(ShowMeteredNetworkWarning(
                onConfirm = { Utils.triggerStreamingUpdate(context, downloadId) }
            ))
        } else {
            Utils.triggerStreamingUpdate(context, downloadId)
        }
    }

    private fun blockedUpdateMessage() = String.format(
        StringGenerator.getCurrentLocale(context),
        context.getString(R.string.blocked_update_dialog_message),
        Utils.getUpgradeBlockedURL(context),
    )

    private fun sendServiceAction(action: String) {
        context.startService(
            Intent(context, UpdaterService::class.java).setAction(action)
        )
    }

    private fun dispatch(event: UpdateListItemUiEvent) {
        viewModelScope.launch { _uiEvents.emit(event) }
    }

    private fun Int.toPct() = percentFormat.format(this / 100f)
    private fun Long.toFileSize() = Formatter.formatShortFileSize(context, this)

    private data class ActiveFields(
        val action: PrimaryAction,
        val enabled: Boolean,
        val indeterminate: Boolean,
        val value: Int,
        val text: StringWrapper,
        val pct: String,
    )

    class Factory(
        private val application: Application,
        private val preferences: SharedPreferences,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            UpdateListItemViewModel(application, preferences) as T
    }
}
