/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.updates

import android.text.format.Formatter
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.lerp
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.dp
import com.android.settingslib.spa.debug.UiModePreviews
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsShape
import com.android.settingslib.spa.framework.theme.SettingsSize
import com.android.settingslib.spa.framework.theme.SettingsSpace
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.widget.preference.ProgressBarPreferenceModel
import com.android.settingslib.spa.widget.preference.ProgressBarWithDataPreference
import com.android.settingslib.spa.widget.scaffold.MoreOptionsScope
import com.android.settingslib.spa.widget.ui.LinearLoadingBar
import com.android.settingslib.spa.widget.ui.SettingsBody
import com.android.settingslib.spa.widget.ui.SettingsIcon
import com.android.settingslib.spa.widget.ui.SettingsTitle
import org.lineageos.updater.R
import org.lineageos.updater.data.Update
import org.lineageos.updater.data.UpdateStatus
import org.lineageos.updater.updates.button.ActionBar
import org.lineageos.updater.updates.button.ActionBarButton
import org.lineageos.updater.util.currentLocale
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val ExpandAnimationMs = 350

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
    var expanded by rememberSaveable(update.downloadId) { mutableStateOf(false) }
    val locale = context.currentLocale
    val buildVersionText = stringResource(R.string.list_build_version, update.version)
    val inProgressStatusText = collapsedInProgressStatusText(update).ifBlank { null }
    val summaryText = if (inProgressStatusText == null) {
        buildVersionText
    } else {
        "$buildVersionText \u2022 $inProgressStatusText"
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceBright,
                shape = SettingsShape.CornerExtraSmall2,
            )
            .animateContentSize(),
    ) {
        UpdateListItemHeader(
            title = remember(update.timestamp, locale) {
                formatShortDate(update.timestamp, locale)
            },
            summary = summaryText,
            icon = collapsedIcon(update),
            expanded = expanded,
            onClick = { expanded = !expanded },
        )

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
                                Column(
                                    modifier = Modifier.padding(
                                        start = SettingsDimension.itemPaddingStart,
                                        end = SettingsDimension.itemPaddingEnd,
                                        top = SettingsDimension.itemPaddingVertical,
                                        bottom = SettingsDimension.itemPaddingVertical,
                                    ),
                                ) {
                                    SettingsTitle(progress.text)
                                    LinearLoadingBar(isLoading = true)
                                }
                            }
                        }
                    }

                    is UpdateListItemState.Idle -> {
                        Box(
                            modifier = Modifier.padding(
                                start = SettingsDimension.itemPaddingStart,
                                end = SettingsDimension.itemPaddingEnd,
                            ),
                        ) {
                            SettingsBody(Formatter.formatShortFileSize(context, update.fileSize))
                        }
                    }
                }

                val primaryAction = state.primaryAction
                val secondaryAction = (state as? UpdateListItemState.Active)?.secondaryAction
                if (primaryAction != null || secondaryAction != null || state.menuActions.isNotEmpty()) {
                    val buttons = buildList {
                        if (secondaryAction != null) {
                            add(
                                ActionBarButton.Outlined(
                                    text = stringResource(android.R.string.cancel),
                                    enabled = secondaryAction.enabled,
                                    onClick = { onSecondaryAction(secondaryAction) },
                                )
                            )
                        }
                        if (primaryAction != null) {
                            add(primaryActionButton(primaryAction) { onPrimaryAction(primaryAction) })
                        }
                    }
                    val menuContent: (@Composable MoreOptionsScope.() -> Unit)? =
                        if (state.menuActions.isNotEmpty()) {
                            {
                                state.menuActions.forEach { action ->
                                    MenuItem(
                                        text = menuActionLabel(action),
                                        onClick = { onMenuAction(action) },
                                    )
                                }
                            }
                        } else {
                            null
                        }
                    ActionBar(
                        buttons = buttons,
                        menuContent = menuContent,
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdateListItemHeader(
    title: String,
    summary: String,
    icon: ImageVector,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val transition = updateTransition(targetState = expanded, label = "itemExpandCollapse")

    val iconWidth by transition.animateDp(
        transitionSpec = { tween(ExpandAnimationMs) },
        label = "iconWidth",
    ) { isExpanded ->
        if (isExpanded) 0.dp else SettingsSize.medium3 + SettingsSpace.extraSmall6
    }

    val iconAlpha by transition.animateFloat(
        transitionSpec = {
            if (false isTransitioningTo true) {
                tween(durationMillis = ExpandAnimationMs / 2)
            } else {
                tween(
                    durationMillis = ExpandAnimationMs / 2,
                    delayMillis = ExpandAnimationMs / 2,
                )
            }
        },
        label = "iconAlpha",
    ) { isExpanded ->
        if (isExpanded) 0f else 1f
    }

    val textFraction by transition.animateFloat(
        transitionSpec = { tween(ExpandAnimationMs) },
        label = "textFraction",
    ) { isExpanded ->
        if (isExpanded) 1f else 0f
    }

    val titleStyle = lerp(
        start = MaterialTheme.typography.titleMedium.copy(textMotion = TextMotion.Animated),
        stop = MaterialTheme.typography.titleLarge.copy(textMotion = TextMotion.Animated),
        fraction = textFraction,
    )
    val summaryStyle = lerp(
        start = MaterialTheme.typography.bodyMedium.copy(textMotion = TextMotion.Animated),
        stop = MaterialTheme.typography.bodyLarge.copy(textMotion = TextMotion.Animated),
        fraction = textFraction,
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SettingsDimension.preferenceMinHeight)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .padding(end = SettingsDimension.itemPaddingEnd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(SettingsDimension.itemPaddingStart))

        Box(
            modifier = Modifier
                .width(iconWidth)
                .alpha(iconAlpha),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier.size(SettingsSize.medium3),
                contentAlignment = Alignment.Center,
            ) {
                SettingsIcon(icon)
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = SettingsDimension.itemPaddingVertical),
        ) {
            Text(
                text = title,
                style = titleStyle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = summary,
                style = summaryStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

private fun collapsedIcon(update: Update): ImageVector =
    if (update.isAvailableOnline && update.status != UpdateStatus.VERIFIED) {
        Icons.Outlined.Download
    } else {
        Icons.Outlined.Storage
    }

@Composable
private fun collapsedInProgressStatusText(update: Update): String {
    if (!update.status.isInProgress) return ""
    return when (update.status) {
        UpdateStatus.DOWNLOADING,
        UpdateStatus.STARTING -> stringResource(R.string.downloading_notification)

        UpdateStatus.PAUSED -> stringResource(R.string.download_paused_notification)
        UpdateStatus.PAUSED_ERROR -> stringResource(R.string.download_paused_error_notification)
        UpdateStatus.VERIFYING -> stringResource(R.string.list_verifying_update)
        UpdateStatus.INSTALLING -> stringResource(R.string.installing_update)
        UpdateStatus.INSTALLATION_SUSPENDED ->
            stringResource(R.string.installation_suspended_notification)

        else -> ""
    }
}

private fun formatShortDate(timestamp: Long, locale: Locale): String {
    val date = Instant.ofEpochSecond(timestamp)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
    val pattern = if (date.year == LocalDate.now().year) "MMM d" else "MMM d, yyyy"
    return DateTimeFormatter.ofPattern(pattern, locale).format(date)
}

@Composable
private fun primaryActionButton(
    action: UpdateListPrimaryAction,
    onClick: () -> Unit,
): ActionBarButton = when (action) {
    is UpdateListPrimaryAction.Start -> ActionBarButton.Tonal(
        text = when (action.operation) {
            UpdateListOperation.Download -> stringResource(R.string.action_download)
            UpdateListOperation.Install -> stringResource(R.string.action_install)
        },
        enabled = action.enabled,
        onClick = onClick,
    )

    is UpdateListPrimaryAction.Pause -> ActionBarButton.MediaIcon(
        imageVector = Icons.Outlined.Pause,
        contentDescription = stringResource(R.string.action_pause),
        enabled = action.enabled,
        onClick = onClick,
    )

    is UpdateListPrimaryAction.Resume -> ActionBarButton.MediaIcon(
        imageVector = Icons.Outlined.PlayArrow,
        contentDescription = stringResource(R.string.action_resume),
        enabled = action.enabled,
        onClick = onClick,
    )

    is UpdateListPrimaryAction.Info -> ActionBarButton.Tonal(
        text = stringResource(R.string.action_info),
        enabled = action.enabled,
        onClick = onClick,
    )

    UpdateListPrimaryAction.Reboot -> ActionBarButton.Tonal(
        text = stringResource(R.string.reboot),
        enabled = action.enabled,
        onClick = onClick,
    )
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

@UiModePreviews
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

@UiModePreviews
@Composable
private fun UpdateListItemDownloadingPreview() {
    SettingsTheme {
        UpdateListItem(
            update = Update(version = "23.2", timestamp = 0L, fileSize = 1_100_000_000L),
            state = UpdateListItemState.Active(
                primaryAction = UpdateListPrimaryAction.Pause(UpdateListOperation.Download),
                secondaryAction = UpdateListSecondaryAction.Cancel(UpdateListOperation.Download),
                progress = UpdateListProgressState.Percent(
                    text = "162 MB of 1.1 GB - 3 minutes left",
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

@UiModePreviews
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
                progress = UpdateListProgressState.Indeterminate(text = "Verifying update..."),
            ),
            onPrimaryAction = {},
            onSecondaryAction = {},
            onMenuAction = {},
        )
    }
}
