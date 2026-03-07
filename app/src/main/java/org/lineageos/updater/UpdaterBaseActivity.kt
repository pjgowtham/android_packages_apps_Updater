/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater

import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.android.settingslib.spa.framework.compose.localNavController
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.scaffold.RegularScaffold
import com.android.settingslib.spa.widget.ui.Category
import org.lineageos.updater.model.UpdateStatus
import org.lineageos.updater.viewmodel.UpdaterViewModel
import com.android.settingslib.spa.R as SpaR

private const val ROUTE_UPDATES = "updates"
private const val ROUTE_PREFERENCES = "preferences"

/**
 * Base activity providing a [RegularScaffold] with an [UpdaterBanner] header and a fixed
 * section of menu preferences (local update, settings).
 *
 * Subclasses override the open `on*Click` hooks to respond to toolbar and menu actions.
 */
abstract class UpdaterBaseActivity : ComponentActivity() {

    private var legacyView: View? = null

    private val viewModel: UpdaterViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(SpaR.style.Theme_SpaLib)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
    }

    // Only intercept the resource-ID overload that the Java subclass calls via
    // setContentView(R.layout.activity_updates). The View and View+params overloads
    // are intentionally NOT overridden — ComposeView.setContent() internally calls
    // setContentView(View, params) to attach itself, and intercepting that would
    // cause infinite recursion.
    override fun setContentView(layoutResID: Int) {
        val capturedView = layoutInflater.inflate(layoutResID, null).also { legacyView = it }
        val composeView = ComposeView(this)
        composeView.setContent {
            SettingsTheme {
                val navController = rememberNavController()
                CompositionLocalProvider(navController.localNavController()) {
                    NavHost(
                        navController = navController,
                        startDestination = ROUTE_UPDATES,
                    ) {
                        composable(ROUTE_UPDATES) {
                            val uiState by viewModel.uiState.collectAsState()
                            val checkState by viewModel.updateCheckState.collectAsState()
                            val updates = uiState.updates
                            val titleText: String
                            when {
                                !uiState.hasLoaded -> {
                                    titleText = stringResource(R.string.display_name)
                                }

                                updates.isEmpty() -> {
                                    titleText = stringResource(R.string.snack_no_updates_found)
                                }

                                updates.any { it.status == UpdateStatus.UPDATED_NEED_REBOOT } -> {
                                    titleText = stringResource(R.string.installing_update_finished)
                                }

                                updates.any {
                                    it.status == UpdateStatus.INSTALLING ||
                                            it.status == UpdateStatus.INSTALLATION_SUSPENDED
                                } -> {
                                    titleText = stringResource(R.string.installing_update)
                                }

                                else -> {
                                    titleText = stringResource(R.string.snack_updates_found)
                                }
                            }
                            RegularScaffold(
                                title = titleText,
                                actions = {
                                    updaterActions(
                                        isRefreshing = checkState.isRefreshing,
                                    )
                                },
                            ) {
                                UpdaterBanner()
                                UpdaterCheck(
                                    isRefreshing = checkState.isRefreshing,
                                    lastCheckTimestamp = checkState.lastCheckTimestamp,
                                    onCheckClick = { viewModel.refreshUpdates() },
                                )
                                AndroidView(
                                    factory = { capturedView },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Category {
                                    val localUpdateSummary =
                                        stringResource(R.string.local_update_import_summary)
                                    val preferencesSummary =
                                        stringResource(R.string.preferences_summary)
                                    Preference(object : PreferenceModel {
                                        override val title =
                                            stringResource(R.string.local_update_import)
                                        override val summary = { localUpdateSummary }
                                        override val onClick = { onLocalUpdateClick() }
                                    })
                                    Preference(object : PreferenceModel {
                                        override val title =
                                            stringResource(R.string.menu_preferences)
                                        override val summary = { preferencesSummary }
                                        override val onClick =
                                            { navController.navigate(ROUTE_PREFERENCES) }
                                    })
                                }
                            }
                        }
                        composable(ROUTE_PREFERENCES) {
                            PreferencesScreen()
                        }
                    }
                }
            }
        }
        // Bypasses our own override — goes directly to ComponentActivity.
        super.setContentView(composeView)
    }

    // Compose renders legacyView asynchronously on the next frame, so it is not
    // yet in the live view hierarchy when the Java subclass calls findViewById()
    // during onCreate(). Fall back to searching legacyView directly.
    override fun <T : View> findViewById(id: Int): T? =
        super.findViewById(id) ?: legacyView?.findViewById(id)

    open fun onLocalUpdateClick() {}
    open fun onChangelogClick() {}


    @Composable
    private fun RowScope.updaterActions(
        isRefreshing: Boolean,
    ) {
        Box(modifier = Modifier.size(24.dp)) {
            if (isRefreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}
