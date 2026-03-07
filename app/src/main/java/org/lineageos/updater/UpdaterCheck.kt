/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsTheme
import org.lineageos.updater.misc.StringGenerator
import java.text.DateFormat as JavaDateFormat
import java.util.Date

/**
 * Formats the last-checked timestamp for display.
 *
 * - Always includes the localized time, respecting user 12/24-hour preference.
 * - Includes the date only when the check was NOT today.
 *
 * Examples (en-US):
 *   Same day  → "5:30 PM"
 *   Other day → "Mar 4, 2026, 5:30 PM"
 */
private fun formatLastChecked(
    context: android.content.Context,
    timestampMillis: Long,
): String {
    val time = DateFormat.getTimeFormat(context).format(Date(timestampMillis))
    val timestampSeconds = timestampMillis / 1000

    return if (DateUtils.isToday(timestampMillis)) {
        time
    } else {
        val date =
            StringGenerator.getDateLocalized(context, JavaDateFormat.MEDIUM, timestampSeconds)
        "$date, $time"
    }
}

/**
 * "Check for Updates" button with a last-checked timestamp, placed below the
 * banner. The button is disabled while a check is in progress.
 *
 * @param isRefreshing  Whether an update check is currently in progress.
 * @param lastCheckTimestamp  Epoch millis of the last successful check, or
 *   a value ≤ 0 if no check has been performed yet.
 * @param onCheckClick  Callback invoked when the user taps the button.
 */
@Composable
fun UpdaterCheck(
    isRefreshing: Boolean,
    lastCheckTimestamp: Long,
    onCheckClick: () -> Unit,
) {
    val context = LocalContext.current
    val isPreview = LocalInspectionMode.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = SettingsDimension.itemPaddingEnd,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SettingsDimension.itemPaddingAround),
    ) {
        FilledTonalButton(
            onClick = onCheckClick,
            enabled = !isRefreshing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.check_for_updates))
        }

        if (lastCheckTimestamp > 0 || isPreview) {
            val formattedTime = if (isPreview) {
                "5:30 PM"
            } else {
                formatLastChecked(context, lastCheckTimestamp)
            }
            Text(
                text = stringResource(R.string.last_checked_at, formattedTime),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Preview
@Composable
private fun UpdaterCheckPreview() {
    SettingsTheme {
        UpdaterCheck(
            isRefreshing = false,
            lastCheckTimestamp = System.currentTimeMillis(),
            onCheckClick = {},
        )
    }
}

@Preview
@Composable
private fun UpdaterCheckRefreshingPreview() {
    SettingsTheme {
        UpdaterCheck(
            isRefreshing = true,
            lastCheckTimestamp = System.currentTimeMillis(),
            onCheckClick = {},
        )
    }
}
