/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater

import android.app.AlertDialog as ViewAlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.window.core.layout.WindowSizeClass
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import com.android.settingslib.spa.framework.compose.LifecycleEffect
import com.android.settingslib.spa.framework.compose.LocalNavController
import com.android.settingslib.spa.framework.compose.NavControllerWrapper
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.framework.util.collectLatestWithLifecycle
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.scaffold.RegularScaffold
import com.android.settingslib.spa.widget.scaffold.SettingsScaffold
import com.android.settingslib.spa.widget.ui.Category
import com.android.settingslib.spa.widget.ui.LinearLoadingBar
import org.lineageos.updater.controller.UpdaterController
import org.lineageos.updater.controller.UpdaterService
import org.lineageos.updater.data.Update
import org.lineageos.updater.data.UpdateStatus
import org.lineageos.updater.misc.Utils
import org.lineageos.updater.preferences.PreferencesActivity
import org.lineageos.updater.update.UpdateList
import org.lineageos.updater.util.NetworkMonitor

class UpdatesActivity : ComponentActivity(), UpdateImporter.Callbacks {

    private var updaterService: UpdaterService? = null
    private var isImporting by mutableStateOf(false)
    private var toBeExported: Update? = null

    private val viewModel: UpdatesViewModel by viewModels { UpdatesViewModel.factory(application) }
    private val updateImporter = UpdateImporter(this, this)
    private val updaterController = mutableStateOf<UpdaterController?>(null)
    private val progressRevision = mutableIntStateOf(0)

    private val exportResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri -> performExport(uri) }
        }
    }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UpdaterController.ACTION_UPDATE_STATUS -> {
                    val downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID)
                    handleDownloadStatusChange(downloadId)
                    progressRevision.intValue++
                }

                UpdaterController.ACTION_DOWNLOAD_PROGRESS,
                UpdaterController.ACTION_INSTALL_PROGRESS,
                UpdaterController.ACTION_UPDATE_REMOVED -> progressRevision.intValue++
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val updaterService = (service as UpdaterService.LocalBinder).service
            this@UpdatesActivity.updaterService = updaterService
            updaterController.value = updaterService.updaterController
            refreshUpdatesList(viewModel.uiState.value.updates)
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            updaterController.value = null
            updaterService = null
            progressRevision.intValue++
        }
    }

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
                    LifecycleEffect(
                        onStart = {
                            val intent = Intent(this@UpdatesActivity, UpdaterService::class.java)
                            startService(intent)
                            bindService(intent, connection, BIND_AUTO_CREATE)
                            registerReceiver(
                                broadcastReceiver,
                                IntentFilter().apply {
                                    addAction(UpdaterController.ACTION_UPDATE_STATUS)
                                    addAction(UpdaterController.ACTION_DOWNLOAD_PROGRESS)
                                    addAction(UpdaterController.ACTION_INSTALL_PROGRESS)
                                    addAction(UpdaterController.ACTION_UPDATE_REMOVED)
                                },
                                RECEIVER_NOT_EXPORTED,
                            )
                        },
                        onStop = {
                            unregisterReceiver(broadcastReceiver)
                            if (updaterService != null) unbindService(connection)
                        },
                    )
                    if (isImporting) {
                        AlertDialog(
                            onDismissRequest = {},
                            properties = DialogProperties(
                                dismissOnBackPress = false,
                                dismissOnClickOutside = false,
                            ),
                            title = { Text(stringResource(R.string.local_update_import)) },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(40.dp))
                                    Spacer(Modifier.width(16.dp))
                                    Text(stringResource(R.string.local_update_import_progress))
                                }
                            },
                            confirmButton = {},
                        )
                    }
                    val uiState by viewModel.uiState.collectAsState()
                    val networkState by NetworkMonitor.getInstance(this)
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
                    val localUpdateSummary = stringResource(R.string.local_update_import_summary)
                    val preferencesSummary = stringResource(R.string.preferences_summary)

                    val footerPane: @Composable () -> Unit = {
                        Category {
                            Preference(object : PreferenceModel {
                                override val title = stringResource(R.string.local_update_import)
                                override val summary = { localUpdateSummary }
                                override val onClick = { updateImporter.openImportPicker() }
                            })
                            Preference(object : PreferenceModel {
                                override val title = stringResource(R.string.menu_preferences)
                                override val summary = { preferencesSummary }
                                override val onClick = {
                                    startActivity(
                                        Intent(
                                            this@UpdatesActivity,
                                            PreferencesActivity::class.java,
                                        )
                                    )
                                }
                            })
                        }
                    }

                    if (isWideScreen) {
                        SettingsScaffold(title = titleText) { paddingValues ->
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
                                            updaterController = updaterController.value,
                                            networkState = networkState,
                                            onExportUpdate = ::onExportUpdate,
                                            progressRevision = progressRevision.intValue,
                                        )
                                        footerPane()
                                        Spacer(Modifier.height(paddingValues.calculateBottomPadding()))
                                    }
                                }
                            }
                        }
                    } else {
                        RegularScaffold(title = titleText) {
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
                                updaterController = updaterController.value,
                                networkState = networkState,
                                onExportUpdate = ::onExportUpdate,
                                progressRevision = progressRevision.intValue,
                            )
                            footerPane()
                        }
                    }
                }
            }
        }

        viewModel.uiState.collectLatestWithLifecycle(this) { state ->
            if (state.errorMessage != null) {
                Toast.makeText(this, R.string.snack_updates_check_failed, Toast.LENGTH_LONG).show()
                viewModel.errorMessageShown()
            }
            if (updaterService != null) {
                refreshUpdatesList(state.updates)
            }
        }
    }

    override fun onPause() {
        if (isImporting) {
            isImporting = false
            updateImporter.stopImport()
        }
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!updateImporter.onResult(requestCode, resultCode, data)) {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onImportStarted() {
        isImporting = true
    }

    override fun onImportCompleted(update: Update?) {
        isImporting = false

        if (update == null) {
            ViewAlertDialog.Builder(this)
                .setTitle(R.string.local_update_import)
                .setMessage(R.string.local_update_import_failure)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val deleteUpdate = { UpdaterController.getInstance(this).deleteUpdate(update.downloadId) }

        ViewAlertDialog.Builder(this)
            .setTitle(R.string.local_update_import)
            .setMessage(getString(R.string.local_update_import_success, update.version))
            .setPositiveButton(R.string.local_update_import_install) { _, _ ->
                refreshUpdatesList(viewModel.uiState.value.updates)
                Utils.triggerUpdate(this, update.downloadId)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> deleteUpdate() }
            .setOnCancelListener { deleteUpdate() }
            .show()
    }

    private fun refreshUpdatesList(updates: List<Update>) {
        val controller = updaterService?.updaterController ?: return
        val updateIds = updates.map { update ->
            controller.addUpdate(update)
            update.downloadId
        }
        controller.setUpdatesAvailableOnline(updateIds, true)
    }

    private fun handleDownloadStatusChange(downloadId: String?) {
        if (downloadId == Update.LOCAL_ID) return
        val update = updaterService?.updaterController?.getUpdate(downloadId) ?: return
        when (update.status) {
            UpdateStatus.PAUSED_ERROR ->
                Toast.makeText(this, R.string.snack_download_failed, Toast.LENGTH_LONG).show()

            UpdateStatus.VERIFICATION_FAILED ->
                Toast.makeText(
                    this, R.string.snack_download_verification_failed, Toast.LENGTH_LONG,
                ).show()

            UpdateStatus.VERIFIED ->
                Toast.makeText(this, R.string.snack_download_verified, Toast.LENGTH_LONG).show()

            else -> {}
        }
    }

    private fun onExportUpdate(update: Update) {
        toBeExported = update
        exportResultLauncher.launch(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/zip"
                putExtra(Intent.EXTRA_TITLE, update.name)
            }
        )
    }

    private fun performExport(uri: Uri) {
        val file = toBeExported?.file ?: return
        startService(
            Intent(this, ExportUpdateService::class.java).apply {
                action = ExportUpdateService.ACTION_START_EXPORTING
                putExtra(ExportUpdateService.EXTRA_SOURCE_FILE, file)
                putExtra(ExportUpdateService.EXTRA_DEST_URI, uri)
            }
        )
    }
}
