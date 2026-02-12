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
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.icu.text.DateFormat;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.text.format.DateUtils;

import androidx.annotation.NonNull;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;

import com.google.android.material.appbar.CollapsingToolbarLayout;

import org.json.JSONException;

import com.android.settingslib.utils.ColorUtil;

import org.lineageos.updater.controller.UpdaterController;
import org.lineageos.updater.controller.UpdaterService;
import org.lineageos.updater.download.DownloadClient;
import org.lineageos.updater.misc.Constants;
import org.lineageos.updater.misc.DeviceInfoUtils;
import org.lineageos.updater.misc.StringGenerator;
import org.lineageos.updater.misc.Utils;
import org.lineageos.updater.model.Update;
import org.lineageos.updater.model.UpdateInfo;
import org.lineageos.updater.model.UpdateStatus;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class UpdatesActivity extends UpdatesListActivity implements UpdateImporter.Callbacks {

    private static final String TAG = "UpdatesActivity";
    private UpdaterService mUpdaterService;
    private BroadcastReceiver mBroadcastReceiver;

    private ConnectivityManager mConnectivityManager;
    private ConnectivityManager.NetworkCallback mNetworkCallback;
    private boolean mIsCheckingForUpdates = false;

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

    private boolean mIsCollapsed = false;
    private ImageView mStatusIcon;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_updates);

        mUpdateImporter = new UpdateImporter(this, this);

        mBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (UpdaterController.ACTION_UPDATE_STATUS.equals(intent.getAction())) {
                    String downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID);
                    handleDownloadStatusChange(downloadId);
                    getUpdatesFragment().notifyUpdateChanged(downloadId);
                    updateHeaderIconAndTitle();
                } else if (UpdaterController.ACTION_DOWNLOAD_PROGRESS.equals(intent.getAction()) ||
                        UpdaterController.ACTION_INSTALL_PROGRESS.equals(intent.getAction())) {
                    String downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID);
                    getUpdatesFragment().notifyUpdateChanged(downloadId);
                } else if (UpdaterController.ACTION_UPDATE_REMOVED.equals(intent.getAction())) {
                    String downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID);
                    getUpdatesFragment().removeUpdate(downloadId);
                    updateHeaderIconAndTitle();
                }
            }
        };

        updateLastCheckedString();
        updateHeaderSummary();
        setTitle(R.string.display_name);

        setupHeaderButtons();
        setupCheckUpdatesButton();
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.updates_list_container, new UpdatesFragment())
                    .replace(R.id.quick_actions_container, new QuickActionsFragment())
                    .commit();
        }
        maybeShowWelcomeMessage();
        Objects.requireNonNull(getAppBarLayout()).post(() ->
                getAppBarLayout().setExpanded(true, false));

        setupStatusIcon();
        Objects.requireNonNull(getAppBarLayout())
                .addOnOffsetChangedListener((appBarLayout, verticalOffset) -> {
                    mIsCollapsed = Math.abs(verticalOffset) == appBarLayout.getTotalScrollRange();
                    updateHeaderIconAndTitle();
        });
    }

    private UpdatesFragment getUpdatesFragment() {
        return (UpdatesFragment) getSupportFragmentManager()
                .findFragmentById(R.id.updates_list_container);
    }

    private void setupStatusIcon() {
        CollapsingToolbarLayout collapsingToolbar = getCollapsingToolbarLayout();
        if (collapsingToolbar == null) {
            return;
        }

        mStatusIcon = new ImageView(this);
        int size = getResources().getDimensionPixelSize(R.dimen.toolbar_icon_size);
        CollapsingToolbarLayout.LayoutParams lp =
                new CollapsingToolbarLayout.LayoutParams(size, size);
        lp.gravity = Gravity.CENTER;
        lp.setCollapseMode(CollapsingToolbarLayout.LayoutParams.COLLAPSE_MODE_PIN);
        mStatusIcon.setVisibility(View.GONE);
        collapsingToolbar.addView(mStatusIcon, lp);
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

        registerNetworkCallback();
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
        unregisterNetworkCallback();
        if (mUpdaterService != null) {
            unbindService(mConnection);
        }
        super.onStop();
    }

    public void openLocalUpdatePicker() {
        mUpdateImporter.openImportPicker();
    }

    private void registerNetworkCallback() {
        if (mNetworkCallback != null) {
            return;
        }

        mConnectivityManager = getSystemService(ConnectivityManager.class);
        if (mConnectivityManager != null) {
            mNetworkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    runOnUiThread(() -> updateNetworkState(isNetworkValidated()));
                }

                @Override
                public void onLost(@NonNull Network network) {
                    runOnUiThread(() -> updateNetworkState(isNetworkValidated()));
                }

                @Override
                public void onCapabilitiesChanged(@NonNull Network network,
                                                  @NonNull NetworkCapabilities capabilities) {
                    runOnUiThread(() -> {
                        boolean isNetworkValidated =
                                capabilities.hasCapability(
                                        NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                                        capabilities.hasCapability(
                                                NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                        updateNetworkState(isNetworkValidated);
                        if (isNetworkValidated) {
                            downloadUpdatesList(false);
                        }
                    });
                }
            };
            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    .build();
            mConnectivityManager.registerNetworkCallback(request, mNetworkCallback);
            updateNetworkState(isNetworkValidated());
        }
    }

    private void unregisterNetworkCallback() {
        if (mConnectivityManager != null && mNetworkCallback != null) {
            try {
                mConnectivityManager.unregisterNetworkCallback(mNetworkCallback);
            } catch (IllegalArgumentException e) {
                // Callback was not registered
            }
            mNetworkCallback = null;
        }
    }

    private boolean isNetworkValidated() {
        if (mConnectivityManager == null) {
            return false;
        }
        Network activeNetwork = mConnectivityManager.getActiveNetwork();
        NetworkCapabilities networkCapabilities =
                mConnectivityManager.getNetworkCapabilities(activeNetwork);
        return networkCapabilities != null &&
                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private void updateNetworkState(boolean isNetworkValidated) {
        View networkWarningCard = findViewById(R.id.network_warning_card);
        if (isNetworkValidated) {
            networkWarningCard.setVisibility(View.GONE);
        } else {
            ImageView icon = networkWarningCard.findViewById(android.R.id.icon);
            ImageView secondaryIcon = networkWarningCard.findViewById(android.R.id.icon1);
            TextView title = networkWarningCard.findViewById(android.R.id.title);
            TextView summary = networkWarningCard.findViewById(android.R.id.summary);

            icon.setImageResource(R.drawable.ic_warning);
            secondaryIcon.setVisibility(View.GONE);
            title.setText(R.string.check_internet_connection);
            summary.setVisibility(View.GONE);

            networkWarningCard.setVisibility(View.VISIBLE);
        }

        getUpdatesFragment().refreshAll();
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

        final Runnable deleteUpdate = () -> UpdaterController.getInstance(this)
                .deleteUpdate(update.getDownloadId());

        new AlertDialog.Builder(this)
                .setTitle(R.string.local_update_import)
                .setMessage(getString(R.string.local_update_import_success, update.getVersion()))
                .setPositiveButton(R.string.local_update_import_install, (dialog, which) -> {
                    getUpdatesFragment().refreshAll();
                    getUpdatesList();
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
            getUpdatesFragment().setUpdaterController(
                    mUpdaterService.getUpdaterController());
            getUpdatesList();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            getUpdatesFragment().setUpdaterController(null);
            mUpdaterService = null;
        }
    };

    private void loadUpdatesList(File jsonFile, boolean manualRefresh)
            throws IOException, JSONException {
        Log.d(TAG, "Adding remote updates");
        UpdaterController controller = mUpdaterService.getUpdaterController();
        boolean newUpdates = false;

        List<UpdateInfo> updates = Utils.parseJson(jsonFile, true);
        List<String> updatesOnline = new ArrayList<>();
        for (UpdateInfo update : updates) {
            newUpdates |= controller.addUpdate(update);
            updatesOnline.add(update.getDownloadId());
        }
        controller.setUpdatesAvailableOnline(updatesOnline, true);

        if (manualRefresh) {
            showToast(
                    newUpdates ? R.string.snack_updates_found : R.string.snack_no_updates_found,
                    Toast.LENGTH_SHORT);
        }

        getUpdatesFragment().refreshAll();
        updateHeaderIconAndTitle();
    }

    private void getUpdatesList() {
        File jsonFile = Utils.getCachedUpdateList(this);
        if (jsonFile.exists()) {
            try {
                loadUpdatesList(jsonFile, false);
                Log.d(TAG, "Cached list parsed");
            } catch (IOException | JSONException e) {
                Log.e(TAG, "Error while parsing json list", e);
            }
        }
        if (isNetworkValidated()) {
            downloadUpdatesList(false);
        }
    }

    private void processNewJson(File json, File jsonNew, boolean manualRefresh) {
        try {
            loadUpdatesList(jsonNew, manualRefresh);
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
            long millis = System.currentTimeMillis();
            preferences.edit().putLong(Constants.PREF_LAST_UPDATE_CHECK, millis).apply();
            updateLastCheckedString();
            if (json.exists() &&
                    preferences.getBoolean(Constants.PREF_PERIODIC_CHECK_ENABLED, true) &&
                    Utils.checkForNewUpdates(json, jsonNew)) {
                UpdatesCheckReceiver.updateRepeatingUpdatesCheck(this);
            }
            // In case we set a one-shot check because of a previous failure
            UpdatesCheckReceiver.cancelUpdatesCheck(this);
            //noinspection ResultOfMethodCallIgnored
            jsonNew.renameTo(json);
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Could not read json", e);
            showToast(R.string.snack_updates_check_failed, Toast.LENGTH_LONG);
        }
    }

    private void downloadUpdatesList(final boolean manualRefresh) {
        if (mIsCheckingForUpdates) {
            return;
        }
        mIsCheckingForUpdates = true;
        final File jsonFile = Utils.getCachedUpdateList(this);
        final File jsonFileTmp = new File(jsonFile.getAbsolutePath() + UUID.randomUUID());
        String url = Utils.getServerURL(this);
        Log.d(TAG, "Checking " + url);

        DownloadClient.DownloadCallback callback = new DownloadClient.DownloadCallback() {
            @Override
            public void onFailure(final boolean cancelled) {
                Log.e(TAG, "Could not download updates list");
                runOnUiThread(() -> {
                    mIsCheckingForUpdates = false;
                    if (!cancelled) {
                        showToast(R.string.snack_updates_check_failed, Toast.LENGTH_LONG);
                    }
                    setRefreshActionState(false);
                });
            }

            @Override
            public void onResponse(DownloadClient.Headers headers) {
            }

            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    mIsCheckingForUpdates = false;
                    Log.d(TAG, "List downloaded");
                    processNewJson(jsonFile, jsonFileTmp, manualRefresh);
                    setRefreshActionState(false);
                });
            }
        };

        final DownloadClient downloadClient;
        try {
            downloadClient = new DownloadClient.Builder()
                    .setUrl(url)
                    .setDestination(jsonFileTmp)
                    .setDownloadCallback(callback)
                    .build();
        } catch (IOException exception) {
            Log.e(TAG, "Could not build download client");
            mIsCheckingForUpdates = false;
            showToast(R.string.snack_updates_check_failed, Toast.LENGTH_LONG);
            return;
        }

        setRefreshActionState(true);
        downloadClient.start();
    }

    private void updateLastCheckedString() {
        final SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(this);
        long lastCheck = preferences.getLong(Constants.PREF_LAST_UPDATE_CHECK, -1) / 1000;
        TextView lastCheckText = findViewById(R.id.last_check_text);

        String timeStr = StringGenerator.getTimeLocalized(this, lastCheck);
        if (DateUtils.isToday(lastCheck * 1000)) {
            lastCheckText.setText(getString(R.string.header_last_updates_check_today, timeStr));
        } else {
            String dateStr = StringGenerator.getDateLocalized(this, DateFormat.MEDIUM, lastCheck);
            lastCheckText.setText(getString(R.string.header_last_updates_check, dateStr, timeStr));
        }
    }

    private void setupHeaderButtons() {

        findViewById(R.id.show_changelog_button).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse(getString(R.string.menu_changelog_url, DeviceInfoUtils.getDevice())));
            startActivity(intent);
        });

        findViewById(R.id.report_issue_button).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse(getString(R.string.report_issue_url)));
            startActivity(intent);
        });
    }

    private void setupCheckUpdatesButton() {
        findViewById(R.id.check_updates_button).setOnClickListener(v -> downloadUpdatesList(true));
    }

    private void updateHeaderSummary() {
        TextView headerAndroidVersion = findViewById(R.id.header_android_version);
        headerAndroidVersion.setText(getString(R.string.header_android_version,
                Build.VERSION.RELEASE));

        TextView headerBrandVersion = findViewById(R.id.header_brand_version);
        headerBrandVersion.setText(DeviceInfoUtils.getBuildVersion());

        TextView headerBrandVersionLarge = findViewById(R.id.header_brand_version_large);
        headerBrandVersionLarge.setText(DeviceInfoUtils.getBuildVersion());

        TextView headerBuildDate = findViewById(R.id.header_build_date);
        headerBuildDate.setText(DeviceInfoUtils.getBuildDateMonthDate());

        TextView headerSecurityPatch = findViewById(R.id.header_security_patch_level);
        headerSecurityPatch.setText(DeviceInfoUtils.getSecurityPatchLevelMonthYear());
    }

    private void updateHeaderIconAndTitle() {
        if (mUpdaterService == null) {
            return;
        }

        UpdaterController controller = mUpdaterService.getUpdaterController();
        List<UpdateInfo> updates = controller.getUpdates();

        int iconRes;
        String titleText;

        if (updates.isEmpty()) {
            iconRes = R.drawable.settingslib_expressive_icon_level_low;
            titleText = getString(R.string.snack_no_updates_found);
        } else {
            boolean isInstalling = false;
            boolean isPendingReboot = false;

            for (UpdateInfo update : updates) {
                if (update.getStatus() == UpdateStatus.INSTALLED) {
                    isPendingReboot = true;
                    break;
                }
                if (update.getStatus() == UpdateStatus.INSTALLING ||
                        update.getStatus() == UpdateStatus.INSTALLATION_SUSPENDED) {
                    isInstalling = true;
                }
            }

            if (isPendingReboot) {
                iconRes = R.drawable.settingslib_expressive_icon_pending;
                titleText = getString(R.string.reboot_to_complete_update);
            } else if (isInstalling) {
                iconRes = R.drawable.settingslib_expressive_icon_ongoing;
                titleText = getString(R.string.installing_update);
            } else {
                iconRes = R.drawable.settingslib_expressive_icon_level_medium;
                titleText = getString(R.string.new_updates_found_title);
            }
        }

        if (mStatusIcon != null) {
            mStatusIcon.setImageResource(iconRes);
            if (mIsCollapsed) {
                mStatusIcon.setVisibility(View.VISIBLE);
                setTitle("");
            } else {
                mStatusIcon.setVisibility(View.GONE);
                setTitle(titleText);
            }
        } else {
            setTitle(titleText);
        }

    }

    private void handleDownloadStatusChange(String downloadId) {
        if (Update.LOCAL_ID.equals(downloadId)) {
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

    @Override
    public void exportUpdate(UpdateInfo update) {
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

    @Override
    public void showToast(int stringId, int duration) {
        Toast.makeText(this, stringId, duration).show();
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

    private void setRefreshActionState(boolean busy) {
        View refreshButton = findViewById(R.id.check_updates_button);
        if (refreshButton != null) {
            refreshButton.setEnabled(!busy);
            refreshButton.setAlpha(busy ? ColorUtil.getDisabledAlpha(this) : 1f);
        }
    }
}
