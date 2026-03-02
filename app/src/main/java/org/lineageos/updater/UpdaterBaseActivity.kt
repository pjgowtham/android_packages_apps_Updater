/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater

import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.compose.rememberNavController
import com.android.settingslib.spa.framework.common.SettingsPageProviderRepository
import com.android.settingslib.spa.framework.common.SpaEnvironment
import com.android.settingslib.spa.framework.common.SpaEnvironmentFactory
import com.android.settingslib.spa.framework.compose.localNavController
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.scaffold.RegularScaffold
import com.android.settingslib.spa.widget.ui.Category

abstract class UpdaterBaseActivity : ComponentActivity() {

    private var legacyView: View? = null
    private val refreshEnabled = mutableStateOf(true)

    protected fun setRefreshEnabled(enabled: Boolean) {
        refreshEnabled.value = enabled
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (!SpaEnvironmentFactory.isReady()) {
            SpaEnvironmentFactory.reset(object : SpaEnvironment(applicationContext) {
                override val pageProviderRepository = lazy {
                    SettingsPageProviderRepository(emptyList())
                }
                override val isSpaExpressiveEnabled = true
            })
        }
        setTheme(com.android.settingslib.spa.R.style.Theme_SpaLib)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
    }

    // Only intercept the resource-ID overload that the Java subclass calls via
    // setContentView(R.layout.activity_updates). The View and View+params overloads
    // are intentionally NOT overridden — ComposeView.setContent() internally calls
    // setContentView(View, params) to attach itself, and intercepting that would
    // cause infinite recursion.
    override fun setContentView(layoutResID: Int) {
        legacyView = layoutInflater.inflate(layoutResID, null)
        val capturedView = legacyView!!
        ComposeView(this).also { composeView ->
            composeView.setContent {
                SettingsTheme {
                    val navController = rememberNavController()
                    CompositionLocalProvider(navController.localNavController()) {
                        RegularScaffold(
                            title = stringResource(R.string.display_name),
                            actions = {
                                IconButton(
                                    onClick = { onRefreshClick() },
                                    enabled = refreshEnabled.value,
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_menu_refresh),
                                        contentDescription = stringResource(R.string.menu_refresh),
                                    )
                                }
                            },
                        ) {
                            AndroidView(
                                factory = { capturedView },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Category {
                                Preference(object : PreferenceModel {
                                    override val title = stringResource(R.string.local_update_import)
                                    override val onClick = { onLocalUpdateClick() }
                                })
                                Preference(object : PreferenceModel {
                                    override val title = stringResource(R.string.menu_show_changelog)
                                    override val onClick = { onChangelogClick() }
                                })
                                Preference(object : PreferenceModel {
                                    override val title = stringResource(R.string.menu_preferences)
                                    override val onClick = { onPreferencesClick() }
                                })
                            }
                        }
                    }
                }
            }
            // Bypasses our own override — goes directly to ComponentActivity.
            super.setContentView(composeView)
        }
    }

    // Compose renders legacyView asynchronously on the next frame, so it is not
    // yet in the live view hierarchy when the Java subclass calls findViewById()
    // during onCreate(). Fall back to searching legacyView directly.
    override fun <T : View> findViewById(id: Int): T? =
        super.findViewById(id) ?: legacyView?.findViewById(id)

    open fun onRefreshClick() {}
    open fun onLocalUpdateClick() {}
    open fun onChangelogClick() {}
    open fun onPreferencesClick() {}
}
