/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.updates

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.android.settingslib.spa.debug.UiModePreviews
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.ui.LinearProgressBar
import org.lineageos.updater.updates.action.UpdateAction
import org.lineageos.updater.updates.action.UpdateActionType
import org.lineageos.updater.updates.action.UpdateActions
import org.lineageos.updater.updates.button.ActionBar
import org.lineageos.updater.updates.button.ActionBarButton
import org.lineageos.updater.updates.state.ProgressState
import org.lineageos.updater.updates.state.UpdateItemState

@Composable
fun UpdateItem(
    state: UpdateItemState,
    expanded: Boolean,
    onAction: (UpdateAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        val subtitle = buildString {
            append(state.buildVersion)
            if (state.status.isNotEmpty()) {
                append(" · ")
                append(state.status)
            }
        }

        Preference(object : PreferenceModel {
            override val title = state.buildDate
            override val summary = { subtitle }
            override val icon = @Composable {
                AnimatedVisibility(
                    visible = !expanded,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    if (state.isLocal) {
                        Icon(imageVector = Icons.Outlined.Archive, contentDescription = null)
                    } else {
                        Icon(imageVector = Icons.Outlined.CloudDownload, contentDescription = null)
                    }
                }
            }
        })

        AnimatedVisibility(visible = expanded) {
            ExpandedContent(state, onAction)
        }
    }
}

@Composable
private fun ExpandedContent(
    state: UpdateItemState,
    onAction: (UpdateAction) -> Unit,
) {
    Column {
        ProgressSection(state.progress, state.fileSize)
        ActionsSection(state.actions, onAction)
    }
}

@Composable
private fun ProgressSection(progress: ProgressState?, fileSize: String) {
    when (progress) {
        is ProgressState.Determinate -> {
            Column(
                modifier = Modifier.padding(
                    horizontal = SettingsDimension.itemPaddingStart,
                ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Percentage at start
                    Text(
                        text = "${progress.progress.toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (progress.downloadedFileSize.isNotEmpty()) {
                        Text(
                            text = " · ${progress.downloadedFileSize}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        // Push ETA to the end
                        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    }

                    // ETA at end
                    if (progress.eta.isNotEmpty()) {
                        Text(
                            text = progress.eta,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                LinearProgressBar(progress = progress.progress / 100f)
            }
        }

        ProgressState.Indeterminate -> {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SettingsDimension.itemPaddingStart),
            )
        }

        null -> {
            Text(
                text = fileSize,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    horizontal = SettingsDimension.itemPaddingStart,
                ),
            )
        }
    }
}

@Composable
private fun ActionsSection(
    actions: UpdateActions,
    onAction: (UpdateAction) -> Unit,
) {
    val context = LocalContext.current
    val buttons = buildList {
        actions.secondary?.let { secondary ->
            add(
                ActionBarButton.Outlined(
                    text = secondary.title(context),
                    enabled = secondary.enabled,
                    onClick = { onAction(secondary) },
                )
            )
        }
        actions.primary?.let { primary ->
            add(
                ActionBarButton.Tonal(
                    text = primary.title(context),
                    enabled = primary.enabled,
                    onClick = { onAction(primary) },
                )
            )
        }
    }

    val overflowActions = actions.overflow
    ActionBar(
        buttons = buttons,
        menuContent = if (overflowActions.isNotEmpty()) {
            {
                overflowActions.forEach { action ->
                    MenuItem(text = action.title(context)) {
                        onAction(action)
                    }
                }
            }
        } else {
            null
        },
    )
}

@UiModePreviews
@Composable
private fun UpdateItemIdleCollapsedPreview() {
    SettingsTheme {
        UpdateItem(
            state = UpdateItemState(
                downloadId = "preview",
                buildDate = "April 27, 2026",
                buildVersion = "LineageOS 23.2",
                status = "",
                isLocal = false, // Remote update -> CloudDownload icon
                fileSize = "1.1 GB",
                progress = null,
                actions = UpdateActions(
                    primary = UpdateAction(type = UpdateActionType.START_DOWNLOAD),
                    overflow = listOf(UpdateAction(type = UpdateActionType.VIEW_DOWNLOADS))
                ),
            ),
            expanded = false,
            onAction = {},
        )
    }
}

@UiModePreviews
@Composable
private fun UpdateItemLocalCollapsedPreview() {
    SettingsTheme {
        UpdateItem(
            state = UpdateItemState(
                downloadId = "preview",
                buildDate = "April 27, 2026",
                buildVersion = "LineageOS 23.2",
                status = "Local update",
                isLocal = true, // Local update -> Archive icon
                fileSize = "1.1 GB",
                progress = null,
                actions = UpdateActions(
                    primary = UpdateAction(type = UpdateActionType.START_INSTALL),
                    overflow = listOf(UpdateAction(type = UpdateActionType.DELETE))
                ),
            ),
            expanded = false,
            onAction = {},
        )
    }
}

@UiModePreviews
@Composable
private fun UpdateItemDownloadingPreview() {
    SettingsTheme {
        UpdateItem(
            state = UpdateItemState(
                downloadId = "preview",
                buildDate = "April 27, 2026",
                buildVersion = "LineageOS 23.2",
                status = "Downloading",
                isLocal = false,
                fileSize = "1.1 GB",
                progress = ProgressState.Determinate(
                    progress = 65f,
                    downloadedFileSize = "715 MB of 1.1 GB",
                    eta = "3 min left",
                ),
                actions = UpdateActions(
                    primary = UpdateAction(type = UpdateActionType.PAUSE_DOWNLOAD),
                    secondary = UpdateAction(type = UpdateActionType.CANCEL_DOWNLOAD),
                    overflow = listOf(UpdateAction(type = UpdateActionType.VIEW_DOWNLOADS))
                ),
            ),
            expanded = true,
            onAction = {},
        )
    }
}

@UiModePreviews
@Composable
private fun UpdateItemVerifyingPreview() {
    SettingsTheme {
        UpdateItem(
            state = UpdateItemState(
                downloadId = "preview",
                buildDate = "April 27, 2026",
                buildVersion = "LineageOS 23.2",
                status = "Verifying update",
                isLocal = false,
                fileSize = "1.1 GB",
                progress = ProgressState.Indeterminate,
                actions = UpdateActions(
                    primary = null, // No primary action while verifying usually
                    overflow = listOf(UpdateAction(type = UpdateActionType.VIEW_DOWNLOADS))
                ),
            ),
            expanded = true,
            onAction = {},
        )
    }
}
