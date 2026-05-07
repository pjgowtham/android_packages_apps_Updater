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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.settingslib.spa.framework.compose.LocalNavController
import com.android.settingslib.spa.framework.compose.NavControllerWrapper
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.scaffold.SettingsScaffold
import com.android.settingslib.spa.widget.ui.Category
import com.android.settingslib.spa.widget.ui.LinearLoadingBar
import org.lineageos.updater.controller.UpdaterController
import org.lineageos.updater.data.Update
import org.lineageos.updater.data.UpdateStatus
import org.lineageos.updater.data.UserPreferencesRepository
import org.lineageos.updater.deviceinfo.DeviceInfoBanner
import org.lineageos.updater.preferences.PreferencesActivity
import org.lineageos.updater.updates.UpdateList
import org.lineageos.updater.updates.action.UpdateActionDialog
import org.lineageos.updater.updates.action.AlertDialogState
import org.lineageos.updater.updates.action.UpdateActionHandler
import org.lineageos.updater.updates.state.UpdateItemStateMapper
import org.lineageos.updater.updatescheck.UpdatesCheck
import org.lineageos.updater.util.NetworkMonitor

abstract class UpdatesScaffoldActivity : ComponentActivity() {

    protected var updaterController: UpdaterController? by mutableStateOf(null)
    protected var controllerStateVersion: Int by mutableStateOf(0)

    private val viewModel: UpdatesViewModel by viewModels { UpdatesViewModel.factory(application) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
    }

    protected fun setupCompose() {
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
                    val userPreferencesRepository = remember {
                        UserPreferencesRepository(this@UpdatesScaffoldActivity)
                    }
                    val streamInstallEnabled by userPreferencesRepository.streamInstallFlow
                        .collectAsState(initial = true)
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
                    var actionDialog by remember {
                        mutableStateOf<AlertDialogState?>(null)
                    }

                    val contentPane: @Composable () -> Unit = {
                        val updateItems = remember(
                            updates,
                            updaterController,
                            networkState.isOnline,
                            streamInstallEnabled,
                            controllerStateVersion,
                        ) {
                            val controller = updaterController ?: return@remember emptyList()
                            val mapper = UpdateItemStateMapper(
                                this@UpdatesScaffoldActivity,
                                controller,
                                streamInstallEnabled,
                            )
                            updates.map { update ->
                                val freshUpdate = checkNotNull(
                                    controller.getUpdate(update.downloadId)
                                ) {
                                    "Controller missing update ${update.downloadId}"
                                }
                                mapper.map(freshUpdate, networkState)
                            }
                        }

                        val updatesCheckModifier = if (isWideScreen) {
                            Modifier.padding(top = SettingsDimension.itemPaddingVertical)
                        } else {
                            Modifier
                        }
                        UpdatesCheck(
                            isRefreshing = uiState.isCheckingForUpdates,
                            isNetworkAvailable = networkState.isOnline,
                            lastCheckTimestamp = uiState.lastCheckedTimestamp,
                            onCheckClick = { viewModel.fetchUpdates() },
                            modifier = updatesCheckModifier,
                        )
                        UpdateList(
                            items = updateItems,
                            onAction = onAction@{ action, downloadId ->
                                val controller = checkNotNull(updaterController) {
                                    "Update action received before updater controller was bound"
                                }
                                val update = checkNotNull(controller.getUpdate(downloadId)) {
                                    "Controller missing update $downloadId"
                                }
                                UpdateActionHandler(
                                    activity = this@UpdatesScaffoldActivity,
                                    updaterController = controller,
                                    exportUpdate = ::exportUpdate,
                                    showDialog = { actionDialog = it },
                                ).perform(action, update)
                            },
                        )
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

                    actionDialog?.let { dialog ->
                        UpdateActionDialog(
                            dialog = dialog,
                            onDismiss = { actionDialog = null },
                        )
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
                                        contentPane()
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
                                contentPane()
                            }
                        }
                    }
                }
            }
        }
    }

    open fun onLocalUpdateClick() {}

    open fun exportUpdate(update: Update) {}

    protected fun notifyControllerStateChanged() {
        controllerStateVersion++
    }
}
