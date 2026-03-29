/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.updatescheck

import android.content.Context
import android.content.res.Configuration
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.android.settingslib.spa.debug.UiModePreviews
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsTheme
import org.lineageos.updater.R
import org.lineageos.updater.util.StringUtil
import java.time.format.FormatStyle
import java.util.Date

private fun formatLastChecked(
    context: Context,
    timestampMillis: Long,
): String {
    val time = DateFormat.getTimeFormat(context).format(Date(timestampMillis))
    if (DateUtils.isToday(timestampMillis)) {
        return time
    }
    val date =
        StringUtil.getDateLocalized(context, FormatStyle.MEDIUM, timestampMillis / 1000)
    return "$date, $time"
}

@Composable
fun UpdatesCheck(
    isRefreshing: Boolean,
    isNetworkAvailable: Boolean,
    lastCheckTimestamp: Long,
    onCheckClick: () -> Unit,
) {
    val context = LocalContext.current
    val isPreview = LocalInspectionMode.current

    Column {
        OutlinedButton(
            onClick = onCheckClick,
            enabled = !isRefreshing && isNetworkAvailable,
            modifier = Modifier
                .fillMaxWidth()
                .padding(SettingsDimension.itemPadding),
        ) {
            Text(text = stringResource(R.string.check_for_updates))
        }

        val lastChecked = remember(lastCheckTimestamp) {
            if (isPreview) "5:30 PM" else formatLastChecked(context, lastCheckTimestamp)
        }
        if (lastCheckTimestamp > 0 || isPreview) {
            Text(
                text = stringResource(R.string.header_last_updates_check, lastChecked),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(SettingsDimension.itemPadding)
                    .padding(end = SettingsDimension.itemPaddingEnd),
                textAlign = TextAlign.End,
            )
        }
    }
}

@UiModePreviews
@Composable
private fun UpdatesCheckPreview() {
    SettingsTheme {
        UpdatesCheck(
            isRefreshing = false,
            isNetworkAvailable = true,
            lastCheckTimestamp = System.currentTimeMillis(),
            onCheckClick = {},
        )
    }
}
