/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import org.lineageos.updater.controller.UpdaterController
import org.lineageos.updater.controller.UpdaterService
import org.lineageos.updater.misc.Utils
import org.lineageos.updater.model.UpdateInfo
import org.lineageos.updater.viewmodel.UpdateListItemViewModel

class UpdatesActivity : UpdaterBaseActivity(), UpdateImporter.Callbacks {

    private var updaterService: UpdaterService? = null

    override val itemViewModel: UpdateListItemViewModel by lazy {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        ViewModelProvider(
            this,
            UpdateListItemViewModel.Factory(application, prefs),
        )[UpdateListItemViewModel::class.java]
    }

    private var toBeExported: UpdateInfo? = null
    private val exportUpdateLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> exportUpdate(uri) }
        }
    }

    private lateinit var updateImporter: UpdateImporter
    private var importDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateImporter = UpdateImporter(this, this)
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, UpdaterService::class.java)
        startService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onPause() {
        importDialog?.let {
            it.dismiss()
            importDialog = null
            updateImporter.stopImport()
        }
        super.onPause()
    }

    override fun onStop() {
        if (updaterService != null) {
            unbindService(connection)
        }
        super.onStop()
    }

    @Deprecated("Use activity result APIs instead")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!updateImporter.onResult(requestCode, resultCode, data)) {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    // region UpdateImporter.Callbacks

    override fun onImportStarted() {
        importDialog?.takeIf { it.isShowing }?.dismiss()

        importDialog = AlertDialog.Builder(this)
            .setTitle(R.string.local_update_import)
            .setView(R.layout.progress_dialog)
            .setCancelable(false)
            .create()
            .also { it.show() }
    }

    override fun onImportCompleted(update: UpdateInfo?) {
        importDialog?.dismiss()
        importDialog = null

        if (update == null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.local_update_import)
                .setMessage(R.string.local_update_import_failure)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val deleteUpdate = Runnable {
            UpdaterController.getInstance(this).deleteUpdate(update.downloadId)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.local_update_import)
            .setMessage(getString(R.string.local_update_import_success, update.version))
            .setPositiveButton(R.string.local_update_import_install) { _, _ ->
                updaterViewModel.refreshUpdates()
                Utils.triggerUpdate(this, update.downloadId)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> deleteUpdate.run() }
            .setOnCancelListener { deleteUpdate.run() }
            .show()
    }

    // endregion

    override fun onLocalUpdateClick() {
        updateImporter.openImportPicker()
    }

    override fun onExportUpdate(update: UpdateInfo) {
        toBeExported = update

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, update.name)
        }
        exportUpdateLauncher.launch(intent)
    }

    // region Private

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as UpdaterService.LocalBinder
            updaterService = binder.service
            val controller = binder.service.updaterController
            itemViewModel.setController(controller)
            updaterViewModel.refreshUpdates()
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            itemViewModel.setController(null)
            updaterService = null
        }
    }

    private fun exportUpdate(uri: Uri) {
        val intent = Intent(this, ExportUpdateService::class.java).apply {
            action = ExportUpdateService.ACTION_START_EXPORTING
            putExtra(ExportUpdateService.EXTRA_SOURCE_FILE, toBeExported?.file)
            putExtra(ExportUpdateService.EXTRA_DEST_URI, uri)
        }
        startService(intent)
    }

    // endregion
}
