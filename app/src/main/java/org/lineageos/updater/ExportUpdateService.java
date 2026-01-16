/*
 * Copyright (C) 2017-2022 The LineageOS Project
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

import android.app.Service;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import org.lineageos.updater.misc.FileUtils;
import org.lineageos.updater.misc.NotificationHelper;

import java.io.File;
import java.io.IOException;

public class ExportUpdateService extends Service {

    private static final String TAG = "ExportUpdateService";

    public static final String ACTION_START_EXPORTING = "start_exporting";

    public static final String EXTRA_SOURCE_FILE = "source_file";
    public static final String EXTRA_DEST_URI = "dest_uri";

    private volatile boolean mIsExporting = false;

    private Thread mExportThread;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (ACTION_START_EXPORTING.equals(intent.getAction())) {
            if (mIsExporting) {
                Log.e(TAG, "Already exporting an update");
                Toast.makeText(this, R.string.toast_already_exporting, Toast.LENGTH_SHORT).show();
                return START_NOT_STICKY;
            }
            mIsExporting = true;
            File source = (File) intent.getSerializableExtra(EXTRA_SOURCE_FILE);
            Uri destination = intent.getParcelableExtra(EXTRA_DEST_URI);
            startExporting(source, destination);
            Toast.makeText(this, R.string.toast_export_started, Toast.LENGTH_SHORT).show();
        } else {
            Log.e(TAG, "No action specified");
        }

        if (!mIsExporting) {
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private class ExportRunnable implements Runnable {
        private final ContentResolver mContentResolver;
        private final File mSource;
        private final Uri mDestination;
        private final FileUtils.ProgressCallBack mProgressCallBack;
        private final Runnable mRunnableComplete;
        private final Runnable mRunnableFailed;

        private ExportRunnable(ContentResolver cr, File source, Uri destination,
                               FileUtils.ProgressCallBack progressCallBack,
                               Runnable runnableComplete, Runnable runnableFailed) {
            mContentResolver = cr;
            mSource = source;
            mDestination = destination;
            mProgressCallBack = progressCallBack;
            mRunnableComplete = runnableComplete;
            mRunnableFailed = runnableFailed;
        }

        @Override
        public void run() {
            try {
                FileUtils.copyFile(mContentResolver, mSource, mDestination, mProgressCallBack);
                mIsExporting = false;
                if (!mExportThread.isInterrupted()) {
                    Log.d(TAG, "Completed");
                    mRunnableComplete.run();
                } else {
                    Log.d(TAG, "Aborted");
                }
            } catch (IOException e) {
                mIsExporting = false;
                Log.e(TAG, "Could not copy file", e);
                mRunnableFailed.run();
            } finally {
                stopSelf();
            }
        }
    }

    private void startExporting(File source, Uri destination) {
        final String fileName = FileUtils.queryName(getContentResolver(), destination);
        NotificationHelper notificationHelper = NotificationHelper.getInstance(this);

        if (fileName == null) {
            Log.e(TAG, "Failed to resolve file name from URI: " + destination);
            notificationHelper.notifyExportFinished(null);
            stopSelf();
            return;
        }

        FileUtils.ProgressCallBack progressCallBack = new FileUtils.ProgressCallBack() {
            private long mLastUpdate = -1;

            @Override
            public void update(int progress) {
                long now = SystemClock.elapsedRealtime();
                if (mLastUpdate < 0 || now - mLastUpdate > 500) {
                    notificationHelper.notifyExportProgress(progress, fileName);
                    mLastUpdate = now;
                }
            }
        };

        startForeground(NotificationHelper.NOTIFICATION_ID_EXPORT,
                notificationHelper.buildExportStart(fileName),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);

        Runnable runnableComplete = () -> {
            notificationHelper.notifyExportFinished(fileName);
            stopForeground(STOP_FOREGROUND_DETACH);
        };

        Runnable runnableFailed = () -> {
            notificationHelper.notifyExportFinished(null);
            stopForeground(STOP_FOREGROUND_DETACH);
        };

        ExportRunnable exportRunnable = new ExportRunnable(getContentResolver(), source,
                destination, progressCallBack, runnableComplete, runnableFailed);
        mExportThread = new Thread(exportRunnable);
        mExportThread.start();
    }
}
