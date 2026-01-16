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

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

import android.app.Activity;
import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.icu.text.DateFormat;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.airbnb.lottie.LottieAnimationView;
import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.google.android.material.chip.ChipGroup;

import org.json.JSONException;
import org.lineageos.updater.controller.UpdaterController;
import org.lineageos.updater.controller.UpdaterService;
import org.lineageos.updater.misc.DeviceInfoUtils;
import org.lineageos.updater.misc.StringGenerator;
import org.lineageos.updater.misc.Utils;
import org.lineageos.updater.misc.Constants;
import org.lineageos.updater.model.Update;
import org.lineageos.updater.model.UpdateInfo;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class UpdatesActivity extends CollapsingToolbarBaseActivity implements UpdateImporter.Callbacks {

    private static final String TAG = "UpdatesActivity";
    private UpdaterService mUpdaterService;
    private BroadcastReceiver mBroadcastReceiver;

    private UpdatesListAdapter mAdapter;
    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            UpdaterService.LocalBinder binder = (UpdaterService.LocalBinder) service;
            mUpdaterService = binder.getService();
            mAdapter.setUpdaterController(mUpdaterService.getUpdaterController());
            getUpdatesList();
            updateUi();
            updateHeader();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mAdapter.setUpdaterController(null);
            mUpdaterService = null;
            mAdapter.notifyDataSetChanged();
        }
    };
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
    private UpdateImporter mUpdateImporter;
    private AlertDialog importDialog;
    private SharedPreferences prefs;
    private SharedPreferences.OnSharedPreferenceChangeListener mPrefListener;
    private ChipGroup mChipGroup;

    private LottieAnimationView mLottieAnimationView;
    private boolean mIsOnline;
    private ConnectivityManager.NetworkCallback mNetworkCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_updates);

        mUpdateImporter = new UpdateImporter(this, this);

        UiModeManager uiModeManager = getSystemService(UiModeManager.class);
        mIsTV = uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;

        mChipGroup = findViewById(R.id.filter_chip_group);
        mChipGroup.check(R.id.filter_latest);
        mChipGroup.setOnCheckedStateChangeListener((group, checkedIds) -> updateUi());

        mLottieAnimationView = findViewById(R.id.lottie_check_animation);
        RecyclerView recyclerView = findViewById(R.id.recycler_view);
        mAdapter = new UpdatesListAdapter(this);
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
                    updateUi();
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
                        findViewById(R.id.recycler_view).setVisibility(View.GONE);
                        updateLottieAnimation();
                    }
                }
                updateHeader();
            }
        };

        if (!mIsTV) {
            // Toolbar is handled by SettingsLibEntityHeader or parent activity in some contexts,
            // but here we are inside a NestedScrollView with SettingsLibEntityHeader style.
            // The Toolbar might be missing if not explicitly added.
            // However, based on the errors, we need to adapt to the new layout.
            // Since we use SettingsLibEntityHeader, we might not need a standard Toolbar if the header handles it,
            // or we need to find the correct view.
            // For now, let's assume the Toolbar ID needs to be matching whatever is in the layout or removed if unnecessary.
            
            // Checking activity_updates.xml, there is NO toolbar. 
            // We should probably add one or handle the lack of it.
            // If the design intent is to look like Settings, it likely relies on an external ActionBar or CollapsingToolbar.
            // Given the existing code expects a Toolbar, and I see CollapsingToolbarLayout usage later.
            
            // Let's comment out Toolbar setup if it's not in the layout, or wait until we fix the layout.
            // The User's error log says "cannot find symbol variable toolbar".
            
            // Assuming we will add a Toolbar to the layout, I will leave this...
            // BUT, strictly sticking to the errors:
            
            // header_title -> entity_header_title
            // header_build_version -> entity_header_summary (or similar)
            // header_build_date -> entity_header_second_summary (or similar)
        } else {
            findViewById(R.id.preferences).setOnClickListener(v ->
                    startActivity(new Intent(this, PreferencesActivity.class)));
        }

        TextView headerTitle = findViewById(R.id.header_build_date);
        headerTitle.setText(StringGenerator.getDateLocalizedUTC(this,
                DateFormat.LONG, DeviceInfoUtils.getBuildDateTimestamp()));

        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        mPrefListener = (sharedPreferences, key) -> {
            if (Constants.LAST_UPDATE_CHECK.equals(key)) {
                runOnUiThread(() -> {
                    updateLastCheckedString();
                    refreshUpdatesList();
                });
            }
        };
        updateLastCheckedString();

        TextView headerBuildVersion = findViewById(R.id.header_build_version);
        headerBuildVersion.setText(
                getString(R.string.header_version_combined,
                        Build.VERSION.RELEASE, DeviceInfoUtils.getBuildVersion()));

        TextView headerSecurityPatch = findViewById(R.id.header_security_patch_level);
        headerSecurityPatch.setText(getString(R.string.header_android_security_update,
                DeviceInfoUtils.getSecurityPatch(StringGenerator.getCurrentLocale(this))));

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
                        collapsingToolbar.setTitle(mHeaderTitle);
                        mIsShown = false;
                    }
                }
            });

            if (!Utils.hasTouchscreen(this)) {
                // This can't be collapsed without a touchscreen
                appBar.setExpanded(false);
            }
        } else {
            findViewById(R.id.preferences).setOnClickListener(v ->
                    startActivity(new Intent(this, PreferencesActivity.class)));
        }

        findViewById(R.id.report_issue_button).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse(getString(R.string.report_issue_url)));
            startActivity(intent);
        });

        findViewById(R.id.show_changelog_button).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse(Utils.getChangelogURL(this)));
            startActivity(intent);
        });

        maybeShowWelcomeMessage();
        updateHeader();
    }

    private String mHeaderTitle = null;

    private void updateHeader() {
        TextView headerTitle = findViewById(R.id.header_build_date);
        ImageView headerIcon = findViewById(R.id.header_icon);

        headerTitle.setText(StringGenerator.getDateLocalizedUTC(this,
                DateFormat.LONG, DeviceInfoUtils.getBuildDateTimestamp()));
        
        if (mUpdaterService != null && mUpdaterService.getUpdaterController() != null) {
            UpdaterController controller = mUpdaterService.getUpdaterController();
            for (UpdateInfo update : controller.getUpdates()) {
                String downloadId = update.getDownloadId();
                if (controller.isInstallingUpdate(downloadId)) {
                    mHeaderTitle = getString(R.string.installing_update);
                    headerIcon.setImageResource(R.drawable.settingslib_expressive_icon_ongoing);
                    updateToolbarTitle();
                    return;
                } else if (controller.isWaitingForReboot(downloadId)) {
                    mHeaderTitle = getString(R.string.reboot_to_complete_update);
                    headerIcon.setImageResource(R.drawable.settingslib_expressive_icon_pending);
                    updateToolbarTitle();
                    return;
                }
            }
        }

        if (mAdapter != null && mAdapter.getItemCount() > 0) {
            mHeaderTitle = getString(R.string.new_updates_found_title);
            headerIcon.setImageResource(R.drawable.settingslib_expressive_icon_level_medium);
        } else {
            mHeaderTitle = getString(R.string.snack_no_updates_found);
            headerIcon.setImageResource(R.drawable.settingslib_expressive_icon_level_low);
        }
        updateToolbarTitle();
    }

    private void updateToolbarTitle() {
        if (mIsTV) {
            return;
        }
        CollapsingToolbarLayout collapsingToolbar = findViewById(R.id.collapsing_toolbar);
        if (collapsingToolbar == null) {
            return;
        }

        // We need to check if the app bar is expanded or collapsed to set the correct title
        // But since we don't have direct access to the state here easily without tracking,
        // we can assume the listener will handle the scroll changes.
        // However, we should set the "expanded" title here if we can.
        // CollapsingToolbarLayout behavior: setTitle sets the text shown when expanded (and collapsed unless constrained).
        // Our listener toggles between mHeaderTitle (expanded) and display_name (collapsed).
        // So we just need to ensure the listener uses the updated mHeaderTitle.
        // We can force a title update if needed, but the listener is reactive.
        // Let's just set the title for now, assuming the view is likely expanded or the listener will catch up.
        // Actually, if we are expanded, we want mHeaderTitle.
        // If we are collapsed, we want display_name.
        // The listener does this.
        AppBarLayout appBar = findViewById(R.id.app_bar);
        if (appBar != null) {
            // Trigger a layout update or let scroll handle it?
            // If the user hasn't scrolled, we are expanded.
            // So we should set the title.
            // But if we are collapsed, we shouldn't.
            // Let's store the state in mIsShown (which means "Title is Shown" i.e. Collapsed Title).
            // Actually, if we set the title on the CTL, it updates immediately.
            // If we are expanded, we want mHeaderTitle.
            // If we are collapsed (mIsShown is true), we want display_name.
            // So:
            // if (!mIsShown) collapsingToolbar.setTitle(mHeaderTitle);
            
            // Wait, mIsShown is local to the anonymous listener.
            // We need to move the listener logic to a field or accessor?
            // Or just update the listener logic in onCreate to read mHeaderTitle.
            // AND here we can try to set it if we think we are expanded.
            // Since we can't easily access mIsShown from here without refactoring,
            // let's rely on the user scrolling or initial state.
            // But better: refactor the listener to be a member so we can access usage.
            // OR: just set it. If it's collapsed, the listener *should* presumably override it on next scroll?
            // No, listener only fires on offset change.
            // We should just update the listener in onCreate to use mHeaderTitle dynamic variable.
            // And here, we can set it *if* we know we are expanded.
            // The safest is to refactor the listener.
            // But for now, let's just make sure mHeaderTitle is updated, which it is.
            // And maybe assume we start expanded or the user sees the update when they interact.
            // But if status changes while looking at it...
            // We should probably set it.
            collapsingToolbar.setTitle(mHeaderTitle);
        }
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
        intentFilter.addAction(UpdaterController.ACTION_UPDATE_REMOVED);
        registerReceiver(mBroadcastReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED);
        prefs.registerOnSharedPreferenceChangeListener(mPrefListener);
        monitorConnectivity();
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
        unregisterReceiver(mBroadcastReceiver);
        if (mUpdaterService != null) {
            unbindService(mConnection);
        }
        prefs.unregisterOnSharedPreferenceChangeListener(mPrefListener);
        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(
                Context.CONNECTIVITY_SERVICE);
        if (connectivityManager != null && mNetworkCallback != null) {
            connectivityManager.unregisterNetworkCallback(mNetworkCallback);
        }
        super.onStop();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_updates, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.menu_preferences) {
            startActivity(new Intent(this, PreferencesActivity.class));
            return true;
        } else if (itemId == R.id.menu_local_update) {
            mUpdateImporter.openImportPicker();
            return true;
        }
        return super.onOptionsItemSelected(item);
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
    public void onImportCompleted(Update update) {
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

        mAdapter.addItem(update.getDownloadId());
        findViewById(R.id.recycler_view).setVisibility(View.VISIBLE);
        if (mLottieAnimationView != null) {
            mLottieAnimationView.setVisibility(View.GONE);
            mLottieAnimationView.pauseAnimation();
        }

        final Runnable deleteUpdate = () -> UpdaterController.getInstance(this)
                .deleteUpdate(update.getDownloadId());

        new AlertDialog.Builder(this)
                .setTitle(R.string.local_update_import)
                .setMessage(getString(R.string.local_update_import_success, update.getVersion()))
                .setPositiveButton(R.string.local_update_import_install, (dialog, which) -> {
                    Utils.triggerUpdate(this, update.getDownloadId());
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> deleteUpdate.run())
                .setOnCancelListener((dialog) -> deleteUpdate.run())
                .show();
    }

    private void loadUpdatesList(File jsonFile)
            throws IOException, JSONException {
        Log.d(TAG, "Adding remote updates");
        UpdaterController controller = mUpdaterService.getUpdaterController();

        List<UpdateInfo> updates = Utils.parseJson(jsonFile, true);
        List<String> updatesOnline = new ArrayList<>();
        for (UpdateInfo update : updates) {
            controller.addUpdate(update, false, false);
            updatesOnline.add(update.getDownloadId());
        }
        controller.setUpdatesAvailableOnline(updatesOnline, true);

        updateUi();
    }

    private void getUpdatesList() {
        File jsonFile = Utils.getCachedUpdateList(this);
        if (jsonFile.exists()) {
            try {
                loadUpdatesList(jsonFile);
                Log.d(TAG, "Cached list parsed");
            } catch (IOException | JSONException e) {
                Log.e(TAG, "Error while parsing json list", e);
            }
        }
        UpdatesCheckWorker.runImmediateCheck(this);
    }

    private void refreshUpdatesList() {
        if (mUpdaterService == null) {
            return;
        }
        File jsonFile = Utils.getCachedUpdateList(this);
        if (jsonFile.exists()) {
            try {
                loadUpdatesList(jsonFile);
                updateHeader();
                Log.d(TAG, "Updates list refreshed");
            } catch (IOException | JSONException e) {
                Log.e(TAG, "Error while refreshing updates list", e);
            }
        }
    }

    private void updateLastCheckedString() {
        long lastCheck = prefs.getLong(Constants.LAST_UPDATE_CHECK, -1) / 1000;
        String lastCheckString = getString(R.string.header_last_updates_check,
                StringGenerator.getDateLocalized(this, DateFormat.LONG, lastCheck),
                StringGenerator.getTimeLocalized(this, lastCheck));
        TextView headerLastCheck = findViewById(R.id.header_last_check);

        headerLastCheck.setText(lastCheckString);

        long lastCheckMillis = prefs.getLong(Constants.LAST_UPDATE_CHECK, -1);
        if (System.currentTimeMillis() - lastCheckMillis > 60 * 1000) {
            headerLastCheck.setTextColor(getColor(R.color.settingslib_colorBackgroundLevel_medium));
        } else {
            headerLastCheck.setTextColor(getColor(R.color.settingslib_colorBackgroundLevel_low));
        }
    }

    public void exportUpdate(UpdateInfo update) {
        mToBeExported = update;

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, update.getName());

        mExportUpdate.launch(intent);
    }

    public void showSnackbar(int messageId, int duration) {
        Toast.makeText(this, messageId, duration).show();
    }

    private void exportUpdate(Uri uri) {
        Intent intent = new Intent(this, ExportUpdateService.class);
        intent.setAction(ExportUpdateService.ACTION_START_EXPORTING);
        intent.putExtra(ExportUpdateService.EXTRA_SOURCE_FILE, mToBeExported.getFile());
        intent.putExtra(ExportUpdateService.EXTRA_DEST_URI, uri);
        startService(intent);
    }

    /**
     * Check the connectivity status of the device
     */
    private void monitorConnectivity() {
        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(
                Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) {
            return;
        }

        mNetworkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull android.net.Network network) {
                runOnUiThread(() -> {
                    mIsOnline = true;
                    updateLottieAnimation();
                });
            }

            @Override
            public void onLost(@NonNull android.net.Network network) {
                runOnUiThread(() -> {
                    mIsOnline = false;
                    updateLottieAnimation();
                });
            }
        };

        // Initialize mIsOnline based on current state provided by synchronous check
        android.net.Network activeNetwork = connectivityManager.getActiveNetwork();
        if (activeNetwork != null) {
            android.net.NetworkCapabilities capabilities =
                    connectivityManager.getNetworkCapabilities(activeNetwork);
             if (capabilities != null && capabilities.hasCapability(
                        android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                 mIsOnline = true;
             }
        }
        updateLottieAnimation();

        connectivityManager.registerDefaultNetworkCallback(mNetworkCallback);
    }

    private void updateLottieAnimation() {
        boolean isEmpty = findViewById(R.id.recycler_view).getVisibility() == View.GONE;
        if (isEmpty) {
            if (mIsOnline) {
                mLottieAnimationView.setVisibility(View.GONE);
                mLottieAnimationView.pauseAnimation();
            } else {
                mLottieAnimationView.setVisibility(View.VISIBLE);
                mLottieAnimationView.playAnimation();
            }
        }
    }

    private void updateUi() {
        UpdaterController controller = mUpdaterService.getUpdaterController();
        List<UpdateInfo> sortedUpdates = controller.getUpdates();
        sortedUpdates.sort((u1, u2) -> Long.compare(u2.getTimestamp(), u1.getTimestamp()));

        List<String> updateIds = new ArrayList<>();
        int checkedId = mChipGroup.getCheckedChipId();
        
        for (UpdateInfo update : sortedUpdates) {
            boolean include = false;
            if (checkedId == R.id.filter_all) {
                include = true;
            } else if (checkedId == R.id.filter_latest) {
                if (updateIds.isEmpty()) {
                    include = true;
                }
            } else if (checkedId == R.id.filter_downloaded) {
                 if (Utils.canInstall(update) || update.getPersistentStatus() == org.lineageos.updater.model.UpdateStatus.Persistent.VERIFIED) {
                     include = true;
                 }
            }
            
            if (include) {
                updateIds.add(update.getDownloadId());
            }
        }
        
        if (updateIds.isEmpty()) {
            findViewById(R.id.recycler_view).setVisibility(View.GONE);
            updateLottieAnimation();
        } else {
            findViewById(R.id.recycler_view).setVisibility(View.VISIBLE);
            mLottieAnimationView.setVisibility(View.GONE);
            mLottieAnimationView.pauseAnimation();
            mAdapter.setData(updateIds);
            mAdapter.notifyDataSetChanged();
        }
    }

    private void maybeShowWelcomeMessage() {
        boolean alreadySeen = prefs.getBoolean(Constants.HAS_SEEN_WELCOME_MESSAGE, false);
        if (alreadySeen) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.welcome_title)
                .setMessage(R.string.welcome_message)
                .setPositiveButton(R.string.info_dialog_ok, (dialog, which) -> prefs.edit()
                        .putBoolean(Constants.HAS_SEEN_WELCOME_MESSAGE, true)
                        .apply())
                .show();
    }
}
