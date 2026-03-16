/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.preferences

import androidx.compose.runtime.Composable
import androidx.compose.runtime.IntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.settingslib.spa.widget.preference.ListPreference
import com.android.settingslib.spa.widget.preference.ListPreferenceModel
import com.android.settingslib.spa.widget.preference.ListPreferenceOption
import com.android.settingslib.spa.widget.preference.SwitchPreference
import com.android.settingslib.spa.widget.preference.SwitchPreferenceModel
import com.android.settingslib.spa.widget.scaffold.RegularScaffold
import com.android.settingslib.spa.widget.ui.Category
import org.lineageos.updater.R
import org.lineageos.updater.data.CheckInterval
import org.lineageos.updater.misc.DeviceInfoUtils
import org.lineageos.updater.misc.Utils

@Composable
fun PreferencesScreen(viewModel: PreferencesViewModel) {
    val isABDevice = remember { DeviceInfoUtils.isABDevice }
    val showRecoveryUpdate = remember { Utils.isRecoveryUpdateExecPresent() }
    RegularScaffold(title = stringResource(R.string.display_name)) {
        PreferencesContent(viewModel, isABDevice, showRecoveryUpdate)
    }
}

@Composable
private fun PreferencesContent(
    viewModel: PreferencesViewModel,
    isABDevice: Boolean,
    showRecoveryUpdate: Boolean,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val autoUpdatesCheckSummary = stringResource(R.string.menu_auto_updates_check_summary)
    val autoDeleteUpdatesSummary = stringResource(R.string.menu_auto_delete_updates_summary)
    val meteredNetworkWarningSummary = stringResource(R.string.menu_metered_network_warning_summary)
    val abPerfModeSummary = stringResource(R.string.menu_ab_perf_mode_summary)
    val updateRecoverySummary = stringResource(R.string.menu_update_recovery_summary)

    Category(title = stringResource(R.string.pref_category_background_sync)) {
        SwitchPreference(object : SwitchPreferenceModel {
            override val title = stringResource(R.string.menu_auto_updates_check)
            override val summary = { autoUpdatesCheckSummary }
            override val checked = { uiState.periodicCheckEnabled }
            override val onCheckedChange: (Boolean) -> Unit = viewModel::setPeriodicCheckEnabled
        })

        ListPreference(object : ListPreferenceModel {
            override val title = stringResource(R.string.menu_auto_updates_check_interval)
            override val enabled = { uiState.periodicCheckEnabled }
            override val options = listOf(
                ListPreferenceOption(
                    id = CheckInterval.DAILY.id,
                    text = stringResource(R.string.time_unit_day),
                ),
                ListPreferenceOption(
                    id = CheckInterval.WEEKLY.id,
                    text = stringResource(R.string.time_unit_week),
                ),
                ListPreferenceOption(
                    id = CheckInterval.MONTHLY.id,
                    text = stringResource(R.string.time_unit_month),
                ),
            )
            override val selectedId = object : IntState {
                override val intValue get() = uiState.checkIntervalId
            }
            override val onIdSelected: (Int) -> Unit = viewModel::setCheckInterval
        })
    }

    Category(title = stringResource(R.string.pref_category_download_install)) {
        SwitchPreference(object : SwitchPreferenceModel {
            override val title = stringResource(R.string.menu_auto_delete_updates)
            override val summary = { autoDeleteUpdatesSummary }
            override val checked = { uiState.autoDelete }
            override val onCheckedChange: (Boolean) -> Unit = viewModel::setAutoDelete
        })

        SwitchPreference(object : SwitchPreferenceModel {
            override val title = stringResource(R.string.menu_metered_network_warning)
            override val summary = { meteredNetworkWarningSummary }
            override val checked = { uiState.meteredNetworkWarning }
            override val onCheckedChange: (Boolean) -> Unit = viewModel::setMeteredNetworkWarning
        })

        if (isABDevice) {
            SwitchPreference(object : SwitchPreferenceModel {
                override val title = stringResource(R.string.menu_ab_perf_mode)
                override val summary = { abPerfModeSummary }
                override val checked = { uiState.abPerfMode }
                override val onCheckedChange: (Boolean) -> Unit = viewModel::setAbPerfMode
            })
        }

        if (showRecoveryUpdate) {
            SwitchPreference(object : SwitchPreferenceModel {
                override val title = stringResource(R.string.menu_update_recovery)
                override val summary = { updateRecoverySummary }
                override val checked = { uiState.recoveryUpdateEnabled }
                override val onCheckedChange: (Boolean) -> Unit = viewModel::setRecoveryUpdate
            })
        }
    }
}
