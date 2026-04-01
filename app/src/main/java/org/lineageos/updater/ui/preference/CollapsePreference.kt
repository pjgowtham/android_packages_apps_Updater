/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.ui.preference

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.android.settingslib.spa.debug.UiModePreviews
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsSpace
import com.android.settingslib.spa.framework.theme.SettingsTheme

/**
 * Based on SPA TopIntroPreference collapse UI.
 */
data class CollapsePreferenceModel(
    val expandText: String,
    val collapseText: String,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CollapsePreference(
    model: CollapsePreferenceModel,
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
            imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier
                .size(SettingsDimension.itemIconSize)
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = CircleShape,
                ),
        )
        Spacer(Modifier.width(SettingsSpace.extraSmall4))
        Text(
            text = if (expanded) model.collapseText else model.expandText,
            style = MaterialTheme.typography.bodyLargeEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@UiModePreviews
@Composable
private fun CollapsePreferenceCollapsedPreview() {
    SettingsTheme {
        CollapsePreference(
            model = CollapsePreferenceModel(
                expandText = "See 2 more updates",
                collapseText = "See less",
            ),
            expanded = false,
            onExpandedChange = {},
        )
    }
}

@UiModePreviews
@Composable
private fun CollapsePreferenceExpandedPreview() {
    SettingsTheme {
        CollapsePreference(
            model = CollapsePreferenceModel(
                expandText = "See 2 more updates",
                collapseText = "See less",
            ),
            expanded = true,
            onExpandedChange = {},
        )
    }
}
