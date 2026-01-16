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
package org.lineageos.updater.controller;

import static org.lineageos.updater.misc.NotificationHelper.NOTIFICATION_ID_NEW_UPDATES;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.lineageos.updater.misc.Constants;
import org.lineageos.updater.misc.NotificationHelper;
import org.lineageos.updater.misc.Utils;
import org.lineageos.updater.model.Update;
import org.lineageos.updater.model.UpdateInfo;
import org.lineageos.updater.model.UpdateStatus;

import java.io.IOException;

public class UpdaterService extends Service {

    public static final String ACTION_DOWNLOAD_CONTROL = "action_download_control";
    public static final String EXTRA_DOWNLOAD_ID = "extra_download_id";
    public static final String EXTRA_DOWNLOAD_CONTROL = "extra_download_control";
    public static final String ACTION_INSTALL_UPDATE = "action_install_update";
    public static final String ACTION_INSTALL_STOP = "action_install_stop";
    public static final String ACTION_INSTALL_SUSPEND = "action_install_suspend";
    public static final String ACTION_INSTALL_RESUME = "action_install_resume";
    public static final String ACTION_POST_REBOOT_CLEANUP = "action_post_reboot_cleanup";
    public static final int DOWNLOAD_RESUME = 0;
    public static final int DOWNLOAD_PAUSE = 1;
    public static final int DOWNLOAD_CANCEL = 2;
    private static final String TAG = "UpdaterService";
    private final IBinder mBinder = new LocalBinder();
    private boolean mHasClients;

    private BroadcastReceiver mBroadcastReceiver;
    private NotificationHelper mNotificationHelper;

    private UpdaterController mUpdaterController;

    @Override
    public void onCreate() {
        super.onCreate();

        mUpdaterController = UpdaterController.getInstance(this);
        mNotificationHelper = NotificationHelper.getInstance(this);

        mBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID);
                String action = intent.getAction();

                if (UpdaterController.ACTION_UPDATE_STATUS.equals(action)) {
                    UpdateInfo update = mUpdaterController.getUpdate(downloadId);
                    handleUpdateStatusChange(update);

                } else if (UpdaterController.ACTION_DOWNLOAD_PROGRESS.equals(action)) {
                    UpdateInfo update = mUpdaterController.getUpdate(downloadId);
                    handleDownloadProgressChange(update);

                } else if (UpdaterController.ACTION_INSTALL_PROGRESS.equals(action)) {
                    UpdateInfo update = mUpdaterController.getUpdate(downloadId);
                    handleInstallProgress(update);

                } else if (UpdaterController.ACTION_UPDATE_REMOVED.equals(action)) {
                    if (!Update.LOCAL_ID.equals(downloadId) && downloadId != null) {
                        UpdateInfo update = mUpdaterController.getUpdate(downloadId);
                        if (update != null && update.getStatus() != UpdateStatus.INSTALLED) {
                            mNotificationHelper.cancel(update.getDownloadId().hashCode());
                        }
                    }
                }
            }
        };

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(UpdaterController.ACTION_DOWNLOAD_PROGRESS);
        intentFilter.addAction(UpdaterController.ACTION_INSTALL_PROGRESS);
        intentFilter.addAction(UpdaterController.ACTION_UPDATE_STATUS);
        intentFilter.addAction(UpdaterController.ACTION_UPDATE_REMOVED);
        registerReceiver(mBroadcastReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED);
    }


    @Override
    public void onDestroy() {
        unregisterReceiver(mBroadcastReceiver);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        mHasClients = true;
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        mHasClients = false;
        tryStopSelf();
        return false;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Starting service");

        if (intent == null || intent.getAction() == null) {
            if (ABUpdateInstaller.isInstallingUpdate(this)) {
                // The service is being restarted.
                ABUpdateInstaller.getInstance(this, mUpdaterController).reconnect();
            }
            return ABUpdateInstaller.isInstallingUpdate(this) ? START_STICKY : START_NOT_STICKY;
        }

        String action = intent.getAction();
        String downloadId = intent.getStringExtra(EXTRA_DOWNLOAD_ID);

        switch (action) {
            case ACTION_POST_REBOOT_CLEANUP:
                handlePostRebootCleanup(downloadId);
                tryStopSelf();
                break;

            case ACTION_DOWNLOAD_CONTROL:
                int controlAction = intent.getIntExtra(EXTRA_DOWNLOAD_CONTROL, -1);
                if (controlAction == DOWNLOAD_RESUME) {
                    mUpdaterController.resumeDownload(downloadId);
                } else if (controlAction == DOWNLOAD_PAUSE) {
                    mUpdaterController.pauseDownload(downloadId);
                } else if (controlAction == DOWNLOAD_CANCEL) {
                    mUpdaterController.cancelDownload(downloadId);
                } else {
                    Log.e(TAG, "Unknown download action");
                }
                break;

            case ACTION_INSTALL_UPDATE:
                handleInstallUpdate(downloadId);
                break;

            case ACTION_INSTALL_STOP:
                if (UpdateInstaller.isInstalling()) {
                    UpdateInstaller.getInstance(this, mUpdaterController).cancel();
                } else if (ABUpdateInstaller.isInstallingUpdate(this)) {
                    ABUpdateInstaller installer = ABUpdateInstaller.getInstance(this, mUpdaterController);
                    installer.reconnect();
                    installer.cancel();
                }
                break;

            case ACTION_INSTALL_SUSPEND:
                if (ABUpdateInstaller.isInstallingUpdate(this)) {
                    ABUpdateInstaller installer = ABUpdateInstaller.getInstance(this, mUpdaterController);
                    installer.reconnect();
                    installer.suspend();
                }
                break;

            case ACTION_INSTALL_RESUME:
                if (ABUpdateInstaller.isInstallingUpdateSuspended(this)) {
                    ABUpdateInstaller installer = ABUpdateInstaller.getInstance(this, mUpdaterController);
                    installer.reconnect();
                    installer.resume();
                }
                break;
        }

        return ABUpdateInstaller.isInstallingUpdate(this) ? START_STICKY : START_NOT_STICKY;
    }

    private void handleInstallUpdate(String downloadId) {
        UpdateInfo update = mUpdaterController.getUpdate(downloadId);
        if (update == null) {
            Log.e(TAG, "Update not found: " + downloadId);
            return;
        }
        if (update.getPersistentStatus() != UpdateStatus.Persistent.VERIFIED) {
            Log.e(TAG, downloadId + " is not verified, cannot install.");
            return;
        }
        try {
            if (Utils.isABUpdate(update.getFile())) {
                ABUpdateInstaller.getInstance(this, mUpdaterController).install(downloadId);
            } else {
                UpdateInstaller.getInstance(this, mUpdaterController).install(downloadId);
            }
        } catch (IOException e) {
            Log.e(TAG, "Could not install update", e);
            mUpdaterController.getActualUpdate(downloadId)
                    .setStatus(UpdateStatus.INSTALLATION_FAILED);
            mUpdaterController.notifyUpdateChange(downloadId);
        }
    }

    public UpdaterController getUpdaterController() {
        return mUpdaterController;
    }

    private void tryStopSelf() {
        if (!mHasClients && !mUpdaterController.hasActiveDownloads() &&
                !mUpdaterController.isInstallingUpdate()) {
            Log.d(TAG, "Service no longer needed, stopping");
            stopSelf();
        }
    }

    private void handleUpdateStatusChange(UpdateInfo update) {
        mNotificationHelper.cancel(NOTIFICATION_ID_NEW_UPDATES);

        switch (update.getStatus()) {
            case DELETED:
                stopForeground(STOP_FOREGROUND_REMOVE);
                mNotificationHelper.cancel(update.getDownloadId().hashCode());
                break;

            case INSTALLED:
                mNotificationHelper.cancel(update.getDownloadId().hashCode());
                mNotificationHelper.notifyRebootRequired();
                break;

            case INSTALLATION_CANCELLED:
                mNotificationHelper.cancel(update.getDownloadId().hashCode());
                break;

            default:
                mNotificationHelper.notifyUpdateStatus(update);
                break;
        }

        updateForegroundState();
        tryStopSelf();
    }

    private void handleDownloadProgressChange(UpdateInfo update) {
        if (update.getStatus() == UpdateStatus.DOWNLOADING) {
            mNotificationHelper.notifyDownloadProgress(update);
        }
    }

    private void handleInstallProgress(UpdateInfo update) {
        mNotificationHelper.notifyInstallProgress(update);
    }


    private void handlePostRebootCleanup(String downloadId) {
        if (downloadId == null) {
            return;
        }
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(this);
        boolean deleteUpdate = pref.getBoolean(Constants.PREF_AUTO_DELETE_UPDATES, false);
        // Always delete local updates
        boolean isLocal = Update.LOCAL_ID.equals(downloadId);
        if (deleteUpdate || isLocal) {
            mUpdaterController.deleteUpdate(downloadId);
        }
    }

    private void updateForegroundState() {
        for (UpdateInfo update : mUpdaterController.getUpdates()) {
            String downloadId = update.getDownloadId();
            UpdateStatus status = update.getStatus();
            boolean isForegroundState = status == UpdateStatus.STARTING ||
                    status == UpdateStatus.DOWNLOADING ||
                    status == UpdateStatus.VERIFYING ||
                    status == UpdateStatus.INSTALLING ||
                    status == UpdateStatus.INSTALLATION_SUSPENDED ||
                    status == UpdateStatus.FINALIZING;

            if (isForegroundState) {
                int notificationId = downloadId.hashCode();
                startForeground(notificationId, mNotificationHelper.buildOngoing(update),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                return;
            }
        }

        stopForeground(STOP_FOREGROUND_DETACH);
    }

    public class LocalBinder extends Binder {
        public UpdaterService getService() {
            return UpdaterService.this;
        }
    }
}
