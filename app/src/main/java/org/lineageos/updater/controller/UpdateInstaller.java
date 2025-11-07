/*
 * Copyright (C) 2017-2025 The LineageOS Project
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
import android.os.FileUtils;
import android.os.SystemProperties;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.lineageos.updater.misc.Constants;
import org.lineageos.updater.misc.Utils;
import org.lineageos.updater.model.UpdateInfo;
import org.lineageos.updater.model.UpdateStatus;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Set;

/**
 * Handles installing updates and preparing uncrypt copies if required.
 * Uses android.os.FileUtils for file copy operations without progress tracking.
 */
class UpdateInstaller {

    private static final String TAG = "UpdateInstaller";

    private static UpdateInstaller sInstance;
    private static String sInstallingUpdate;

    private Thread mPrepareUpdateThread;
    private volatile boolean mCanCancel;

    private final Context mContext;
    private final UpdaterController mUpdaterController;

    private UpdateInstaller(Context context, UpdaterController controller) {
        mContext = context.getApplicationContext();
        mUpdaterController = controller;
    }

    static synchronized UpdateInstaller getInstance(Context context, UpdaterController controller) {
        if (sInstance == null) {
            sInstance = new UpdateInstaller(context, controller);
        }
        return sInstance;
    }

    static synchronized boolean isInstalling() {
        return sInstallingUpdate != null;
    }

    static synchronized boolean isInstalling(String downloadId) {
        return sInstallingUpdate != null && sInstallingUpdate.equals(downloadId);
    }

    void install(String downloadId) {
        if (isInstalling()) {
            Log.e(TAG, "Already installing an update");
            return;
        }

        UpdateInfo update = mUpdaterController.getUpdate(downloadId);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        long buildTimestamp = SystemProperties.getLong(Constants.PROP_BUILD_DATE, 0);
        long lastBuildTimestamp = prefs.getLong(Constants.PREF_INSTALL_OLD_TIMESTAMP, buildTimestamp);
        boolean isReinstalling = buildTimestamp == lastBuildTimestamp;

        prefs.edit()
                .putLong(Constants.PREF_INSTALL_OLD_TIMESTAMP, buildTimestamp)
                .putLong(Constants.PREF_INSTALL_NEW_TIMESTAMP, update.getTimestamp())
                .putString(Constants.PREF_INSTALL_PACKAGE_PATH, update.getFile().getAbsolutePath())
                .putBoolean(Constants.PREF_INSTALL_AGAIN, isReinstalling)
                .putBoolean(Constants.PREF_INSTALL_NOTIFIED, false)
                .apply();

        if (Utils.isEncrypted(mContext, update.getFile())) {
            prepareForUncryptAndInstall(update);
        } else {
            installPackage(update.getFile(), downloadId);
        }
    }

    private void installPackage(File update, String downloadId) {
        try {
            android.os.RecoverySystem.installPackage(mContext, update);
        } catch (IOException e) {
            Log.e(TAG, "Could not install update", e);
            mUpdaterController.getActualUpdate(downloadId)
                    .setStatus(UpdateStatus.INSTALLATION_FAILED);
            mUpdaterController.notifyUpdateChange(downloadId);
        }
    }

    /**
     * Creates an unencrypted copy of the update package and installs it.
     */
    private synchronized void prepareForUncryptAndInstall(UpdateInfo update) {
        String uncryptPath = update.getFile().getAbsolutePath() + Constants.UNCRYPT_FILE_EXT;
        File uncryptFile = new File(uncryptPath);

        Runnable copyRunnable = () -> {
            try {
                mCanCancel = true;

                try (FileInputStream in = new FileInputStream(update.getFile());
                     FileOutputStream out = new FileOutputStream(uncryptFile)) {
                    FileUtils.copy(in, out);
                }

                // Set permissions
                try {
                    Set<PosixFilePermission> perms = new HashSet<>();
                    perms.add(PosixFilePermission.OWNER_READ);
                    perms.add(PosixFilePermission.OWNER_WRITE);
                    perms.add(PosixFilePermission.GROUP_READ);
                    perms.add(PosixFilePermission.OTHERS_READ);
                    Files.setPosixFilePermissions(uncryptFile.toPath(), perms);
                } catch (IOException ignored) {
                }

                mCanCancel = false;
                if (mPrepareUpdateThread.isInterrupted()) {
                    mUpdaterController.getActualUpdate(update.getDownloadId())
                            .setStatus(UpdateStatus.INSTALLATION_CANCELLED);
                    if (!uncryptFile.delete()) {
                        Log.w(TAG, "Failed to delete uncrypt file: " + uncryptFile);
                    }

                } else {
                    installPackage(uncryptFile, update.getDownloadId());
                }

            } catch (IOException e) {
                Log.e(TAG, "Failed to copy update for uncrypt install", e);
                if (!uncryptFile.delete()) {
                    Log.w(TAG, "Failed to delete uncrypt file: " + uncryptFile);
                }

                mUpdaterController.getActualUpdate(update.getDownloadId())
                        .setStatus(UpdateStatus.INSTALLATION_FAILED);
            } finally {
                synchronized (UpdateInstaller.this) {
                    mCanCancel = false;
                    mPrepareUpdateThread = null;
                    sInstallingUpdate = null;
                }
                mUpdaterController.notifyUpdateChange(update.getDownloadId());
            }
        };

        mPrepareUpdateThread = new Thread(copyRunnable);
        mPrepareUpdateThread.start();
        sInstallingUpdate = update.getDownloadId();
        mCanCancel = false;

        mUpdaterController.getActualUpdate(update.getDownloadId())
                .setStatus(UpdateStatus.INSTALLING);
        mUpdaterController.notifyUpdateChange(update.getDownloadId());
    }

    public synchronized void cancel() {
        if (!mCanCancel) {
            Log.d(TAG, "Nothing to cancel");
            return;
        }
        mPrepareUpdateThread.interrupt();
    }
}
