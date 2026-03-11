/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.preferences

import androidx.compose.runtime.Composable
import androidx.compose.runtime.IntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.settingslib.spa.widget.preference.ListPreference
import com.android.settingslib.spa.widget.preference.ListPreferenceModel
import com.android.settingslib.spa.widget.preference.ListPreferenceOption
import com.android.settingslib.spa.widget.preference.SwitchPreference
import com.android.settingslib.spa.widget.preference.SwitchPreferenceModel
import com.android.settingslib.spa.widget.scaffold.RegularScaffold
import com.android.settingslib.spa.widget.ui.Category
import org.lineageos.updater.misc.DeviceInfoUtils
import org.lineageos.updater.misc.Utils
import org.lineageos.updater.R
import org.lineageos.updater.data.CheckInterval

@Composable
fun PreferencesScreen(viewModel: PreferencesViewModel) {
    val isABDevice = remember { DeviceInfoUtils.isABDevice }
    val showUpdateRecovery = remember { Utils.isRecoveryUpdateExecPresent() }
    RegularScaffold(title = stringResource(R.string.display_name)) {
        PreferencesContent(viewModel, isABDevice, showUpdateRecovery)
    }
}

@Composable
private fun PreferencesContent(
    viewModel: PreferencesViewModel,
    isABDevice: Boolean,
    showUpdateRecovery: Boolean,
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Category(title = stringResource(R.string.pref_category_background_sync)) {
        SwitchPreference(
            object : SwitchPreferenceModel {
                override val title = context.getString(R.string.menu_auto_updates_check)
                override val summary = { context.getString(R.string.menu_auto_updates_check_summary) }
                override val checked = { uiState.periodicCheckEnabled }
                override val onCheckedChange: (Boolean) -> Unit = { newValue ->
                    viewModel.setPeriodicCheckEnabled(newValue)
                }
            }
        )

        ListPreference(
            object : ListPreferenceModel {
                override val title = context.getString(R.string.menu_auto_updates_check_interval)
                override val enabled = { uiState.periodicCheckEnabled }
                override val options = listOf(
                    ListPreferenceOption(
                        id = CheckInterval.DAILY.id,
                        text = context.getString(R.string.time_unit_day),
                    ),
                    ListPreferenceOption(
                        id = CheckInterval.WEEKLY.id,
                        text = context.getString(R.string.time_unit_week),
                    ),
                    ListPreferenceOption(
                        id = CheckInterval.MONTHLY.id,
                        text = context.getString(R.string.time_unit_month),
                    ),
                )
                override val selectedId = object : IntState {
                    override val intValue: Int get() = uiState.checkInterval
                }
                override val onIdSelected: (Int) -> Unit = { id -> viewModel.setCheckInterval(id) }
            }
        )
    }

    Category(title = stringResource(R.string.pref_category_download_install)) {
        SwitchPreference(
            object : SwitchPreferenceModel {
                override val title = context.getString(R.string.menu_auto_delete_updates)
                override val summary = { context.getString(R.string.menu_auto_delete_updates_summary) }
                override val checked = { uiState.autoDelete }
                override val onCheckedChange: (Boolean) -> Unit = { newValue ->
                    viewModel.setAutoDelete(newValue)
                }
            }
        )

        SwitchPreference(
            object : SwitchPreferenceModel {
                override val title = context.getString(R.string.menu_metered_network_warning)
                override val summary = { context.getString(R.string.menu_metered_network_warning_summary) }
                override val checked = { uiState.meteredWarning }
                override val onCheckedChange: (Boolean) -> Unit = { newValue ->
                    viewModel.setMeteredWarning(newValue)
                }
            }
        )

        if (isABDevice) {
            SwitchPreference(
                object : SwitchPreferenceModel {
                    override val title = context.getString(R.string.menu_ab_perf_mode)
                    override val summary = { context.getString(R.string.menu_ab_perf_mode_summary) }
                    override val checked = { uiState.abPerfMode }
                    override val onCheckedChange: (Boolean) -> Unit = { newValue ->
                        viewModel.setAbPerfMode(newValue)
                    }
                }
            )
        }

        if (showUpdateRecovery) {
            SwitchPreference(
                object : SwitchPreferenceModel {
                    override val title = context.getString(R.string.menu_update_recovery)
                    override val summary = { context.getString(R.string.menu_update_recovery_summary) }
                    override val checked = { uiState.recoveryUpdate }
                    override val onCheckedChange: (Boolean) -> Unit = { newValue ->
                        viewModel.setRecoveryUpdate(newValue)
                    }
                }
            )
        }
    }
}
