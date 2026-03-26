/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.update

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.lerp
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.tooling.preview.Preview
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsShape
import com.android.settingslib.spa.framework.theme.SettingsSize
import com.android.settingslib.spa.framework.theme.SettingsSpace
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.widget.preference.ProgressBarPreference
import com.android.settingslib.spa.widget.preference.ProgressBarPreferenceModel
import com.android.settingslib.spa.widget.preference.ProgressBarWithDataPreference
import com.android.settingslib.spa.widget.scaffold.MoreOptionsAction
import com.android.settingslib.spa.widget.ui.SettingsIcon
import com.android.settingslib.spa.widget.ui.SettingsBody
import org.lineageos.updater.R
import org.lineageos.updater.data.Update
import org.lineageos.updater.data.UpdateStatus
import org.lineageos.updater.misc.StringGenerator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UpdateListItem(
    update: Update,
    state: UpdateListItemState,
    onPrimaryAction: (UpdateListPrimaryAction) -> Unit,
    onSecondaryAction: (UpdateListSecondaryAction) -> Unit,
    onMenuAction: (UpdateListMenuAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val locale = remember { StringGenerator.getCurrentLocale(context) }

    val transition = updateTransition(targetState = expanded, label = "expandCollapse")
    val textFraction by transition.animateFloat(label = "textFraction") { if (it) 1f else 0f }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceBright,
                shape = SettingsShape.CornerExtraSmall2,
            )
            .animateContentSize(),
    ) {
        // Header — always visible, toggles expansion on tap; no ripple per design
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) {
                    expanded = !expanded
                }
                .heightIn(min = SettingsDimension.preferenceMinHeight)
                .padding(
                    start = SettingsDimension.itemPaddingStart,
                    end = SettingsDimension.itemPaddingEnd,
                    top = SettingsDimension.itemPaddingVertical,
                    bottom = SettingsDimension.itemPaddingVertical,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Icon + trailing spacer — fades and shrinks out horizontally when expanded.
            // Box size and trailing spacer match BaseIcon (expressive branch) from
            // com.android.settingslib.spa.widget.preference.BaseLayout.
            AnimatedVisibility(visible = !expanded) {
                Row {
                    Box(
                        modifier = Modifier.size(SettingsSize.medium3),
                        contentAlignment = Alignment.Center,
                    ) {
                        SettingsIcon(collapsedIcon(update))
                    }
                    Spacer(Modifier.width(SettingsSpace.extraSmall6))
                }
            }
            // Date + version — grow into freed icon space and scale up when expanded
            val dateStyle = lerp(
                start = MaterialTheme.typography.bodyLarge.copy(textMotion = TextMotion.Animated),
                stop = MaterialTheme.typography.titleLarge.copy(textMotion = TextMotion.Animated),
                fraction = textFraction,
            )
            val versionStyle = lerp(
                start = MaterialTheme.typography.bodyMedium.copy(textMotion = TextMotion.Animated),
                stop = MaterialTheme.typography.bodyLarge.copy(textMotion = TextMotion.Animated),
                fraction = textFraction,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = remember(update.timestamp, locale) {
                        formatShortDate(update.timestamp, locale)
                    },
                    style = dateStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    text = stringResource(R.string.list_build_version, update.version),
                    style = versionStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Crossfade(targetState = expanded, label = "chevron") { isExpanded ->
                Icon(
                    imageVector = if (isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Expanded section — animateContentSize on the Column handles the smooth size transition
        if (expanded) {
            Column(
                modifier = Modifier.padding(
                    bottom = SettingsDimension.itemPaddingEnd,
                ),
            ) {
                when (state) {
                    is UpdateListItemState.Active -> state.progress?.let { progress ->
                        when (progress) {
                            is UpdateListProgressState.Percent -> {
                                val fraction = progress.value / 100f
                                val label = "${progress.value}%"
                                ProgressBarWithDataPreference(
                                    model = object : ProgressBarPreferenceModel {
                                        override val title = label
                                        override val progress = fraction
                                    },
                                    data = progress.text,
                                )
                            }
                            is UpdateListProgressState.Indeterminate -> {
                                ProgressBarPreference(
                                    model = object : ProgressBarPreferenceModel {
                                        override val title = progress.text
                                        override val progress = 0f
                                    },
                                )
                            }
                        }
                    }

                    is UpdateListItemState.Idle ->
                        Box(
                            modifier = Modifier.padding(
                                start = SettingsDimension.itemPaddingStart,
                                end = SettingsDimension.itemPaddingEnd,
                            ),
                        ) {
                            SettingsBody(
                                body = Formatter.formatShortFileSize(context, update.fileSize),
                            )
                        }
                }

                // Action row — start-aligned; button style varies by action type
                val primaryAction = state.primaryAction
                val secondaryAction = (state as? UpdateListItemState.Active)?.secondaryAction
                if (primaryAction != null || secondaryAction != null || state.menuActions.isNotEmpty()) {
                    Spacer(Modifier.height(SettingsDimension.buttonPaddingVertical))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = SettingsDimension.itemPaddingStart,
                                end = SettingsDimension.itemPaddingEnd,
                            ),
                        horizontalArrangement = Arrangement.spacedBy(SettingsSpace.extraSmall2),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Cancel — outlined text button, leftmost
                        if (secondaryAction != null) {
                            OutlinedButton(
                                onClick = { onSecondaryAction(secondaryAction) },
                                enabled = secondaryAction.enabled,
                            ) {
                                Text(stringResource(android.R.string.cancel))
                            }
                        }
                        if (primaryAction != null) {
                            when (primaryAction) {
                                // Pause / Resume — media-style filled icon buttons (expressive shape)
                                is UpdateListPrimaryAction.Pause,
                                is UpdateListPrimaryAction.Resume ->
                                    FilledIconButton(
                                        onClick = { onPrimaryAction(primaryAction) },
                                        shapes = IconButtonDefaults.shapes(),
                                        enabled = primaryAction.enabled,
                                    ) {
                                        Icon(
                                            imageVector = primaryActionIcon(primaryAction),
                                            contentDescription = primaryActionLabel(primaryAction),
                                        )
                                    }
                                // Download / Install — expressive filled tonal text buttons
                                is UpdateListPrimaryAction.Start ->
                                    FilledTonalButton(
                                        onClick = { onPrimaryAction(primaryAction) },
                                        shapes = ButtonDefaults.shapes(),
                                        enabled = primaryAction.enabled,
                                    ) {
                                        Text(primaryActionLabel(primaryAction))
                                    }
                                // Info / Reboot — tonal icon buttons (expressive shape)
                                else ->
                                    FilledTonalIconButton(
                                        onClick = { onPrimaryAction(primaryAction) },
                                        shapes = IconButtonDefaults.shapes(),
                                        enabled = primaryAction.enabled,
                                    ) {
                                        Icon(
                                            imageVector = primaryActionIcon(primaryAction),
                                            contentDescription = primaryActionLabel(primaryAction),
                                        )
                                    }
                            }
                        }
                        // More options — pushed to the end of the row
                        if (state.menuActions.isNotEmpty()) {
                            Spacer(Modifier.weight(1f))
                            MoreOptionsAction {
                                state.menuActions.forEach { action ->
                                    MenuItem(
                                        text = menuActionLabel(action),
                                        onClick = { onMenuAction(action) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Collapsed-state icon: download arrow for updates available online that have not yet been
 * verified locally; storage icon for updates already present in local storage.
 */
private fun collapsedIcon(update: Update): ImageVector =
    if (update.isAvailableOnline && update.status != UpdateStatus.VERIFIED)
        Icons.Outlined.Download
    else
        Icons.Outlined.Storage

private fun formatShortDate(timestamp: Long, locale: Locale): String {
    val date = Instant.ofEpochSecond(timestamp)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
    val pattern = if (date.year == LocalDate.now().year) "MMM d" else "MMM d, yyyy"
    return DateTimeFormatter.ofPattern(pattern, locale).format(date)
}

private fun operationIcon(operation: UpdateListOperation): ImageVector =
    when (operation) {
        UpdateListOperation.Download -> Icons.Outlined.Download
        UpdateListOperation.Install -> Icons.Outlined.SystemUpdate
    }

@Composable
private fun primaryActionLabel(action: UpdateListPrimaryAction): String = when (action) {
    is UpdateListPrimaryAction.Start -> when (action.operation) {
        UpdateListOperation.Download -> stringResource(R.string.action_download)
        UpdateListOperation.Install -> stringResource(R.string.action_install)
    }
    is UpdateListPrimaryAction.Pause -> stringResource(R.string.action_pause)
    is UpdateListPrimaryAction.Resume -> stringResource(R.string.action_resume)
    is UpdateListPrimaryAction.Info -> stringResource(R.string.action_info)
    UpdateListPrimaryAction.Reboot -> stringResource(R.string.reboot)
}

private fun primaryActionIcon(action: UpdateListPrimaryAction): ImageVector = when (action) {
    is UpdateListPrimaryAction.Start -> operationIcon(action.operation)
    is UpdateListPrimaryAction.Pause -> Icons.Outlined.Pause
    is UpdateListPrimaryAction.Resume -> Icons.Outlined.PlayArrow
    is UpdateListPrimaryAction.Info -> Icons.Outlined.Info
    UpdateListPrimaryAction.Reboot -> Icons.Outlined.RestartAlt
}

@Composable
private fun menuActionLabel(action: UpdateListMenuAction): String = when (action) {
    UpdateListMenuAction.Delete -> stringResource(R.string.action_delete)
    UpdateListMenuAction.Export -> stringResource(R.string.menu_export_update)
    UpdateListMenuAction.ViewDownloads -> stringResource(
        R.string.menu_view_downloads,
        stringResource(R.string.brand_name),
    )
}

@Preview
@Composable
private fun UpdateListItemIdlePreview() {
    SettingsTheme {
        UpdateListItem(
            update = Update(version = "23.2", timestamp = 0L, fileSize = 1_100_000_000L),
            state = UpdateListItemState.Idle(
                primaryAction = UpdateListPrimaryAction.Start(
                    operation = UpdateListOperation.Download,
                    enabled = true,
                ),
                menuActions = setOf(UpdateListMenuAction.ViewDownloads),
            ),
            onPrimaryAction = {},
            onSecondaryAction = {},
            onMenuAction = {},
        )
    }
}

@Preview
@Composable
private fun UpdateListItemDownloadingPreview() {
    SettingsTheme {
        UpdateListItem(
            update = Update(version = "23.2", timestamp = 0L, fileSize = 1_100_000_000L),
            state = UpdateListItemState.Active(
                primaryAction = UpdateListPrimaryAction.Pause(UpdateListOperation.Download),
                secondaryAction = UpdateListSecondaryAction.Cancel(UpdateListOperation.Download),
                progress = UpdateListProgressState.Percent(
                    text = "162 MB of 1.1 GB · 3 minutes left",
                    value = 15,
                ),
                menuActions = setOf(UpdateListMenuAction.ViewDownloads),
            ),
            onPrimaryAction = {},
            onSecondaryAction = {},
            onMenuAction = {},
        )
    }
}

@Preview
@Composable
private fun UpdateListItemVerifyingPreview() {
    SettingsTheme {
        UpdateListItem(
            update = Update(version = "23.2", timestamp = 0L),
            state = UpdateListItemState.Active(
                primaryAction = UpdateListPrimaryAction.Start(
                    operation = UpdateListOperation.Install,
                    enabled = false,
                ),
                progress = UpdateListProgressState.Indeterminate(text = "Verifying update…"),
            ),
            onPrimaryAction = {},
            onSecondaryAction = {},
            onMenuAction = {},
        )
    }
}
