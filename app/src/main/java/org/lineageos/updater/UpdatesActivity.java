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
import android.content.res.Resources;
import android.icu.text.DateFormat;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.text.SpannableString;
import android.text.format.DateUtils;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;
import com.android.settingslib.utils.ColorUtil;
import com.google.android.material.appbar.CollapsingToolbarLayout;

import org.json.JSONException;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import static org.lineageos.updater.R.color.*;
import static org.lineageos.updater.R.drawable.*;

public class UpdatesActivity extends CollapsingToolbarBaseActivity
        implements UpdateImporter.Callbacks, UpdateActionHandler {

    private static final String TAG_CONTENT_FRAGMENT = "updates_content";

    private static final String TAG = "UpdatesActivity";

    private static final int BATTERY_PLUGGED_ANY = BatteryManager.BATTERY_PLUGGED_AC
            | BatteryManager.BATTERY_PLUGGED_USB
            | BatteryManager.BATTERY_PLUGGED_WIRELESS;

    private UpdaterService mUpdaterService;
    private BroadcastReceiver mBroadcastReceiver;

    private UpdatesListAdapter mAdapter;
    private List<String> mAllUpdateIds;

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
    private AlertDialog infoDialog;

    void openUpdateImportPicker() {
        mUpdateImporter.openImportPicker();
    }

    private boolean mIsCollapsed = false;
    private ImageView mStatusIcon;

    private View mHeaderInfoRow;
    private TextView mCollapsedBuildDate;

    private View mCheckUpdatesButton;
    private TextView mLastCheckDateView;
    private TextView mLastCheckTimeView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_updates);

        mUpdateImporter = new UpdateImporter(this, this);

        mAdapter = new UpdatesListAdapter(this, this);

        mBroadcastReceiver = new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                if (UpdaterController.ACTION_UPDATE_STATUS.equals(intent.getAction())) {
                    String downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID);
                    handleDownloadStatusChange(downloadId);
                    mAdapter.notifyItemChanged(downloadId);
                    updateHeaderIconAndTitle();
                } else if (UpdaterController.ACTION_DOWNLOAD_PROGRESS.equals(intent.getAction()) ||
                        UpdaterController.ACTION_INSTALL_PROGRESS.equals(intent.getAction())) {
                    String downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID);
                    mAdapter.notifyItemChanged(downloadId);
                } else if (UpdaterController.ACTION_UPDATE_REMOVED.equals(intent.getAction())) {
                    String downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID);
                    mAdapter.removeItem(downloadId);
                    updateHeaderIconAndTitle();
                }
            }
        };

        setTitle(R.string.display_name);

        setupStatusIcon();
        setupHeaderViews();

        Objects.requireNonNull(getAppBarLayout())
                .addOnOffsetChangedListener((appBarLayout, verticalOffset) -> {
                    int totalRange = appBarLayout.getTotalScrollRange();
                    mIsCollapsed = Math.abs(verticalOffset) == totalRange;
                    updateHeaderIconAndTitle();
                });

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(
                            R.id.updates_fragment_container,
                            new UpdatesContentFragment(),
                            TAG_CONTENT_FRAGMENT)
                    .commit();
        }

        registerHeaderViews(
                findViewById(R.id.check_updates_button),
                findViewById(R.id.last_check_date),
                findViewById(R.id.last_check_time));

        updateLastCheckedString();
        updateHeaderSummary();
        setupHeaderButtons();
    }

    private void setupStatusIcon() {
        mStatusIcon = new ImageView(this);
        int size = getResources().getDimensionPixelSize(R.dimen.toolbar_icon_size);
        CollapsingToolbarLayout.LayoutParams lp =
                new CollapsingToolbarLayout.LayoutParams(size, size);
        lp.gravity = Gravity.CENTER;
        lp.setCollapseMode(CollapsingToolbarLayout.LayoutParams.COLLAPSE_MODE_PIN);
        mStatusIcon.setVisibility(View.GONE);
        getCollapsingToolbarLayout().addView(mStatusIcon, lp);
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
        if (infoDialog != null) {
            infoDialog.dismiss();
            infoDialog = null;
        }
        super.onStop();
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

            icon.setImageResource(com.android.settingslib.widget.preference.banner.R.drawable.ic_warning);
            secondaryIcon.setVisibility(View.GONE);
            title.setText(R.string.check_internet_connection);
            summary.setVisibility(View.GONE);

            networkWarningCard.setVisibility(View.VISIBLE);
        }

        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
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

        mAdapter.showAll();
        mAdapter.addItem(update.getDownloadId());

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

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className,
                IBinder service) {
            UpdaterService.LocalBinder binder = (UpdaterService.LocalBinder) service;
            mUpdaterService = binder.getService();
            mAdapter.setUpdaterController(mUpdaterService.getUpdaterController());
            getUpdatesList();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mAdapter.setUpdaterController(null);
            mUpdaterService = null;
            mAdapter.notifyDataSetChanged();
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

        List<UpdateInfo> sortedUpdates = controller.getUpdates();
        if (!sortedUpdates.isEmpty()) {
            sortedUpdates.sort((u1, u2) -> Long.compare(u2.getTimestamp(), u1.getTimestamp()));
        }
        mAllUpdateIds = new ArrayList<>();
        for (UpdateInfo update : sortedUpdates) {
            mAllUpdateIds.add(update.getDownloadId());
        }
        updateAdapterData();
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
                    setCheckUpdatesButtonState(false);
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
                    setCheckUpdatesButtonState(false);
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

        setCheckUpdatesButtonState(true);
        downloadClient.start();
    }

    private void updateLastCheckedString() {
        final SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(this);
        long lastCheckMillis = preferences.getLong(Constants.PREF_LAST_UPDATE_CHECK, -1);
        long lastCheckSeconds = lastCheckMillis / 1000;

        if (mLastCheckDateView == null || mLastCheckTimeView == null) {
            return;
        }

        boolean isCheckToday = DateUtils.isToday(lastCheckMillis);

        if (!isCheckToday) {
            mLastCheckDateView.setText(StringGenerator.getDateLocalized(this, DateFormat.MEDIUM, lastCheckSeconds));
        }

        mLastCheckDateView.setVisibility(isCheckToday ? View.GONE : View.VISIBLE);
        mLastCheckTimeView.setText(StringGenerator.getTimeLocalized(this, lastCheckSeconds));
    }

    public void registerHeaderViews(View checkUpdatesButton, TextView dateView, TextView timeView) {
        mCheckUpdatesButton = checkUpdatesButton;
        mLastCheckDateView = dateView;
        mLastCheckTimeView = timeView;

        if (mCheckUpdatesButton != null) {
            mCheckUpdatesButton.setOnClickListener(v -> downloadUpdatesList(true));
        }

        updateLastCheckedString();
    }

    public UpdatesListAdapter getAdapter() {
        return mAdapter;
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
    }

    private void setupHeaderButtons() {
        View changelogButton = findViewById(R.id.show_changelog_button);
        if (changelogButton != null) {
            changelogButton.setOnClickListener(v -> {
                Intent intent = new Intent(Intent.ACTION_VIEW,
                        Uri.parse(getString(R.string.menu_changelog_url, DeviceInfoUtils.getDevice())));
                startActivity(intent);
            });
        }

        View reportButton = findViewById(R.id.report_issue_button);
        if (reportButton != null) {
            reportButton.setOnClickListener(v -> {
                Intent intent = new Intent(Intent.ACTION_VIEW,
                        Uri.parse(getString(R.string.report_issue_url)));
                startActivity(intent);
            });
        }
    }

    private void setupHeaderViews() {
        mHeaderInfoRow = findViewById(R.id.header_info_row);
        mCollapsedBuildDate = findViewById(R.id.header_build_date_collapsed);
    }

    private void updateAdapterData() {
        if (mAllUpdateIds == null) {
            return;
        }
        mAdapter.setData(mAllUpdateIds);
        mAdapter.notifyDataSetChanged();
    }

    private void updateHeaderSummary() {
        TextView headerAndroidVersion = findViewById(R.id.header_android_version);
        headerAndroidVersion.setText(getString(R.string.header_android_version,
                Build.VERSION.RELEASE));

        TextView headerLineageVersion = findViewById(R.id.header_brand_version_large);
        headerLineageVersion.setText(DeviceInfoUtils.getBuildVersion());

        TextView headerBuildVersion = findViewById(R.id.header_build_version);
        headerBuildVersion.setText(DeviceInfoUtils.getBuildVersion());

        TextView headerBuildDate = findViewById(R.id.header_build_date);
        String buildDateText = new SimpleDateFormat("MMM d", StringGenerator.getCurrentLocale(this))
                .format(new java.util.Date(DeviceInfoUtils.getBuildDateTimestamp() * 1000));
        headerBuildDate.setText(buildDateText);

        TextView headerSecurityPatch = findViewById(R.id.header_security_patch_level);
        headerSecurityPatch.setText(DeviceInfoUtils.getSecurityPatchShort(
                StringGenerator.getCurrentLocale(this)));

        if (mCollapsedBuildDate != null) {
            mCollapsedBuildDate.setText(buildDateText);
        }
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
            iconRes = settingslib_expressive_icon_level_low;
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
                iconRes = settingslib_expressive_icon_level_medium;
                titleText = getString(R.string.new_updates_found_title);
            }
        }

        CollapsingToolbarLayout collapsingToolbar = getCollapsingToolbarLayout();
        if (collapsingToolbar != null) {
            collapsingToolbar.setTitle(mIsCollapsed ? "" : titleText);
        } else {
            setTitle(titleText);
        }

        if (mStatusIcon != null) {
            mStatusIcon.setImageResource(iconRes);
            mStatusIcon.setVisibility(mIsCollapsed ? View.VISIBLE : View.GONE);
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

    public void showToast(int stringId, int duration) {
        Toast.makeText(this, stringId, duration).show();
    }

    private void setCheckUpdatesButtonState(boolean busy) {
        if (mCheckUpdatesButton != null) {
            mCheckUpdatesButton.setEnabled(!busy);
            mCheckUpdatesButton.setAlpha(busy ? ColorUtil.getDisabledAlpha(this) : 1f);
        }
    }

    // ---- UpdateActionHandler implementation ----

    private UpdaterController getController() {
        return mUpdaterService != null ? mUpdaterService.getUpdaterController() : null;
    }

    @Override
    public void onStartDownload(String downloadId) {
        UpdaterController controller = getController();
        if (controller == null) return;
        if (controller.hasActiveDownloads()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.download_switch_confirm_title)
                    .setMessage(R.string.download_switch_confirm_message)
                    .setPositiveButton(android.R.string.ok,
                            (dialog, which) -> downloadWithWarning(downloadId, false))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } else {
            downloadWithWarning(downloadId, false);
        }
    }

    @Override
    public void onResumeDownload(String downloadId) {
        UpdaterController controller = getController();
        if (controller == null) return;
        UpdateInfo update = controller.getUpdate(downloadId);
        long fileLength = update.getFile() != null ? update.getFile().length() : 0;
        boolean canInstall = Utils.canInstall(update) || fileLength == update.getFileSize();
        if (!canInstall) {
            showToast(R.string.snack_update_not_installable, Toast.LENGTH_LONG);
            return;
        }
        if (controller.hasActiveDownloads()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.download_switch_confirm_title)
                    .setMessage(R.string.download_switch_confirm_message)
                    .setPositiveButton(android.R.string.ok,
                            (dialog, which) -> downloadWithWarning(downloadId, true))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } else {
            downloadWithWarning(downloadId, true);
        }
    }

    private void downloadWithWarning(String downloadId, boolean isResume) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        boolean warn = preferences.getBoolean(Constants.PREF_METERED_NETWORK_WARNING, true);
        UpdaterController controller = getController();
        if (controller == null) return;
        if (!(Utils.isNetworkMetered(this) && warn)) {
            if (isResume) {
                controller.resumeDownload(downloadId);
            } else {
                controller.startDownload(downloadId);
            }
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.update_over_metered_network_title)
                .setMessage(R.string.update_over_metered_network_message)
                .setPositiveButton(isResume ? R.string.action_resume : R.string.action_download,
                        (dialog, which) -> {
                            if (isResume) {
                                controller.resumeDownload(downloadId);
                            } else {
                                controller.startDownload(downloadId);
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public void onPauseDownload(String downloadId) {
        UpdaterController controller = getController();
        if (controller != null) {
            controller.pauseDownload(downloadId);
        }
    }

    @Override
    public void onCancelDownload(String downloadId) {
        UpdaterController controller = getController();
        if (controller == null) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.confirm_cancel_dialog_title)
                .setMessage(R.string.confirm_cancel_dialog_message)
                .setPositiveButton(android.R.string.ok,
                        (dialog, which) -> controller.cancelDownload(downloadId))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public void onInstallUpdate(String downloadId) {
        UpdaterController controller = getController();
        if (controller == null) return;
        UpdateInfo update = controller.getUpdate(downloadId);
        if (!Utils.canInstall(update)) {
            showToast(R.string.snack_update_not_installable, Toast.LENGTH_LONG);
            return;
        }

        AlertDialog.Builder blockingDialog = getPreInstallBlockingDialog();
        if (blockingDialog != null) {
            blockingDialog.show();
            return;
        }

        int resId;
        try {
            if (Utils.isABUpdate(update.getFile())) {
                resId = R.string.apply_update_dialog_message_ab;
            } else {
                resId = R.string.apply_update_dialog_message;
            }
        } catch (IOException e) {
            Log.e(TAG, "Could not determine the type of the update");
            return;
        }

        String buildDate = StringGenerator.getDateLocalizedUTC(this,
                java.text.DateFormat.MEDIUM, update.getTimestamp());
        String buildInfoText = getString(R.string.list_build_version_date,
                update.getVersion(), buildDate);
        new AlertDialog.Builder(this)
                .setTitle(R.string.apply_update_dialog_title)
                .setMessage(getString(resId, buildInfoText,
                        getString(android.R.string.ok)))
                .setPositiveButton(android.R.string.ok,
                        (dialog, which) -> Utils.triggerUpdate(this, downloadId))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public void onStreamInstallUpdate(String downloadId) {
        UpdaterController controller = getController();
        if (controller == null) return;
        UpdateInfo update = controller.getUpdate(downloadId);
        if (!Utils.canInstall(update)) {
            showToast(R.string.snack_update_not_installable, Toast.LENGTH_LONG);
            return;
        }

        AlertDialog.Builder blockingDialog = getPreInstallBlockingDialog();
        if (blockingDialog != null) {
            blockingDialog.show();
            return;
        }

        String buildDate = StringGenerator.getDateLocalizedUTC(this,
                java.text.DateFormat.MEDIUM, update.getTimestamp());
        String buildInfoText = getString(R.string.list_build_version_date,
                update.getVersion(), buildDate);
        new AlertDialog.Builder(this)
                .setTitle(R.string.apply_update_dialog_title)
                .setMessage(getString(R.string.apply_update_dialog_message_streaming,
                        buildInfoText, getString(android.R.string.ok)))
                .setPositiveButton(android.R.string.ok,
                        (dialog, which) -> Utils.triggerStreamingUpdate(this, downloadId))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public void onSuspendInstallation() {
        Intent intent = new Intent(this, UpdaterService.class);
        intent.setAction(UpdaterService.ACTION_INSTALL_SUSPEND);
        startService(intent);
    }

    @Override
    public void onResumeInstallation() {
        Intent intent = new Intent(this, UpdaterService.class);
        intent.setAction(UpdaterService.ACTION_INSTALL_RESUME);
        startService(intent);
    }

    @Override
    public void onCancelInstallation() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.cancel_installation_dialog_title)
                .setMessage(R.string.cancel_installation_dialog_message)
                .setPositiveButton(android.R.string.ok,
                        (dialog, which) -> {
                            Intent intent = new Intent(this, UpdaterService.class);
                            intent.setAction(UpdaterService.ACTION_INSTALL_STOP);
                            startService(intent);
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public void onRebootDevice() {
        PowerManager pm = getSystemService(PowerManager.class);
        pm.reboot(null);
    }

    @Override
    public void onDeleteUpdate(String downloadId) {
        UpdaterController controller = getController();
        if (controller == null) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.confirm_delete_dialog_title)
                .setMessage(R.string.confirm_delete_dialog_message)
                .setPositiveButton(android.R.string.ok,
                        (dialog, which) -> {
                            controller.pauseDownload(downloadId);
                            controller.deleteUpdate(downloadId);
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public void onShowBlockedUpdateInfo() {
        String messageString = String.format(StringGenerator.getCurrentLocale(this),
                getString(R.string.blocked_update_dialog_message),
                Utils.getUpgradeBlockedURL(this));
        SpannableString message = new SpannableString(messageString);
        Linkify.addLinks(message, Linkify.WEB_URLS);
        if (infoDialog != null) {
            infoDialog.dismiss();
        }
        infoDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.blocked_update_dialog_title)
                .setPositiveButton(android.R.string.ok, null)
                .setMessage(message)
                .show();
        TextView textView = infoDialog.findViewById(android.R.id.message);
        if (textView != null) {
            textView.setMovementMethod(LinkMovementMethod.getInstance());
        }
    }

    @Override
    public void onExportUpdate(UpdateInfo update) {
        exportUpdate(update);
    }

    @Override
    public void onCopyDownloadUrl(String downloadId) {
        UpdaterController controller = getController();
        if (controller == null) return;
        UpdateInfo update = controller.getUpdate(downloadId);
        if (update != null) {
            Utils.addToClipboard(this,
                    getString(R.string.label_download_url),
                    update.getDownloadUrl());
        }
    }

    // ---- Helper methods for pre-install checks ----

    private AlertDialog.Builder getPreInstallBlockingDialog() {
        if (!isBatteryLevelOk()) {
            Resources resources = getResources();
            String message = resources.getString(R.string.dialog_battery_low_message_pct,
                    resources.getInteger(R.integer.battery_ok_percentage_discharging),
                    resources.getInteger(R.integer.battery_ok_percentage_charging));
            return new AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_battery_low_title)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null);
        }
        if (isScratchMounted()) {
            return new AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_scratch_mounted_title)
                    .setMessage(R.string.dialog_scratch_mounted_message)
                    .setPositiveButton(android.R.string.ok, null);
        }
        return null;
    }

    private boolean isBatteryLevelOk() {
        Intent intent = registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (intent == null || !intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)) {
            return true;
        }
        int percent = Math.round(100.f * intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) /
                intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
        int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        int required = (plugged & BATTERY_PLUGGED_ANY) != 0 ?
                getResources().getInteger(R.integer.battery_ok_percentage_charging) :
                getResources().getInteger(R.integer.battery_ok_percentage_discharging);
        return percent >= required;
    }

    private static boolean isScratchMounted() {
        try (Stream<String> lines = Files.lines(Path.of("/proc/mounts"))) {
            return lines.anyMatch(x -> x.split(" ")[1].equals("/mnt/scratch"));
        } catch (IOException e) {
            return false;
        }
    }
}
