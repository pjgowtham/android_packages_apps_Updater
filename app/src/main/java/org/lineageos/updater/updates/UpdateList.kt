/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.updates

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.android.settingslib.spa.widget.preference.ZeroStatePreference
import com.android.settingslib.spa.widget.ui.Category
import org.lineageos.updater.R
import org.lineageos.updater.ui.CollapseBar
import org.lineageos.updater.updates.action.UpdateAction
import org.lineageos.updater.updates.state.UpdateItemState

@Composable
fun UpdateList(
    items: List<UpdateItemState>,
    onAction: (UpdateAction, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) {
        ZeroStatePreference(
            icon = Icons.Outlined.CheckCircle,
            text = stringResource(R.string.snack_no_updates_found),
            description = stringResource(R.string.list_no_updates),
        )
        return
    }

    var expanded by rememberSaveable { mutableStateOf(false) }

    // Latest update (first) + any active items are visible in collapsed state
    val collapsedVisibleItems = (listOf(items.first()) + items.filter { it.progress != null }).distinct()
    val visibleItems = if (expanded) items else collapsedVisibleItems
    val hiddenCount = items.size - collapsedVisibleItems.size

    Column(modifier = modifier) {
        Category {
            visibleItems.forEach { item ->
                UpdateItem(
                    state = item,
                    expanded = expanded || items.size == 1,
                    onAction = { action -> onAction(action, item.downloadId) },
                )
            }
        }

        if (hiddenCount > 0) {
            CollapseBar(
                expanded = expanded,
                hiddenItemCount = hiddenCount,
                onExpandedChange = { expanded = it },
            )
        }
    }
}
