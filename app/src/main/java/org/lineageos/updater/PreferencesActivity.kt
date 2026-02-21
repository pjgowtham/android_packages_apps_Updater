/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.settingslib.spa.framework.compose.LocalNavController
import com.android.settingslib.spa.framework.compose.NavControllerWrapper
import com.android.settingslib.spa.framework.theme.SettingsTheme
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
import org.lineageos.updater.repository.PreferencesRepository


class PreferencesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SettingsTheme {
                val backDispatcher =
                    LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                val navController = remember {
                    object : NavControllerWrapper {
                        override fun navigate(route: String, popUpCurrent: Boolean) {}
                        override fun navigateBack() {
                            backDispatcher?.onBackPressed()
                        }
                    }
                }
                CompositionLocalProvider(LocalNavController provides navController) {
                    PreferencesScreen()
                }
            }
        }
    }
}

@Composable
private fun PreferencesScreen() {
    val context = LocalContext.current
    val repository = remember { PreferencesRepository(context) }
    val isABDevice = remember { DeviceInfoUtils.isABDevice }
    val showRecoveryUpdate = remember { Utils.isRecoveryUpdateExecPresent() }
    val state by repository.preferencesData.collectAsStateWithLifecycle(
        initialValue = PreferencesData(
            periodicCheckEnabled = true,
            periodicCheckInterval = CheckInterval.WEEKLY,
            autoDeleteUpdates = false,
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
                override val onCheckedChange = repository::setPeriodicCheckEnabled
            })

            CheckIntervalPreference(
                state = state,
                repository = repository,
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
                    override val onCheckedChange = repository::setAutoDeleteUpdates
                })
            }

            val meteredTitle = stringResource(R.string.menu_metered_network_warning)
            val meteredSummary = stringResource(R.string.menu_metered_network_warning_summary)
            SwitchPreference(object : SwitchPreferenceModel {
                override val title = meteredTitle
                override val summary = { meteredSummary }
                override val checked = { state.meteredNetworkWarning }
                override val onCheckedChange = repository::setMeteredNetworkWarning
            })

            if (isABDevice) {
                val abPerfTitle = stringResource(R.string.menu_ab_perf_mode)
                val abPerfSummary = stringResource(R.string.menu_ab_perf_mode_summary)
                SwitchPreference(object : SwitchPreferenceModel {
                    override val title = abPerfTitle
                    override val summary = { abPerfSummary }
                    override val checked = { state.abPerfMode }
                    override val onCheckedChange = repository::setAbPerfMode
                })

                val streamingTitle = stringResource(R.string.menu_ab_streaming_mode)
                val streamingSummary = stringResource(R.string.menu_ab_streaming_mode_summary)
                SwitchPreference(object : SwitchPreferenceModel {
                    override val title = streamingTitle
                    override val summary = { streamingSummary }
                    override val checked = { state.abStreamingMode }
                    override val onCheckedChange = repository::setAbStreamingMode
                })
            }

            if (showRecoveryUpdate) {
                val recoveryTitle = stringResource(R.string.menu_update_recovery)
                val recoverySummary = stringResource(R.string.menu_update_recovery_summary)
                SwitchPreference(object : SwitchPreferenceModel {
                    override val title = recoveryTitle
                    override val summary = { recoverySummary }
                    override val checked = { state.updateRecovery }
                    override val onCheckedChange = repository::setUpdateRecovery
                })
            }
        }
    }
}


/** Interval selector, greyed out when periodic check is disabled. */
@Composable
private fun CheckIntervalPreference(
    state: PreferencesData,
    repository: PreferencesRepository,
    enabled: Boolean,
) {
    val entries = stringArrayResource(R.array.check_interval_entries)

    val options = remember(entries) {
        entries.mapIndexed { i, text -> ListPreferenceOption(id = i, text = text) }
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
            repository.setPeriodicCheckInterval(CheckInterval.entries[index])
        }
    })
}
