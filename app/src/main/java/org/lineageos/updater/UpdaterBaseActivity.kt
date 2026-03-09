/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.android.settingslib.spa.framework.compose.localNavController
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.scaffold.RegularScaffold
import com.android.settingslib.spa.widget.ui.Category
import org.lineageos.updater.model.UpdateInfo
import org.lineageos.updater.model.UpdateStatus
import org.lineageos.updater.ui.UpdateListItemDialogs
import org.lineageos.updater.ui.UpdatesListScreen
import org.lineageos.updater.viewmodel.UpdateListItemViewModel
import org.lineageos.updater.viewmodel.UpdaterViewModel
import com.android.settingslib.spa.R as SpaR

private const val ROUTE_UPDATES = "updates"
private const val ROUTE_PREFERENCES = "preferences"

/**
 * Base activity providing a [RegularScaffold] with an [UpdaterBanner] header and a fixed
 * section of menu preferences (local update, settings).
 *
 * Subclasses override the open `on*` hooks to respond to toolbar and menu actions.
 */
abstract class UpdaterBaseActivity : ComponentActivity() {

    protected val updaterViewModel: UpdaterViewModel by viewModels()

    /** The item-level ViewModel — created by subclasses which provide SharedPreferences. */
    abstract val itemViewModel: UpdateListItemViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(SpaR.style.Theme_SpaLib)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { UpdaterApp() }
    }

    open fun onLocalUpdateClick() {}
    open fun onExportUpdate(update: UpdateInfo) {}

    // region Composables

    @Composable
    private fun UpdaterApp() {
        SettingsTheme {
            val navController = rememberNavController()
            CompositionLocalProvider(navController.localNavController()) {
                NavHost(
                    navController = navController,
                    startDestination = ROUTE_UPDATES,
                ) {
                    composable(ROUTE_UPDATES) { UpdatesScreen(navController) }
                    composable(ROUTE_PREFERENCES) { PreferencesScreen() }
                }
            }
        }
    }

    @Composable
    private fun UpdatesScreen(navController: NavController) {
        val uiState by updaterViewModel.uiState.collectAsState()
        val checkState by updaterViewModel.updateCheckState.collectAsState()
        val updates = uiState.updates

        val titleText = when {
            !uiState.hasLoaded -> stringResource(R.string.display_name)
            updates.isEmpty() -> stringResource(R.string.snack_no_updates_found)
            updates.any { it.status == UpdateStatus.UPDATED_NEED_REBOOT } ->
                stringResource(R.string.installing_update_finished)
            updates.any {
                it.status == UpdateStatus.INSTALLING ||
                        it.status == UpdateStatus.INSTALLATION_SUSPENDED
            } -> stringResource(R.string.installing_update)
            else -> stringResource(R.string.snack_updates_found)
        }

        RegularScaffold(
            title = titleText,
            actions = { UpdaterActions(isRefreshing = checkState.isRefreshing) },
        ) {
            UpdaterBanner()
            UpdaterCheck(
                isRefreshing = checkState.isRefreshing,
                lastCheckTimestamp = checkState.lastCheckTimestamp,
                onCheckClick = { updaterViewModel.refreshUpdates() },
            )
            UpdatesListScreen(
                downloadIds = uiState.updateIds,
                viewModel = itemViewModel,
                onExportUpdate = { update -> onExportUpdate(update) },
            )
            UpdateListItemDialogs(viewModel = itemViewModel)
            Category {
                val localUpdateSummary = stringResource(R.string.local_update_import_summary)
                val preferencesSummary = stringResource(R.string.preferences_summary)
                Preference(object : PreferenceModel {
                    override val title = stringResource(R.string.local_update_import)
                    override val summary = { localUpdateSummary }
                    override val onClick = { onLocalUpdateClick() }
                })
                Preference(object : PreferenceModel {
                    override val title = stringResource(R.string.menu_preferences)
                    override val summary = { preferencesSummary }
                    override val onClick = { navController.navigate(ROUTE_PREFERENCES) }
                })
            }
        }
    }

    @Composable
    private fun RowScope.UpdaterActions(isRefreshing: Boolean) {
        Box(modifier = Modifier.size(24.dp)) {
            if (isRefreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }

    // endregion
}
