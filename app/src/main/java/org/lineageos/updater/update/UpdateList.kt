/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.update

import android.content.Intent
import android.os.PowerManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsSpace
import com.android.settingslib.spa.widget.preference.ZeroStatePreference
import com.android.settingslib.spa.widget.ui.Category
import org.lineageos.updater.R
import org.lineageos.updater.controller.UpdaterController
import org.lineageos.updater.controller.UpdaterService
import org.lineageos.updater.data.PreferencesRepository
import org.lineageos.updater.data.Update
import org.lineageos.updater.misc.Utils
import org.lineageos.updater.util.BatteryMonitor
import org.lineageos.updater.util.NetworkState

/**
 * Composable update list with a "spotlight latest" design: item[0] (most recent by timestamp) is
 * always visible in an untitled [Category]; older updates are revealed by a [CollapseBar] toggle
 * and rendered as full interactive [UpdateListItem] cards under [AnimatedVisibility].
 *
 * All confirmation dialogs are owned by [UpdateListDialogs] / [rememberUpdateListDialogs].
 *
 * @param updates           Current list of known updates from the repository.
 * @param updaterController Live controller reference; null while the service is not yet bound.
 * @param networkState      Current network connectivity state.
 * @param onExportUpdate    Called when the user selects "Export" from the overflow menu.
 * @param progressRevision  Opaque counter; increment to force re-read of in-flight controller
 *                          state (e.g. on ACTION_DOWNLOAD_PROGRESS broadcasts).
 */
@Composable
fun UpdateList(
    updates: List<Update>,
    updaterController: UpdaterController?,
    networkState: NetworkState,
    onExportUpdate: (Update) -> Unit,
    progressRevision: Int = 0,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    val batteryState by remember { BatteryMonitor.getInstance(context).batteryState }
        .collectAsStateWithLifecycle()
    val meteredWarning by remember { PreferencesRepository(context).meteredNetworkWarningFlow }
        .collectAsStateWithLifecycle(initialValue = true)

    val dialogs = rememberUpdateListDialogs(
        updaterController = updaterController,
        networkState = networkState,
        meteredWarning = meteredWarning,
        batteryState = batteryState,
    )

    // ── Action handlers ──────────────────────────────────────────────────────────────────────────

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
            UpdateListMenuAction.ViewDownloads -> uriHandler.openUri(Utils.getDownloadsURL(context))
        }
    }

    @Composable
    fun Item(update: Update) {
        val liveUpdate = remember(update, updaterController, progressRevision) {
            updaterController?.getUpdate(update.downloadId) ?: update
        }
        val itemState = remember(liveUpdate, updaterController, networkState, progressRevision) {
            if (updaterController != null)
                UpdateListItemState.create(context, liveUpdate, updaterController, networkState)
            else
                UpdateListItemState.Idle()
        }
        UpdateListItem(
            update = liveUpdate,
            state = itemState,
            onPrimaryAction = { onPrimaryAction(liveUpdate, it) },
            onSecondaryAction = { onSecondaryAction(liveUpdate, it) },
            onMenuAction = { onMenuAction(liveUpdate, it) },
        )
    }

    // ── UI ───────────────────────────────────────────────────────────────────────────────────────

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
            Column(verticalArrangement = Arrangement.spacedBy(SettingsSpace.extraSmall1)) {
                sorted.drop(1).forEach { update -> Item(update) }
            }
        }
    }

    if (hiddenCount > 0) {
        CollapseBar(
            expandText = pluralStringResource(R.plurals.see_more_updates, hiddenCount, hiddenCount),
            collapseText = stringResource(R.string.see_less_updates),
            expanded = showAll,
            onExpandedChange = { showAll = it },
        )
    }
}

/**
 * Copied from [com.android.settingslib.spa.widget.preference.TopIntroPreference], adapted to
 * accept plain strings instead of [TopIntroPreferenceModel].
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CollapseBar(
    expandText: String,
    collapseText: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onExpandedChange(!expanded) }
            .padding(
                top = SettingsSpace.extraSmall4,
                bottom = SettingsSpace.small1,
                start = SettingsSpace.small4,
                end = SettingsSpace.small4,
            ),
    ) {
        Icon(
            imageVector = if (expanded) Icons.Filled.KeyboardArrowUp
                          else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier
                .size(SettingsDimension.itemIconSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
        Spacer(Modifier.width(SettingsSpace.extraSmall4))
        Text(
            text = if (expanded) collapseText else expandText,
            style = MaterialTheme.typography.bodyLargeEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
