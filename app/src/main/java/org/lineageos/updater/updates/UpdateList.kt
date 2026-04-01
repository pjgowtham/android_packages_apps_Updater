/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.updates

import android.content.Intent
import android.os.PowerManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.android.settingslib.spa.framework.theme.SettingsSpace
import com.android.settingslib.spa.widget.preference.ZeroStatePreference
import com.android.settingslib.spa.widget.ui.Category
import org.lineageos.updater.R
import org.lineageos.updater.controller.UpdaterController
import org.lineageos.updater.controller.UpdaterService
import org.lineageos.updater.data.Update
import org.lineageos.updater.deviceinfo.DeviceInfoUtils
import org.lineageos.updater.ui.preference.CollapsePreference
import org.lineageos.updater.ui.preference.CollapsePreferenceModel
import org.lineageos.updater.util.BatteryState
import org.lineageos.updater.util.NetworkState

@Composable
fun UpdateList(
    updates: List<Update>,
    updaterController: UpdaterController?,
    networkState: NetworkState,
    batteryState: BatteryState,
    meteredWarning: Boolean,
    onExportUpdate: (Update) -> Unit,
    progressRevision: Int = 0,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    val dialogs = rememberUpdateListDialogs(
        updaterController = updaterController,
        networkState = networkState,
        meteredWarning = meteredWarning,
        batteryState = batteryState,
    )

    fun onPrimaryAction(update: Update, action: UpdateListPrimaryAction) {
        when (action) {
            is UpdateListPrimaryAction.Start -> when (action.operation) {
                UpdateListOperation.Download -> dialogs.initiateDownload(update.downloadId)
                UpdateListOperation.Install -> dialogs.initiateInstall(update)
            }

            is UpdateListPrimaryAction.Pause -> when (action.operation) {
                UpdateListOperation.Download -> updaterController?.pauseDownload(update.downloadId)
                UpdateListOperation.Install -> context.startService(
                    Intent(context, UpdaterService::class.java).apply {
                        this.action = UpdaterService.ACTION_INSTALL_SUSPEND
                    }
                )
            }

            is UpdateListPrimaryAction.Resume -> when (action.operation) {
                UpdateListOperation.Download -> dialogs.initiateResume(update.downloadId)
                UpdateListOperation.Install -> context.startService(
                    Intent(context, UpdaterService::class.java).apply {
                        this.action = UpdaterService.ACTION_INSTALL_RESUME
                    }
                )
            }

            is UpdateListPrimaryAction.Info -> dialogs.showBlockedInfo()
            UpdateListPrimaryAction.Reboot ->
                context.getSystemService(PowerManager::class.java).reboot(null)
        }
    }

    fun onSecondaryAction(update: Update, action: UpdateListSecondaryAction) {
        when (action) {
            is UpdateListSecondaryAction.Cancel -> when (action.operation) {
                UpdateListOperation.Download -> dialogs.confirmCancelDownload(update.downloadId)
                UpdateListOperation.Install -> dialogs.confirmCancelInstall()
            }
        }
    }

    fun onMenuAction(update: Update, action: UpdateListMenuAction) {
        when (action) {
            UpdateListMenuAction.Delete -> dialogs.confirmDelete(update.downloadId)
            UpdateListMenuAction.Export -> onExportUpdate(update)
            UpdateListMenuAction.ViewDownloads -> uriHandler.openUri(
                context.getString(R.string.menu_downloads_url, DeviceInfoUtils.device)
            )
        }
    }

    @Composable
    fun Item(update: Update) {
        val liveUpdate = remember(update, updaterController, progressRevision) {
            updaterController?.getUpdate(update.downloadId) ?: update
        }
        val itemState = remember(liveUpdate, updaterController, networkState, progressRevision) {
            if (updaterController != null) {
                UpdateListItemState.create(context, liveUpdate, updaterController, networkState)
            } else {
                UpdateListItemState.Idle()
            }
        }
        UpdateListItem(
            update = liveUpdate,
            state = itemState,
            onPrimaryAction = { onPrimaryAction(liveUpdate, it) },
            onSecondaryAction = { onSecondaryAction(liveUpdate, it) },
            onMenuAction = { onMenuAction(liveUpdate, it) },
        )
    }

    if (updates.isEmpty()) {
        ZeroStatePreference(
            icon = Icons.Outlined.Check,
            text = stringResource(R.string.snack_no_updates_found),
            description = stringResource(R.string.list_no_updates),
        )
        return
    }

    val sorted = remember(updates) { updates.sortedByDescending { it.timestamp } }
    val hiddenCount = sorted.size - 1
    var showAll by remember { mutableStateOf(false) }

    Category {
        Item(sorted[0])

        AnimatedVisibility(visible = showAll && hiddenCount > 0) {
            Column(
                verticalArrangement = Arrangement.spacedBy(SettingsSpace.extraSmall1),
            ) {
                sorted.drop(1).forEach { update ->
                    Item(update)
                }
            }
        }
    }

    if (hiddenCount > 0) {
        CollapsePreference(
            model = CollapsePreferenceModel(
                expandText = pluralStringResource(
                    R.plurals.see_more_updates,
                    hiddenCount,
                    hiddenCount,
                ),
                collapseText = stringResource(R.string.see_less_updates),
            ),
            expanded = showAll,
            onExpandedChange = { showAll = it },
        )
    }
}
