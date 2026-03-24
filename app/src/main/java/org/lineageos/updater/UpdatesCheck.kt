/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.widget.ui.SettingsBody
import org.lineageos.updater.misc.StringGenerator
import java.text.DateFormat as JavaDateFormat
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
        StringGenerator.getDateLocalized(context, JavaDateFormat.MEDIUM, timestampMillis / 1000)
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SettingsDimension.itemPaddingEnd),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(SettingsDimension.itemPaddingAround),
    ) {
        OutlinedButton(
            onClick = onCheckClick,
            enabled = !isRefreshing && isNetworkAvailable,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.check_for_updates))
        }

        val lastChecked = remember(lastCheckTimestamp) {
            if (isPreview) "5:30 PM" else formatLastChecked(context, lastCheckTimestamp)
        }
        if (lastCheckTimestamp > 0 || isPreview) {
            SettingsBody(stringResource(R.string.header_last_updates_check, lastChecked))
        }
    }
}

@Preview
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
