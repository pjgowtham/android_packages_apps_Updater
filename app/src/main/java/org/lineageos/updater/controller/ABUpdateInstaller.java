/*
 * Copyright (C) 2017-2024 The LineageOS Project
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
package org.lineageos.updater.controller;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.ServiceSpecificException;
import android.os.UpdateEngine;
import android.os.UpdateEngineCallback;
import android.text.TextUtils;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.lineageos.updater.data.Update;
import org.lineageos.updater.data.UpdateStatus;
import org.lineageos.updater.data.UserPreferencesRepository;
import org.lineageos.updater.misc.Constants;
import org.lineageos.updater.util.BatteryMonitor;

import java.io.File;
import java.io.IOException;

class ABUpdateInstaller {

    private static final String TAG = "ABUpdateInstaller";

    private static final String PREF_INSTALLING_AB_ID = "installing_ab_id";
    private static final String PREF_INSTALLING_SUSPENDED_AB_ID = "installing_suspended_ab_id";

    private static ABUpdateInstaller sInstance = null;

    private final UpdaterController mUpdaterController;
    private final Context mContext;
    private String mDownloadId;

    private final UpdateEngine mUpdateEngine;
    private boolean mBound;

    private boolean mFinalizing;
    private int mProgress;
    private volatile boolean mCancelled;

    private final UpdateEngineCallback mUpdateEngineCallback = new UpdateEngineCallback() {

        @Override
        public void onStatusUpdate(int status, float percent) {
            Update update = mUpdaterController.getUpdate(mDownloadId);
            if (update == null) {
                // We read the id from a preference, the update could no longer exist
                installationDone(status == UpdateEngine.UpdateStatusConstants.UPDATED_NEED_REBOOT);
                return;
            }

            switch (status) {
                case UpdateEngine.UpdateStatusConstants.DOWNLOADING:
                case UpdateEngine.UpdateStatusConstants.FINALIZING: {
                    if (update.getStatus() != UpdateStatus.INSTALLING) {
                        update = update.withStatus(UpdateStatus.INSTALLING);
                        mUpdaterController.setUpdate(mDownloadId, update);
                        mUpdaterController.notifyUpdateChange(mDownloadId);
                    }
                    mProgress = Math.round(percent * 100);
                    mFinalizing = status == UpdateEngine.UpdateStatusConstants.FINALIZING;
                    update = update.toBuilder()
                            .setInstallProgress(mProgress)
                            .setFinalizing(mFinalizing)
                            .build();
                    mUpdaterController.setUpdate(mDownloadId, update);
                    mUpdaterController.notifyInstallProgress(mDownloadId);
                }
                break;

                case UpdateEngine.UpdateStatusConstants.UPDATED_NEED_REBOOT: {
                    installationDone(true);
                    update = update.toBuilder()
                            .setInstallProgress(0)
                            .setStatus(UpdateStatus.UPDATED_NEED_REBOOT)
                            .build();
                    mUpdaterController.setUpdate(mDownloadId, update);
                    mUpdaterController.notifyUpdateChange(mDownloadId);
                }
                break;

                case UpdateEngine.UpdateStatusConstants.IDLE: {
                    // The service was restarted because we thought we were installing an
                    // update, but we aren't, so clear everything.
                    installationDone(false);
                }
                break;
            }
        }

        @Override
        public void onPayloadApplicationComplete(int errorCode) {
            if (errorCode != UpdateEngine.ErrorCodeConstants.SUCCESS) {
                installationDone(false);
                Update update = mUpdaterController.getUpdate(mDownloadId);
                mUpdaterController.setUpdate(mDownloadId, update.toBuilder()
                        .setInstallProgress(0)
                        .setStatus(UpdateStatus.INSTALLATION_FAILED)
                        .build());
                mUpdaterController.notifyUpdateChange(mDownloadId);
            }
        }
    };

    static synchronized boolean isInstallingUpdate(Context context) {
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);
        return pref.getString(ABUpdateInstaller.PREF_INSTALLING_AB_ID, null) != null ||
                pref.getString(Constants.PREF_NEEDS_REBOOT_ID, null) != null;
    }

    static synchronized boolean isInstallingUpdate(Context context, String downloadId) {
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);
        return downloadId.equals(pref.getString(ABUpdateInstaller.PREF_INSTALLING_AB_ID, null)) ||
                TextUtils.equals(pref.getString(Constants.PREF_NEEDS_REBOOT_ID, null), downloadId);
    }

    static synchronized boolean isInstallingUpdateSuspended(Context context) {
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);
        return pref.getString(ABUpdateInstaller.PREF_INSTALLING_SUSPENDED_AB_ID, null) != null;
    }

    static synchronized boolean isWaitingForReboot(Context context, String downloadId) {
        String waitingId = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(Constants.PREF_NEEDS_REBOOT_ID, null);
        return TextUtils.equals(waitingId, downloadId);
    }

    private boolean shouldEnablePerformanceMode(boolean userPreferenceEnabled) {
        return BatteryMonitor.getInstance(mContext).getBatteryState().getValue().isAcCharging()
                || userPreferenceEnabled;
    }

    private void applyPerformanceMode(boolean userPreferenceEnabled) {
        try {
            mUpdateEngine.setPerformanceMode(shouldEnablePerformanceMode(userPreferenceEnabled));
        } catch (Throwable e) {
            Log.w(TAG, "Could not set performance mode", e);
        }
    }

    private ABUpdateInstaller(Context context, UpdaterController updaterController) {
        mUpdaterController = updaterController;
        mContext = context.getApplicationContext();
        mUpdateEngine = new UpdateEngine();
    }

    static synchronized ABUpdateInstaller getInstance(Context context,
            UpdaterController updaterController) {
        if (sInstance == null) {
            sInstance = new ABUpdateInstaller(context, updaterController);
        }
        return sInstance;
    }

    public void install(String downloadId) {
        if (isInstallingUpdate(mContext)) {
            Log.e(TAG, "Already installing an update");
            return;
        }

        mDownloadId = downloadId;
        mCancelled = false;

        File file = mUpdaterController.getUpdate(mDownloadId).getFile();
        install(file, downloadId);
    }

    public void installStreaming(String downloadId) {
        if (isInstallingUpdate(mContext)) {
            Log.e(TAG, "Already installing an update");
            return;
        }

        mDownloadId = downloadId;
        mCancelled = false;
        mProgress = 0;
        mFinalizing = false;

        Update update = mUpdaterController.getUpdate(mDownloadId);

        mUpdaterController.setUpdate(downloadId, update.toBuilder()
                .setInstallProgress(0)
                .setFinalizing(false)
                .setStatus(UpdateStatus.INSTALLING)
                .build());
        mUpdaterController.notifyUpdateChange(downloadId);

        PreferenceManager.getDefaultSharedPreferences(mContext).edit()
                .putString(PREF_INSTALLING_AB_ID, mDownloadId)
                .apply();

        new Thread(() -> {
            try {
                Payload payload = Payload.from(update.getDownloadUrl());
                if (mCancelled) {
                    return;
                }
                if (!bindUpdateEngine(downloadId)) {
                    return;
                }
                applyPerformanceMode(UserPreferencesRepository.getAbPerfModeBlocking(mContext));
                applyPayload(
                        payload.url,
                        payload.offset,
                        payload.size,
                        payload.headerKeyValuePairs,
                        downloadId);
            } catch (IOException | IllegalArgumentException | ServiceSpecificException e) {
                if (mCancelled) {
                    return;
                }
                Log.e(TAG, "Could not stream install " + update.getDownloadUrl(), e);
                setInstallationFailed(downloadId);
            }
        }).start();
    }

    public void install(File file, String downloadId) {
        if (!file.exists()) {
            Log.e(TAG, "The given update doesn't exist");
            Update update = mUpdaterController.getUpdate(downloadId);
            mUpdaterController.setUpdate(downloadId,
                    update.withStatus(UpdateStatus.INSTALLATION_FAILED));
            mUpdaterController.notifyUpdateChange(downloadId);
            return;
        }

        Payload payload;
        try {
            payload = Payload.from(file);
        } catch (IOException | IllegalArgumentException e) {
            Log.e(TAG, "Could not prepare " + file, e);
            Update update = mUpdaterController.getUpdate(downloadId);
            mUpdaterController.setUpdate(downloadId,
                    update.withStatus(UpdateStatus.INSTALLATION_FAILED));
            mUpdaterController.notifyUpdateChange(mDownloadId);
            return;
        }

        if (!bindUpdateEngine(downloadId)) {
            return;
        }

        applyPerformanceMode(UserPreferencesRepository.getAbPerfModeBlocking(mContext));

        applyPayload(
                payload.url,
                payload.offset,
                payload.size,
                payload.headerKeyValuePairs,
                downloadId);

    }

    private boolean bindUpdateEngine(String downloadId) {
        if (!mBound) {
            mBound = mUpdateEngine.bind(mUpdateEngineCallback);
            if (!mBound) {
                Log.e(TAG, "Could not bind");
                setInstallationFailed(downloadId);
                return false;
            }
        }
        return true;
    }

    private void applyPayload(String url, long offset, long size,
            String[] headerKeyValuePairs, String downloadId) {
        if (mCancelled) {
            return;
        }
        try {
            mUpdateEngine.applyPayload(url, offset, size, headerKeyValuePairs);
        } catch (ServiceSpecificException e) {
            if (e.errorCode == 66 /* kUpdateAlreadyInstalled */) {
                installationDone(true);
                Update update = mUpdaterController.getUpdate(downloadId);
                mUpdaterController.setUpdate(downloadId,
                        update.withStatus(UpdateStatus.UPDATED_NEED_REBOOT));
                mUpdaterController.notifyUpdateChange(downloadId);
                return;
            }
            throw e;
        }

        Update update = mUpdaterController.getUpdate(downloadId);
        mUpdaterController.setUpdate(downloadId,
                update.withStatus(UpdateStatus.INSTALLING));
        mUpdaterController.notifyUpdateChange(downloadId);

        PreferenceManager.getDefaultSharedPreferences(mContext).edit()
                .putString(PREF_INSTALLING_AB_ID, downloadId)
                .apply();
    }

    public void reconnect() {
        if (!isInstallingUpdate(mContext)) {
            Log.e(TAG, "reconnect: Not installing any update");
            return;
        }

        if (mBound) {
            return;
        }

        mDownloadId = PreferenceManager.getDefaultSharedPreferences(mContext)
                .getString(PREF_INSTALLING_AB_ID, null);

        // We will get a status notification as soon as we are connected
        mBound = mUpdateEngine.bind(mUpdateEngineCallback);
        if (!mBound) {
            Log.e(TAG, "Could not bind");
            return;
        }

        applyPerformanceMode(UserPreferencesRepository.getAbPerfModeBlocking(mContext));
    }

    private void installationDone(boolean needsReboot) {
        String id = needsReboot ? mDownloadId : null;
        PreferenceManager.getDefaultSharedPreferences(mContext).edit()
                .putString(Constants.PREF_NEEDS_REBOOT_ID, id)
                .remove(PREF_INSTALLING_AB_ID)
                .remove(PREF_INSTALLING_SUSPENDED_AB_ID)
                .apply();
    }

    private void setInstallationFailed(String downloadId) {
        installationDone(false);
        Update update = mUpdaterController.getUpdate(downloadId);
        mUpdaterController.setUpdate(downloadId, update.toBuilder()
                .setInstallProgress(0)
                .setFinalizing(false)
                .setStatus(UpdateStatus.INSTALLATION_FAILED)
                .build());
        mUpdaterController.notifyUpdateChange(downloadId);
    }

    public void cancel() {
        if (!isInstallingUpdate(mContext)) {
            Log.e(TAG, "cancel: Not installing any update");
            return;
        }

        mCancelled = true;

        if (!mBound) {
            Log.e(TAG, "Not connected to update engine");
            installationDone(false);
            Update update = mUpdaterController.getUpdate(mDownloadId);
            mUpdaterController.setUpdate(mDownloadId,
                    update.withStatus(UpdateStatus.INSTALLATION_CANCELLED));
            mUpdaterController.notifyUpdateChange(mDownloadId);
            return;
        }

        mUpdateEngine.cancel();
        installationDone(false);

        Update update = mUpdaterController.getUpdate(mDownloadId);
        mUpdaterController.setUpdate(mDownloadId,
                update.withStatus(UpdateStatus.INSTALLATION_CANCELLED));
        mUpdaterController.notifyUpdateChange(mDownloadId);

    }

    public void suspend() {
        if (!isInstallingUpdate(mContext)) {
            Log.e(TAG, "cancel: Not installing any update");
            return;
        }

        if (!mBound) {
            Log.e(TAG, "Not connected to update engine");
            return;
        }

        mUpdateEngine.suspend();

        Update update = mUpdaterController.getUpdate(mDownloadId);
        mUpdaterController.setUpdate(mDownloadId,
                update.withStatus(UpdateStatus.INSTALLATION_SUSPENDED));
        mUpdaterController.notifyUpdateChange(mDownloadId);

        PreferenceManager.getDefaultSharedPreferences(mContext).edit()
                .putString(PREF_INSTALLING_SUSPENDED_AB_ID, mDownloadId)
                .apply();

    }

    public void resume() {
        if (!isInstallingUpdateSuspended(mContext)) {
            Log.e(TAG, "cancel: No update is suspended");
            return;
        }

        if (!mBound) {
            Log.e(TAG, "Not connected to update engine");
            return;
        }

        mUpdateEngine.resume();

        Update update = mUpdaterController.getUpdate(mDownloadId);
        mUpdaterController.setUpdate(mDownloadId, update.toBuilder()
                .setStatus(UpdateStatus.INSTALLING)
                .setInstallProgress(mProgress)
                .setFinalizing(mFinalizing)
                .build());
        mUpdaterController.notifyUpdateChange(mDownloadId);
        mUpdaterController.notifyInstallProgress(mDownloadId);

        PreferenceManager.getDefaultSharedPreferences(mContext).edit()
                .remove(PREF_INSTALLING_SUSPENDED_AB_ID)
                .apply();

    }
}
