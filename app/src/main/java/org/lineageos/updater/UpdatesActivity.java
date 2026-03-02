/*
 * Copyright (C) 2017-2026 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lineageos.updater;

import android.app.Activity;
import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.icu.text.DateFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.android.settingslib.utils.ColorUtil;
import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.CollapsingToolbarLayout;

import org.lineageos.updater.controller.UpdaterController;
import org.lineageos.updater.controller.UpdaterService;
import org.lineageos.updater.misc.Constants;
import org.lineageos.updater.misc.DeviceInfoUtils;
import org.lineageos.updater.misc.StringGenerator;
import org.lineageos.updater.misc.Utils;
import org.lineageos.updater.model.UpdateInfo;
import org.lineageos.updater.viewmodel.FetchResult;
import org.lineageos.updater.viewmodel.UpdaterViewModel;

import java.util.ArrayList;
import java.util.List;

public class UpdatesActivity extends AppCompatActivity implements
        UpdateImporter.Callbacks {

    private static final String TAG = "UpdatesActivity";
    private UpdaterService mUpdaterService;
    private BroadcastReceiver mBroadcastReceiver;

    private UpdatesListAdapter mAdapter;

    private UpdaterViewModel mViewModel;

    private boolean mIsTV;

    private UpdateInfo mToBeExported = null;
    private final ActivityResultLauncher<Intent> mExportUpdate = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    Intent intent = result.getData();
                    if (intent != null) {
                        Uri uri = intent.getData();
                        exportUpdate(uri);
                    }
                }
            });

    private Menu mMenu;
    private UpdateImporter mUpdateImporter;
    private AlertDialog importDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_updates);

        mViewModel = new ViewModelProvider(this).get(UpdaterViewModel.class);
        mViewModel.getUiStateLiveData().observe(this, state -> {
            boolean hasUpdates = !state.getUpdates().isEmpty();
            if (hasUpdates) {
                findViewById(R.id.no_new_updates_view).setVisibility(View.GONE);
                findViewById(R.id.recycler_view).setVisibility(View.VISIBLE);
            } else {
                findViewById(R.id.no_new_updates_view).setVisibility(View.VISIBLE);
                findViewById(R.id.recycler_view).setVisibility(View.GONE);
            }
            mAdapter.setData(state.getUpdateIds());
            mAdapter.notifyDataSetChanged();
        });
        mViewModel.getUpdateCheckStateLiveData().observe(this, checkState -> {
            setRefreshEnabled(!checkState.isRefreshing());

            if (checkState.getFetchResult() != null) {
                FetchResult result = checkState.getFetchResult();
                if (result instanceof FetchResult.Success) {
                    updateLastCheckedString(checkState.getLastCheckTimestamp());
                    boolean hasNew = ((FetchResult.Success) result).getHasNewUpdates();
                    showToast(hasNew ? R.string.snack_updates_found : R.string.snack_no_updates_found, Toast.LENGTH_SHORT);
                } else if (result instanceof FetchResult.Error) {
                    showToast(R.string.snack_updates_check_failed, Toast.LENGTH_LONG);
                }
                mViewModel.consumeFetchResult();
            }
        });

        mUpdateImporter = new UpdateImporter(this, this);

        UiModeManager uiModeManager = getSystemService(UiModeManager.class);
        mIsTV = uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;

        RecyclerView recyclerView = findViewById(R.id.recycler_view);
        mAdapter = new UpdatesListAdapter(this, this::exportUpdate);
        recyclerView.setAdapter(mAdapter);
        RecyclerView.LayoutManager layoutManager = new LinearLayoutManager(this);
        recyclerView.setLayoutManager(layoutManager);
        RecyclerView.ItemAnimator animator = recyclerView.getItemAnimator();
        if (animator instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        }

        mBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (UpdaterController.ACTION_UPDATE_STATUS.equals(intent.getAction())) {
                    String downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID);
                    handleDownloadStatusChange(downloadId);
                    mAdapter.notifyItemChanged(downloadId);
                } else if (UpdaterController.ACTION_DOWNLOAD_PROGRESS.equals(intent.getAction()) ||
                        UpdaterController.ACTION_INSTALL_PROGRESS.equals(intent.getAction())) {
                    String downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID);
                    mAdapter.notifyItemChanged(downloadId);
                } else if (UpdaterController.ACTION_UPDATE_REMOVED.equals(intent.getAction())) {
                    String downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID);
                    mAdapter.removeItem(downloadId);
                    List<UpdateInfo> sortedUpdates =
                            mUpdaterService.getUpdaterController().getUpdates();
                    if (sortedUpdates.isEmpty()) {
                        findViewById(R.id.no_new_updates_view).setVisibility(View.VISIBLE);
                        findViewById(R.id.recycler_view).setVisibility(View.GONE);
                    }
                }
            }
        };

        if (!mIsTV) {
            Toolbar toolbar = findViewById(R.id.toolbar);
            setSupportActionBar(toolbar);
            ActionBar actionBar = getSupportActionBar();
            if (actionBar != null) {
                actionBar.setDisplayShowTitleEnabled(false);
                actionBar.setDisplayHomeAsUpEnabled(true);
                final int statusBarHeight;
                TypedValue tv = new TypedValue();
                if (getTheme().resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
                    statusBarHeight = TypedValue.complexToDimensionPixelSize(
                            tv.data, getResources().getDisplayMetrics());
                } else {
                    statusBarHeight = 0;
                }
                RelativeLayout headerContainer = findViewById(R.id.header_container);
                recyclerView.setOnApplyWindowInsetsListener((view, insets) -> {
                    int top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
                    CollapsingToolbarLayout.LayoutParams lp =
                            (CollapsingToolbarLayout.LayoutParams)
                                    headerContainer.getLayoutParams();
                    lp.topMargin = top + statusBarHeight;
                    headerContainer.setLayoutParams(lp);
                    return insets;
                });
            }
        }

        TextView headerTitle = findViewById(R.id.header_title);
        headerTitle.setText(getString(R.string.header_title_text,
                DeviceInfoUtils.getBuildVersion()));

        TextView headerBuildVersion = findViewById(R.id.header_build_version);
        headerBuildVersion.setText(
                getString(R.string.header_android_version, Build.VERSION.RELEASE));

        TextView headerBuildDate = findViewById(R.id.header_build_date);
        headerBuildDate.setText(StringGenerator.getDateLocalizedUTC(this,
                DateFormat.LONG, DeviceInfoUtils.getBuildDateTimestamp()));

        TextView headerSecurityPatch = findViewById(R.id.header_security_patch_level);
        headerSecurityPatch.setText(getString(R.string.header_android_security_update,
                DeviceInfoUtils.getSecurityPatch()));

        if (!mIsTV) {
            // Switch between header title and appbar title minimizing overlaps
            final CollapsingToolbarLayout collapsingToolbar = findViewById(R.id.collapsing_toolbar);
            final AppBarLayout appBar = findViewById(R.id.app_bar);
            appBar.addOnOffsetChangedListener(new AppBarLayout.OnOffsetChangedListener() {
                boolean mIsShown = false;

                @Override
                public void onOffsetChanged(AppBarLayout appBarLayout, int verticalOffset) {
                    int scrollRange = appBarLayout.getTotalScrollRange();
                    if (!mIsShown && scrollRange + verticalOffset < 10) {
                        collapsingToolbar.setTitle(getString(R.string.display_name));
                        mIsShown = true;
                    } else if (mIsShown && scrollRange + verticalOffset > 100) {
                        collapsingToolbar.setTitle(null);
                        mIsShown = false;
                    }
                }
            });

            if (!Utils.hasTouchscreen(this)) {
                // This can't be collapsed without a touchscreen
                appBar.setExpanded(false);
            }
        } else {
            findViewById(R.id.refresh).setOnClickListener(v ->
                    mViewModel.refreshUpdates());
            findViewById(R.id.preferences).setOnClickListener(v -> showPreferencesDialog());
        }

        maybeShowWelcomeMessage();
    }

    @Override
    public void onStart() {
        super.onStart();
        Intent intent = new Intent(this, UpdaterService.class);
        startService(intent);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(UpdaterController.ACTION_UPDATE_STATUS);
        intentFilter.addAction(UpdaterController.ACTION_DOWNLOAD_PROGRESS);
        intentFilter.addAction(UpdaterController.ACTION_INSTALL_PROGRESS);
        intentFilter.addAction(UpdaterController.ACTION_UPDATE_REMOVED);
        LocalBroadcastManager.getInstance(this).registerReceiver(mBroadcastReceiver, intentFilter);
    }

    @Override
    protected void onPause() {
        if (importDialog != null) {
            importDialog.dismiss();
            importDialog = null;
            mUpdateImporter.stopImport();
        }

        super.onPause();
    }

    @Override
    public void onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mBroadcastReceiver);
        if (mUpdaterService != null) {
            unbindService(mConnection);
        }
        super.onStop();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_toolbar, menu);
        mMenu = menu;
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.menu_refresh) {
            mViewModel.refreshUpdates();
            return true;
        } else if (itemId == R.id.menu_preferences) {
            showPreferencesDialog();
            return true;
        } else if (itemId == R.id.menu_show_changelog) {
            Intent openUrl = new Intent(Intent.ACTION_VIEW,
                    Uri.parse(Utils.getChangelogURL(this)));
            startActivity(openUrl);
            return true;
        } else if (itemId == R.id.menu_local_update) {
            mUpdateImporter.openImportPicker();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (!mUpdateImporter.onResult(requestCode, resultCode, data)) {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onImportStarted() {
        if (importDialog != null && importDialog.isShowing()) {
            importDialog.dismiss();
        }

        importDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.local_update_import)
                .setView(R.layout.progress_dialog)
                .setCancelable(false)
                .create();

        importDialog.show();
    }

    @Override
    public void onImportCompleted(UpdateInfo update) {
        if (importDialog != null) {
            importDialog.dismiss();
            importDialog = null;
        }

        if (update == null) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.local_update_import)
                    .setMessage(R.string.local_update_import_failure)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        mAdapter.notifyDataSetChanged();

        final Runnable deleteUpdate = () -> UpdaterController.getInstance(this)
                .deleteUpdate(update.getDownloadId());

        new AlertDialog.Builder(this)
                .setTitle(R.string.local_update_import)
                .setMessage(getString(R.string.local_update_import_success, update.getVersion()))
                .setPositiveButton(R.string.local_update_import_install, (dialog, which) -> {
                    mAdapter.addItem(update.getDownloadId());
                    mViewModel.refreshUpdates();
                    Utils.triggerUpdate(this, update.getDownloadId());
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> deleteUpdate.run())
                .setOnCancelListener((dialog) -> deleteUpdate.run())
                .show();
    }

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className,
                IBinder service) {
            UpdaterService.LocalBinder binder = (UpdaterService.LocalBinder) service;
            mUpdaterService = binder.getService();
            UpdaterController controller = mUpdaterService.getUpdaterController();
            mAdapter.setUpdaterController(controller);
            mViewModel.refreshUpdates();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mAdapter.setUpdaterController(null);
            mUpdaterService = null;
            mAdapter.notifyDataSetChanged();
        }
    };

    private void setRefreshEnabled(boolean enabled) {
        float disabledAlpha = ColorUtil.getDisabledAlpha(this);
        if (!mIsTV) {
            if (mMenu != null) {
                MenuItem item = mMenu.findItem(R.id.menu_refresh);
                if (item != null) {
                    item.setEnabled(enabled);
                    if (item.getIcon() != null) {
                        item.getIcon().setAlpha(enabled ? 255
                                : (int) (255 * disabledAlpha));
                    }
                }
            }
        } else {
            View refreshButton = findViewById(R.id.refresh);
            if (refreshButton != null) {
                refreshButton.setEnabled(enabled);
                refreshButton.setAlpha(enabled ? 1f : disabledAlpha);
            }
        }
    }

    private void updateLastCheckedString(long lastCheckMillis) {
        if (lastCheckMillis <= 0) return;
        long lastCheckSeconds = lastCheckMillis / 1000;
        String lastCheckString = getString(R.string.header_last_updates_check,
                StringGenerator.getDateLocalized(this, DateFormat.LONG, lastCheckSeconds),
                StringGenerator.getTimeLocalized(this, lastCheckSeconds));
        ((TextView) findViewById(R.id.header_last_check)).setText(lastCheckString);
    }

    private void handleDownloadStatusChange(String downloadId) {
        if (UpdateInfo.LOCAL_ID.equals(downloadId)) {
            return;
        }

        UpdateInfo update = mUpdaterService.getUpdaterController().getUpdate(downloadId);
        switch (update.getStatus()) {
            case PAUSED_ERROR:
                showToast(R.string.snack_download_failed, Toast.LENGTH_LONG);
                break;
            case VERIFICATION_FAILED:
                showToast(R.string.snack_download_verification_failed, Toast.LENGTH_LONG);
                break;
            case VERIFIED:
                showToast(R.string.snack_download_verified, Toast.LENGTH_LONG);
                break;
        }
    }

    private void exportUpdate(UpdateInfo update) {
        mToBeExported = update;

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, update.getName());

        mExportUpdate.launch(intent);
    }

    private void exportUpdate(Uri uri) {
        Intent intent = new Intent(this, ExportUpdateService.class);
        intent.setAction(ExportUpdateService.ACTION_START_EXPORTING);
        intent.putExtra(ExportUpdateService.EXTRA_SOURCE_FILE, mToBeExported.getFile());
        intent.putExtra(ExportUpdateService.EXTRA_DEST_URI, uri);
        startService(intent);
    }

    private void showToast(int stringId, int duration) {
        Toast.makeText(this, stringId, duration).show();
    }

    private void showPreferencesDialog() {
        Intent intent = new Intent(this, PreferencesActivity.class);
        startActivity(intent);
    }

    private void maybeShowWelcomeMessage() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        boolean alreadySeen = preferences.getBoolean(Constants.PREF_HAS_SEEN_WELCOME_MESSAGE, false);
        if (alreadySeen) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.welcome_title)
                .setMessage(R.string.welcome_message)
                .setPositiveButton(R.string.info_dialog_ok, (dialog, which) -> preferences.edit()
                        .putBoolean(Constants.PREF_HAS_SEEN_WELCOME_MESSAGE, true)
                        .apply())
                .show();
    }
}
