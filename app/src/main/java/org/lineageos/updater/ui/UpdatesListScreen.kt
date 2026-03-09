/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.settingslib.spa.framework.compose.thenIf
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsShape
import com.android.settingslib.spa.widget.scaffold.MoreOptionsAction
import com.android.settingslib.spa.widget.ui.Category
import com.android.settingslib.spa.widget.ui.SettingsTitleSmall
import org.lineageos.updater.R
import org.lineageos.updater.model.UpdateInfo
import org.lineageos.updater.viewmodel.UpdateListItemViewModel

/**
 *
 * Layout order (top to bottom):
 * 1. Latest update — always expanded
 * 2. Any other active (downloading/installing) items — always expanded
 * 3. Older inactive updates — shown above "See all" when toggled
 * 4. "See all (N)" / "See less" — toggle row, always at the bottom
 */
@Composable
fun UpdatesListScreen(
    downloadIds: List<String>,
    viewModel: UpdateListItemViewModel,
    onExportUpdate: (UpdateInfo) -> Unit,
) {
    if (downloadIds.isEmpty()) return

    val states by viewModel.itemStates.collectAsState()

    var showAll by rememberSaveable { mutableStateOf(false) }
    var expandedIds by rememberSaveable { mutableStateOf(emptySet<String>()) }

    val latestId = downloadIds.first()
    val olderIds = downloadIds.drop(1)

    // Partition older items: active ones always visible, inactive behind "see all"
    val olderActiveIds = olderIds.filter { states[it] is UpdateListItemUiState.Active }
    val olderInactiveIds = olderIds.filter { states[it] !is UpdateListItemUiState.Active }
    val hasHiddenUpdates = olderInactiveIds.isNotEmpty()

    Category {
        // Latest update — always expanded
        UpdateItem(
            downloadId = latestId,
            state = states[latestId] ?: UpdateListItemUiState.LOADING,
            viewModel = viewModel,
            onExportUpdate = onExportUpdate,
            isExpanded = true,
            isLatest = true,
            onToggleExpand = null,
        )

        // Other active items — always expanded, never collapsible
        olderActiveIds.forEach { downloadId ->
            UpdateItem(
                downloadId = downloadId,
                state = states[downloadId] ?: UpdateListItemUiState.LOADING,
                viewModel = viewModel,
                onExportUpdate = onExportUpdate,
                isExpanded = true,
                isLatest = false,
                onToggleExpand = null,
            )
        }

        // Older inactive updates — animated, appear ABOVE the "See all" row
        AnimatedVisibility(
            visible = showAll,
            enter = expandVertically(
                @OptIn(ExperimentalMaterial3ExpressiveApi::class)
                MaterialTheme.motionScheme.fastSpatialSpec()
            ),
            exit = shrinkVertically(
                @OptIn(ExperimentalMaterial3ExpressiveApi::class)
                MaterialTheme.motionScheme.fastSpatialSpec()
            ),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                olderInactiveIds.forEach { downloadId ->
                    val isExpanded = downloadId in expandedIds
                    UpdateItem(
                        downloadId = downloadId,
                        state = states[downloadId] ?: UpdateListItemUiState.LOADING,
                        viewModel = viewModel,
                        onExportUpdate = onExportUpdate,
                        isExpanded = isExpanded,
                        isLatest = false,
                        onToggleExpand = {
                            expandedIds = if (isExpanded) {
                                expandedIds - downloadId
                            } else {
                                expandedIds + downloadId
                            }
                        },
                    )
                }
            }
        }

        // "See all (N)" / "See less" toggle row — always at the bottom
        if (hasHiddenUpdates) {
            val surfaceBright = MaterialTheme.colorScheme.surfaceBright
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = SettingsDimension.preferenceMinHeight)
                    .background(
                        color = surfaceBright,
                        shape = SettingsShape.CornerExtraSmall2,
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { showAll = !showAll }
                    .padding(
                        horizontal = SettingsDimension.itemPaddingStart,
                        vertical = SettingsDimension.itemPaddingVertical,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(
                    SettingsDimension.itemPaddingStart,
                ),
            ) {
                Icon(
                    if (showAll) Icons.Outlined.ExpandLess
                    else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(SettingsDimension.itemIconSize),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Box(modifier = Modifier.weight(1f)) {
                    SettingsTitleSmall(
                        if (showAll) stringResource(R.string.see_less)
                        else stringResource(R.string.see_all) +
                                " (${olderInactiveIds.size})",
                        useMediumWeight = true,
                    )
                }
            }
        }
    }
}

// region Update item

/**
 * A single update item. Uses [AnimatedVisibility] to animate
 * the icon, texts, and details expansion independently.
 */
@Composable
private fun UpdateItem(
    downloadId: String,
    state: UpdateListItemUiState,
    viewModel: UpdateListItemViewModel,
    onExportUpdate: (UpdateInfo) -> Unit,
    isExpanded: Boolean,
    isLatest: Boolean,
    onToggleExpand: (() -> Unit)?,
) {
    val surfaceBright = MaterialTheme.colorScheme.surfaceBright

    Column(
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SettingsDimension.preferenceMinHeight)
            .background(color = surfaceBright, shape = SettingsShape.CornerExtraSmall2)
            .let { mod ->
                if (onToggleExpand != null) mod.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggleExpand,
                ) else mod
            }
            .padding(
                horizontal = SettingsDimension.itemPaddingStart,
                vertical = SettingsDimension.itemPaddingVertical,
            ),
    ) {
        // Header Row
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedVisibility(
                visible = !isExpanded,
                enter = fadeIn() + expandHorizontally(expandFrom = Alignment.Start, clip = false),
                exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.Start, clip = false),
            ) {
                Icon(
                    Icons.Outlined.FileDownload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(end = SettingsDimension.itemPaddingStart)
                        .size(SettingsDimension.itemIconSize),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                val titleStyle = MaterialTheme.typography.titleLarge
                val titleCollapsedStyle = MaterialTheme.typography.titleMedium
                val bodyStyle = MaterialTheme.typography.bodyLarge
                val bodyCollapsedStyle = MaterialTheme.typography.bodyMedium
                val titleFontSize by animateFloatAsState(
                    targetValue = if (isExpanded) titleStyle.fontSize.value else titleCollapsedStyle.fontSize.value,
                    label = "titleFontSize",
                )
                val bodyFontSize by animateFloatAsState(
                    targetValue = if (isExpanded) bodyStyle.fontSize.value else bodyCollapsedStyle.fontSize.value,
                    label = "bodyFontSize",
                )
                Text(
                    text = state.buildDate,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = titleCollapsedStyle.copy(fontSize = titleFontSize.sp),
                )
                Text(
                    text = state.buildVersion,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = bodyCollapsedStyle.copy(fontSize = bodyFontSize.sp),
                )
            }

            if (onToggleExpand != null) {
                Icon(
                    if (isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.padding(start = SettingsDimension.itemPaddingStart),
                )
            }
        }

        // Details content
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column {
                when (state) {
                    is UpdateListItemUiState.Active -> {
                        Spacer(Modifier.height(16.dp))
                        ActiveDetails(
                            state = state,
                            downloadId = downloadId,
                            viewModel = viewModel,
                            onExportUpdate = onExportUpdate,
                            isLatest = isLatest,
                        )
                    }

                    is UpdateListItemUiState.Inactive -> {
                        Spacer(Modifier.height(2.dp))
                        InactiveDetails(
                            state = state,
                            downloadId = downloadId,
                            viewModel = viewModel,
                            onExportUpdate = onExportUpdate,
                            isLatest = isLatest,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveDetails(
    state: UpdateListItemUiState.Active,
    downloadId: String,
    viewModel: UpdateListItemViewModel,
    onExportUpdate: (UpdateInfo) -> Unit,
    isLatest: Boolean,
) {
    val context = LocalContext.current

    Row(
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (state.percentageText.isNotEmpty()) {
            val text = buildAnnotatedString {
                val percentIndex = state.percentageText.indexOf('%')
                if (percentIndex != -1) {
                    append(state.percentageText.substring(0, percentIndex))
                    withStyle(SpanStyle(fontSize = 18.sp)) {
                        append(state.percentageText.substring(percentIndex))
                    }
                } else {
                    append(state.percentageText)
                }
            }
            Text(
                text = text,
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.alignByBaseline(),
            )
        }

        Spacer(Modifier.weight(1f))

        Text(
            text = state.progressText.resolve(context),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.alignByBaseline(),
        )
    }

    Spacer(Modifier.height(8.dp))

    // Progress bar
    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    if (state.isProgressIndeterminate) {
        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
    } else {
        LinearWavyProgressIndicator(
            progress = { state.progressValue / 100f },
            modifier = Modifier.fillMaxWidth()
        )
    }

    Spacer(Modifier.height(16.dp))

    // Bottom row: action buttons LEFT, hamburger RIGHT
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.isCancelVisible) {
                OutlinedButton(
                    onClick = {
                        if (state.cancelAction == UpdateListItemUiState.CancelAction.CANCEL_INSTALLATION) {
                            viewModel.onCancelInstallationClicked()
                        } else {
                            viewModel.onCancelDownloadClicked(downloadId)
                        }
                    },
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            }

            PrimaryActionButton(
                action = state.primaryAction,
                enabled = state.isPrimaryActionEnabled,
                downloadId = downloadId,
                viewModel = viewModel,
                isLatest = isLatest,
            )
        }

        Spacer(Modifier.weight(1f))

        OverflowMenu(
            menuState = state.menuState,
            downloadId = downloadId,
            viewModel = viewModel,
            onExportUpdate = onExportUpdate,
        )
    }
}

@Composable
private fun InactiveDetails(
    state: UpdateListItemUiState.Inactive,
    downloadId: String,
    viewModel: UpdateListItemViewModel,
    onExportUpdate: (UpdateInfo) -> Unit,
    isLatest: Boolean,
) {
    Text(
        text = state.buildSize,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(16.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PrimaryActionButton(
            action = state.primaryAction,
            enabled = state.isPrimaryActionEnabled,
            downloadId = downloadId,
            viewModel = viewModel,
            isLatest = isLatest,
        )

        Spacer(Modifier.weight(1f))

        OverflowMenu(
            menuState = state.menuState,
            downloadId = downloadId,
            viewModel = viewModel,
            onExportUpdate = onExportUpdate,
        )
    }
}

@Composable
private fun PrimaryActionButton(
    action: UpdateListItemUiState.PrimaryAction,
    enabled: Boolean,
    downloadId: String,
    viewModel: UpdateListItemViewModel,
    isLatest: Boolean,
) {
    when (action) {
        UpdateListItemUiState.PrimaryAction.DOWNLOAD -> {
            ActionButton(
                labelRes = R.string.action_download,
                onClick = { viewModel.onDownloadClicked(downloadId) },
                enabled = enabled,
                isLatest = isLatest,
            )
        }
        UpdateListItemUiState.PrimaryAction.PAUSE -> {
            OutlinedIconButton(
                onClick = { viewModel.onPause(downloadId) },
                enabled = enabled,
            ) {
                Icon(Icons.Outlined.Pause, contentDescription = null)
            }
        }
        UpdateListItemUiState.PrimaryAction.RESUME -> {
            ActionIconButton(
                icon = Icons.Outlined.PlayArrow,
                onClick = { viewModel.onResumeClicked(downloadId) },
                enabled = enabled,
                isLatest = isLatest,
            )
        }
        UpdateListItemUiState.PrimaryAction.INSTALL -> {
            ActionButton(
                labelRes = R.string.action_install,
                onClick = { viewModel.onInstallClicked(downloadId) },
                enabled = enabled,
                isLatest = isLatest,
            )
        }
        UpdateListItemUiState.PrimaryAction.INFO -> {
            ActionButton(
                labelRes = R.string.action_info,
                onClick = { viewModel.onInfoClicked() },
                enabled = enabled,
                isLatest = isLatest,
            )
        }
        UpdateListItemUiState.PrimaryAction.DELETE -> {
            ActionButton(
                labelRes = R.string.action_delete,
                onClick = { viewModel.onDeleteClicked(downloadId) },
                enabled = enabled,
                isLatest = isLatest,
            )
        }
        UpdateListItemUiState.PrimaryAction.CANCEL_INSTALLATION -> {
            ActionButton(
                labelRes = android.R.string.cancel,
                onClick = { viewModel.onCancelInstallationClicked() },
                enabled = enabled,
                isLatest = isLatest,
            )
        }
        UpdateListItemUiState.PrimaryAction.REBOOT -> {
            ActionButton(
                labelRes = R.string.reboot,
                onClick = { viewModel.onReboot() },
                enabled = enabled,
                isLatest = isLatest,
            )
        }
        UpdateListItemUiState.PrimaryAction.SUSPEND_INSTALLATION -> {
            OutlinedIconButton(
                onClick = { viewModel.onSuspendInstallation() },
                enabled = enabled,
            ) {
                Icon(Icons.Outlined.Pause, contentDescription = null)
            }
        }
        UpdateListItemUiState.PrimaryAction.RESUME_INSTALLATION -> {
            ActionIconButton(
                icon = Icons.Outlined.PlayArrow,
                onClick = { viewModel.onResumeInstallation() },
                enabled = enabled,
                isLatest = isLatest,
            )
        }
    }
}

@Composable
private fun ActionButton(
    labelRes: Int,
    onClick: () -> Unit,
    enabled: Boolean,
    isLatest: Boolean,
) {
    if (isLatest) {
        Button(
            onClick = onClick,
            enabled = enabled,
        ) {
            Text(stringResource(labelRes))
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
        ) {
            Text(stringResource(labelRes))
        }
    }
}

@Composable
private fun ActionIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean,
    isLatest: Boolean,
) {
    if (isLatest) {
        FilledIconButton(
            onClick = onClick,
            enabled = enabled,
        ) {
            Icon(icon, contentDescription = null)
        }
    } else {
        FilledTonalIconButton(
            onClick = onClick,
            enabled = enabled,
        ) {
            Icon(icon, contentDescription = null)
        }
    }
}

@Composable
private fun OverflowMenu(
    menuState: UpdateListItemUiState.MenuState,
    downloadId: String,
    viewModel: UpdateListItemViewModel,
    onExportUpdate: (UpdateInfo) -> Unit,
) {
    if (!menuState.showDelete && !menuState.showCopyUrl && !menuState.showExport) return

    // Box anchors the MoreOptionsAction dropdown to the icon button rather than the parent row.
    Box {
        MoreOptionsAction {
            if (menuState.showDelete) {
                MenuItem(stringResource(R.string.menu_delete_update)) {
                    viewModel.onDeleteClicked(downloadId)
                }
            }
            if (menuState.showCopyUrl) {
                MenuItem(stringResource(R.string.label_download_url)) {
                    viewModel.copyDownloadUrl(downloadId)
                }
            }
            if (menuState.showExport) {
                MenuItem(stringResource(R.string.menu_export_update)) {
                    viewModel.getUpdateForExport(downloadId)?.let(onExportUpdate)
                }
            }
        }
    }
}
