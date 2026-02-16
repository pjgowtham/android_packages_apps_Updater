/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater

import android.app.AlertDialog
import android.app.UiModeManager
import android.content.Intent
import android.content.res.Configuration
import android.icu.text.DateFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity
import kotlinx.coroutines.launch
import org.lineageos.updater.controller.UpdaterController
import org.lineageos.updater.misc.Constants
import org.lineageos.updater.misc.DeviceInfoUtils
import org.lineageos.updater.misc.StringGenerator
import org.lineageos.updater.misc.Utils
import org.lineageos.updater.model.UpdateInfo
import org.lineageos.updater.model.UpdateStatus
import org.lineageos.updater.viewmodel.UpdateCheckViewModel

class UpdatesActivity : CollapsingToolbarBaseActivity(), UpdateImporter.Callbacks,
    UpdaterController.StatusListener {

    private lateinit var viewModel: UpdateCheckViewModel
    private lateinit var adapter: UpdatesListAdapter

    private var isTV = false

    private var toBeExported: UpdateInfo? = null
    private val exportUpdateLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri -> exportUpdate(uri) }
        }
    }

    private lateinit var updateImporter: UpdateImporter
    private var importDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_updates)

        updateImporter = UpdateImporter(this, this)

        val uiModeManager = getSystemService(UiModeManager::class.java)
        isTV = uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

        if (!isTV) {
            setTitle(R.string.display_name)
        } else {
            getAppBarLayout()?.visibility = View.GONE
        }

        val recyclerView = findViewById<RecyclerView>(R.id.recycler_view)
        adapter = UpdatesListAdapter(this)
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(this)
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        findViewById<TextView>(R.id.header_title).text =
            getString(R.string.header_title_text, DeviceInfoUtils.buildVersion)

        findViewById<TextView>(R.id.header_build_version).text =
            getString(R.string.header_android_version, Build.VERSION.RELEASE)

        findViewById<TextView>(R.id.header_build_date).text =
            StringGenerator.getDateLocalizedUTC(
                this, DateFormat.LONG, DeviceInfoUtils.buildDateTimestamp
            )

        findViewById<TextView>(R.id.header_security_patch_level).text =
            getString(
                R.string.header_android_security_update, DeviceInfoUtils.securityPatch
            )

        if (isTV) {
            findViewById<View>(R.id.refresh).setOnClickListener {
                viewModel.fetchUpdates(true)
            }
            findViewById<View>(R.id.preferences).setOnClickListener {
                startActivity(Intent(this, PreferencesActivity::class.java))
            }
        }

        // Set up ViewModel and flow observers
        viewModel = ViewModelProvider(this)[UpdateCheckViewModel::class.java]
        observeViewModel()

        maybeShowWelcomeMessage()
    }

    override fun onStart() {
        super.onStart()
        UpdaterController.getInstance(this).addListener(this)
    }

    override fun onStop() {
        UpdaterController.getInstance(this).removeListener(this)
        super.onStop()
    }

    override fun onPause() {
        importDialog?.let {
            it.dismiss()
            importDialog = null
            updateImporter.stopImport()
        }
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_toolbar, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_refresh -> {
                viewModel.fetchUpdates(true)
                true
            }

            R.id.menu_preferences -> {
                startActivity(Intent(this, PreferencesActivity::class.java))
                true
            }

            R.id.menu_show_changelog -> {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Utils.getChangelogURL(this).toUri()
                    )
                )
                true
            }

            R.id.menu_local_update -> {
                updateImporter.openImportPicker()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!updateImporter.onResult(requestCode, resultCode, data)) {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onImportStarted() {
        if (importDialog?.isShowing == true) {
            importDialog?.dismiss()
        }

        importDialog = AlertDialog.Builder(this)
            .setTitle(R.string.local_update_import)
            .setView(R.layout.progress_dialog)
            .setCancelable(false)
            .create()

        importDialog?.show()
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

        adapter.notifyDataSetChanged()

        val deleteUpdate = Runnable {
            UpdaterController.getInstance(this).deleteUpdate(update.downloadId)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.local_update_import)
            .setMessage(getString(R.string.local_update_import_success, update.version))
            .setPositiveButton(R.string.local_update_import_install) { _, _ ->
                adapter.addItem(update.downloadId)
                Utils.triggerUpdate(this, update.downloadId)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> deleteUpdate.run() }
            .setOnCancelListener { deleteUpdate.run() }
            .show()
    }

    private fun updateLastCheckedString(millis: Long) {
        val lastCheck = millis / 1000
        val lastCheckString = getString(
            R.string.header_last_updates_check,
            StringGenerator.getDateLocalized(this, DateFormat.LONG, lastCheck),
            StringGenerator.getTimeLocalized(this, lastCheck)
        )
        findViewById<TextView>(R.id.header_last_check).text = lastCheckString
    }

    fun exportUpdate(update: UpdateInfo) {
        toBeExported = update

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, update.name)
        }

        exportUpdateLauncher.launch(intent)
    }

    private fun exportUpdate(uri: Uri) {
        val intent = Intent(this, ExportUpdateService::class.java).apply {
            action = ExportUpdateService.ACTION_START_EXPORTING
            putExtra(ExportUpdateService.EXTRA_SOURCE_FILE, toBeExported?.file)
            putExtra(ExportUpdateService.EXTRA_DEST_URI, uri)
        }
        startService(intent)
    }

    fun showToast(stringId: Int, duration: Int) {
        Toast.makeText(this, stringId, duration).show()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.updaterController.collect { controller ->
                        adapter.setUpdaterController(controller)
                    }
                }
                launch {
                    viewModel.uiState.collect { state ->
                        adapter.setData(state.updateIds)
                        adapter.notifyDataSetChanged()
                        val empty = state.updateIds.isEmpty()
                        findViewById<View>(R.id.no_new_updates_view).visibility =
                            if (empty) View.VISIBLE else View.GONE
                        findViewById<View>(R.id.recycler_view).visibility =
                            if (empty) View.GONE else View.VISIBLE
                        updateLastCheckedString(state.lastCheckTimestamp)
                    }
                }
                launch {
                    viewModel.uiEvent.collect { event ->
                        when (event) {
                            is UpdateCheckViewModel.UiEvent.ShowMessage -> {
                                showToast(
                                    event.messageId,
                                    if (event.long) Toast.LENGTH_LONG
                                    else Toast.LENGTH_SHORT
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun maybeShowWelcomeMessage() {
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val alreadySeen =
            preferences.getBoolean(Constants.PREF_HAS_SEEN_WELCOME_MESSAGE, false)
        if (alreadySeen) {
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.welcome_title)
            .setMessage(R.string.welcome_message)
            .setPositiveButton(R.string.info_dialog_ok) { _, _ ->
                preferences.edit {
                    putBoolean(Constants.PREF_HAS_SEEN_WELCOME_MESSAGE, true)
                }
            }
            .show()
    }

    override fun onUpdateStatusChanged(downloadId: String?) {
        runOnUiThread {
            val controller = UpdaterController.getInstance(this)
            val update = controller.getUpdate(downloadId)
            if (update != null) {
                var msgId = -1
                val status = update.status
                if (status == UpdateStatus.PAUSED_ERROR) {
                    msgId = R.string.snack_download_failed
                } else if (status == UpdateStatus.VERIFICATION_FAILED) {
                    msgId = R.string.snack_download_verification_failed
                } else if (status == UpdateStatus.VERIFIED) {
                    msgId = R.string.snack_download_verified
                }
                if (msgId != -1) {
                    showToast(msgId, Toast.LENGTH_LONG)
                }
            }
            adapter.notifyItemChanged(downloadId)
        }
    }

    override fun onDownloadProgressChanged(downloadId: String?) {
        runOnUiThread {
            adapter.notifyItemChanged(downloadId)
        }
    }

    override fun onInstallProgressChanged(downloadId: String?) {
        runOnUiThread {
            adapter.notifyItemChanged(downloadId)
        }
    }

    override fun onUpdateRemoved(downloadId: String?) {
        runOnUiThread {
            adapter.removeItem(downloadId)
        }
    }
}
