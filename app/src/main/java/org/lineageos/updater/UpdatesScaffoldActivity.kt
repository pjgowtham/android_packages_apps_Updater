/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.settingslib.spa.framework.compose.LocalNavController
import com.android.settingslib.spa.framework.compose.NavControllerWrapper
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.scaffold.SettingsScaffold
import com.android.settingslib.spa.widget.ui.Category
import com.android.settingslib.spa.widget.ui.LinearLoadingBar
import org.lineageos.updater.controller.UpdaterController
import org.lineageos.updater.data.UserPreferencesRepository
import org.lineageos.updater.data.Update
import org.lineageos.updater.deviceinfo.DeviceInfoBanner
import org.lineageos.updater.data.UpdateStatus
import org.lineageos.updater.preferences.PreferencesActivity
import org.lineageos.updater.updates.UpdateList
import org.lineageos.updater.updatescheck.UpdatesCheck
import org.lineageos.updater.util.BatteryMonitor
import org.lineageos.updater.util.NetworkMonitor

abstract class UpdatesScaffoldActivity : ComponentActivity() {

    private val viewModel: UpdatesViewModel by viewModels { UpdatesViewModel.factory(application) }
    private val updaterControllerState = mutableStateOf<UpdaterController?>(null)
    private val progressRevisionState = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val navController = remember {
                object : NavControllerWrapper {
                    override fun navigate(route: String, popUpCurrent: Boolean) {}
                    override fun navigateBack() = finish()
                }
            }
            CompositionLocalProvider(LocalNavController provides navController) {
                SettingsTheme {
                    val uiState by viewModel.uiState.collectAsState()
                    val networkState by NetworkMonitor.getInstance(this@UpdatesScaffoldActivity)
                        .networkState.collectAsState()
                    val batteryState by BatteryMonitor.getInstance(this@UpdatesScaffoldActivity)
                        .batteryState.collectAsState()
                    val meteredWarning by UserPreferencesRepository(this@UpdatesScaffoldActivity)
                        .meteredNetworkWarningFlow.collectAsState(initial = true)
                    val updates = uiState.updates
                    val titleText = when {
                        updates.any { it.status == UpdateStatus.UPDATED_NEED_REBOOT } ->
                            stringResource(R.string.installing_update_finished)

                        updates.any { it.status == UpdateStatus.INSTALLATION_FAILED } ->
                            stringResource(R.string.installing_update_error)

                        updates.any {
                            it.status == UpdateStatus.INSTALLING ||
                                    it.status == UpdateStatus.INSTALLATION_SUSPENDED
                        } -> stringResource(R.string.installing_update)

                        else -> stringResource(R.string.display_name)
                    }
                    val isWideScreen =
                        with(LocalDensity.current) {
                            LocalWindowInfo.current.containerSize.width.toDp() >= 600.dp
                        }
                    val localUpdateSummary =
                        stringResource(R.string.local_update_import_summary)
                    val preferencesSummary =
                        stringResource(R.string.preferences_summary)

                    val footerPane: @Composable () -> Unit = {
                        Category {
                            Preference(object : PreferenceModel {
                                override val title =
                                    stringResource(R.string.local_update_import)
                                override val summary = { localUpdateSummary }
                                override val onClick = { onLocalUpdateClick() }
                            })
                            Preference(object : PreferenceModel {
                                override val title = stringResource(R.string.menu_preferences)
                                override val summary = { preferencesSummary }
                                override val onClick = {
                                    startActivity(
                                        Intent(
                                            this@UpdatesScaffoldActivity,
                                            PreferencesActivity::class.java,
                                        )
                                    )
                                }
                            })
                        }
                    }

                    if (isWideScreen) {
                        SettingsScaffold(
                            title = titleText,
                        ) { paddingValues ->
                            Column(modifier = Modifier.fillMaxSize()) {
                                Spacer(Modifier.height(paddingValues.calculateTopPadding()))
                                LinearLoadingBar(isLoading = uiState.isCheckingForUpdates)
                                Row(modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()) {
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .verticalScroll(rememberScrollState()),
                                    ) {
                                        DeviceInfoBanner()
                                        Spacer(Modifier.height(paddingValues.calculateBottomPadding()))
                                    }
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .verticalScroll(rememberScrollState()),
                                    ) {
                                        UpdatesCheck(
                                            isRefreshing = uiState.isCheckingForUpdates,
                                            isNetworkAvailable = networkState.isOnline,
                                            lastCheckTimestamp = uiState.lastCheckedTimestamp,
                                            onCheckClick = viewModel::fetchUpdates,
                                        )
                                        UpdateList(
                                            updates = updates,
                                            updaterController = updaterControllerState.value,
                                            networkState = networkState,
                                            batteryState = batteryState,
                                            meteredWarning = meteredWarning,
                                            onExportUpdate = ::onExportUpdate,
                                            progressRevision = progressRevisionState.intValue,
                                        )
                                        footerPane()
                                        Spacer(Modifier.height(paddingValues.calculateBottomPadding()))
                                    }
                                }
                            }
                        }
                    } else {
                        SettingsScaffold(
                            title = titleText,
                        ) { paddingValues ->
                            Column(
                                Modifier
                                    .fillMaxSize()
                                    .padding(paddingValues)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                LinearLoadingBar(isLoading = uiState.isCheckingForUpdates)
                                DeviceInfoBanner()
                                UpdatesCheck(
                                    isRefreshing = uiState.isCheckingForUpdates,
                                    isNetworkAvailable = networkState.isOnline,
                                    lastCheckTimestamp = uiState.lastCheckedTimestamp,
                                    onCheckClick = viewModel::fetchUpdates,
                                )
                                UpdateList(
                                    updates = updates,
                                    updaterController = updaterControllerState.value,
                                    networkState = networkState,
                                    batteryState = batteryState,
                                    meteredWarning = meteredWarning,
                                    onExportUpdate = ::onExportUpdate,
                                    progressRevision = progressRevisionState.intValue,
                                )
                                footerPane()
                            }
                        }
                    }
                }
            }
        }
    }

    protected fun setUpdaterController(controller: UpdaterController?) {
        updaterControllerState.value = controller
    }

    protected fun incrementProgressRevision() {
        progressRevisionState.intValue++
    }

    open fun onExportUpdate(update: Update) {}

    open fun onLocalUpdateClick() {}
}
