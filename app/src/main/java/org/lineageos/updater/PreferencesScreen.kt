/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.settingslib.spa.widget.preference.ListPreference
import com.android.settingslib.spa.widget.preference.ListPreferenceModel
import com.android.settingslib.spa.widget.preference.ListPreferenceOption
import com.android.settingslib.spa.widget.preference.SwitchPreference
import com.android.settingslib.spa.widget.preference.SwitchPreferenceModel
import com.android.settingslib.spa.widget.scaffold.RegularScaffold
import com.android.settingslib.spa.widget.ui.Category
import org.lineageos.updater.misc.Constants.CheckInterval
import org.lineageos.updater.misc.DeviceInfoUtils
import org.lineageos.updater.misc.Utils
import org.lineageos.updater.repository.PreferencesData
import org.lineageos.updater.viewmodel.PreferencesViewModel

@Composable
internal fun PreferencesScreen(viewModel: PreferencesViewModel = viewModel()) {
    val isABDevice = remember { DeviceInfoUtils.isABDevice }
    val showRecoveryUpdate = remember { Utils.isRecoveryUpdateExecPresent() }
    val state by viewModel.preferencesData.collectAsStateWithLifecycle(
        initialValue = PreferencesData(
            periodicCheckEnabled = true,
            periodicCheckInterval = CheckInterval.WEEKLY,
            autoDeleteUpdates = true,
            meteredNetworkWarning = true,
            abPerfMode = false,
            abStreamingMode = false,
            updateRecovery = false,
        )
    )

    RegularScaffold(title = stringResource(R.string.menu_preferences)) {
        Category(title = stringResource(R.string.pref_category_background_sync)) {
            val autoUpdatesCheckTitle = stringResource(R.string.menu_auto_updates_check)
            val autoUpdatesCheckSummary = stringResource(R.string.menu_auto_updates_check_summary)
            SwitchPreference(object : SwitchPreferenceModel {
                override val title = autoUpdatesCheckTitle
                override val summary = { autoUpdatesCheckSummary }
                override val checked = { state.periodicCheckEnabled }
                override val onCheckedChange = viewModel::setPeriodicCheckEnabled
            })

            CheckIntervalPreference(
                state = state,
                onIntervalSelected = viewModel::setPeriodicCheckInterval,
                enabled = state.periodicCheckEnabled,
            )
        }

        Category(title = stringResource(R.string.pref_category_download_install)) {
            if (!isABDevice) {
                val autoDeleteTitle = stringResource(R.string.menu_auto_delete_updates)
                val autoDeleteSummary = stringResource(R.string.menu_auto_delete_updates_summary)
                SwitchPreference(object : SwitchPreferenceModel {
                    override val title = autoDeleteTitle
                    override val summary = { autoDeleteSummary }
                    override val checked = { state.autoDeleteUpdates }
                    override val onCheckedChange = viewModel::setAutoDeleteUpdates
                })
            }

            val meteredTitle = stringResource(R.string.menu_metered_network_warning)
            val meteredSummary = stringResource(R.string.menu_metered_network_warning_summary)
            SwitchPreference(object : SwitchPreferenceModel {
                override val title = meteredTitle
                override val summary = { meteredSummary }
                override val checked = { state.meteredNetworkWarning }
                override val onCheckedChange = viewModel::setMeteredNetworkWarning
            })

            if (isABDevice) {
                val abPerfTitle = stringResource(R.string.menu_ab_perf_mode)
                val abPerfSummary = stringResource(R.string.menu_ab_perf_mode_summary)
                SwitchPreference(object : SwitchPreferenceModel {
                    override val title = abPerfTitle
                    override val summary = { abPerfSummary }
                    override val checked = { state.abPerfMode }
                    override val onCheckedChange = viewModel::setAbPerfMode
                })

                val streamingTitle = stringResource(R.string.menu_ab_streaming_mode)
                val streamingSummary = stringResource(R.string.menu_ab_streaming_mode_summary)
                SwitchPreference(object : SwitchPreferenceModel {
                    override val title = streamingTitle
                    override val summary = { streamingSummary }
                    override val checked = { state.abStreamingMode }
                    override val onCheckedChange = viewModel::setAbStreamingMode
                })
            }

            if (showRecoveryUpdate) {
                val recoveryTitle = stringResource(R.string.menu_update_recovery)
                val recoverySummary = stringResource(R.string.menu_update_recovery_summary)
                SwitchPreference(object : SwitchPreferenceModel {
                    override val title = recoveryTitle
                    override val summary = { recoverySummary }
                    override val checked = { state.updateRecovery }
                    override val onCheckedChange = viewModel::setUpdateRecovery
                })
            }
        }
    }
}

/** Interval selector, greyed out when periodic check is disabled. */
@Composable
private fun CheckIntervalPreference(
    state: PreferencesData,
    onIntervalSelected: (CheckInterval) -> Unit,
    enabled: Boolean,
) {
    val options = CheckInterval.entries.mapIndexed { i, interval ->
        ListPreferenceOption(id = i, text = stringResource(interval.labelRes))
    }

    val checkIntervalIndex = CheckInterval.entries.indexOf(state.periodicCheckInterval)
    val selectedId = remember(checkIntervalIndex) {
        mutableIntStateOf(checkIntervalIndex)
    }

    ListPreference(object : ListPreferenceModel {
        override val title = stringResource(R.string.menu_auto_updates_check_interval)
        override val options = options
        override val selectedId = selectedId
        override val enabled = { enabled }
        override val onIdSelected: (Int) -> Unit = { index ->
            onIntervalSelected(CheckInterval.entries[index])
        }
    })
}
