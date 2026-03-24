/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater

import android.content.Intent
import android.os.Bundle
import android.view.View
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.window.core.layout.WindowSizeClass
import com.android.settingslib.spa.framework.compose.LocalNavController
import com.android.settingslib.spa.framework.compose.NavControllerWrapper
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.scaffold.RegularScaffold
import com.android.settingslib.spa.widget.scaffold.SettingsScaffold
import com.android.settingslib.spa.widget.ui.Category
import com.android.settingslib.spa.widget.ui.LinearLoadingBar
import org.lineageos.updater.data.UpdateStatus
import org.lineageos.updater.preferences.PreferencesActivity
import org.lineageos.updater.util.NetworkMonitor

abstract class UpdaterBaseActivity : ComponentActivity() {

    private var legacyView: View? = null
    private val viewModel: UpdatesViewModel by viewModels { UpdatesViewModel.factory(application) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
    }

    override fun setContentView(layoutResID: Int) {
        val capturedView = layoutInflater.inflate(layoutResID, null).also { legacyView = it }

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
                    val networkState by NetworkMonitor.getInstance(this@UpdaterBaseActivity)
                        .networkState.collectAsState()
                    val updates = uiState.updates
                    val titleText = when {
                        updates.isEmpty() && uiState.lastCheckedTimestamp == 0L ->
                            stringResource(R.string.display_name)

                        updates.isEmpty() -> stringResource(R.string.snack_no_updates_found)
                        updates.any { it.status == UpdateStatus.UPDATED_NEED_REBOOT } ->
                            stringResource(R.string.installing_update_finished)

                        updates.any {
                            it.status == UpdateStatus.INSTALLING ||
                                    it.status == UpdateStatus.INSTALLATION_SUSPENDED
                        } -> stringResource(R.string.installing_update)

                        updates.any { it.status == UpdateStatus.VERIFYING } ->
                            stringResource(R.string.verifying_download_notification)

                        updates.any {
                            it.status == UpdateStatus.DOWNLOADING ||
                                    it.status == UpdateStatus.STARTING ||
                                    it.status == UpdateStatus.PAUSED ||
                                    it.status == UpdateStatus.PAUSED_ERROR
                        } -> stringResource(R.string.downloading_notification)

                        else -> stringResource(R.string.snack_updates_found)
                    }
                    val windowAdaptiveInfo = currentWindowAdaptiveInfo()
                    val isWideScreen = windowAdaptiveInfo.windowSizeClass
                        .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
                    val localUpdateSummary =
                        stringResource(R.string.local_update_import_summary)
                    val preferencesSummary =
                        stringResource(R.string.preferences_summary)

                    val contentPane: @Composable () -> Unit = {
                        UpdatesCheck(
                            isRefreshing = uiState.isCheckingForUpdates,
                            isNetworkAvailable = networkState.isOnline,
                            lastCheckTimestamp = uiState.lastCheckedTimestamp,
                            onCheckClick = viewModel::fetchUpdates,
                        )
                        AndroidView(
                            factory = { capturedView },
                            modifier = Modifier.fillMaxWidth(),
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
                                            this@UpdaterBaseActivity,
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
                                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
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
                        RegularScaffold(
                            title = titleText,
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

    override fun <T : View> findViewById(id: Int): T? =
        super.findViewById(id) ?: legacyView?.findViewById(id)

    open fun onLocalUpdateClick() {}
}
