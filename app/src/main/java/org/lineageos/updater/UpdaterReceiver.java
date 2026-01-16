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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.PowerManager;

import androidx.preference.PreferenceManager;

import org.lineageos.updater.controller.UpdaterService;
import org.lineageos.updater.misc.Constants;
import org.lineageos.updater.misc.NotificationHelper;

public class UpdaterReceiver extends BroadcastReceiver {

    public static final String ACTION_INSTALL_REBOOT =
            "org.lineageos.updater.action.INSTALL_REBOOT";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_INSTALL_REBOOT.equals(intent.getAction())) {
            PowerManager pm = context.getSystemService(PowerManager.class);
            pm.reboot(null);
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);
            String downloadId = pref.getString(Constants.PREF_NEEDS_REBOOT_ID, null);
            pref.edit().remove(Constants.PREF_NEEDS_REBOOT_ID).apply();

            if (downloadId != null) {
                Intent cleanupIntent = new Intent(context, UpdaterService.class);
                cleanupIntent.setAction(UpdaterService.ACTION_POST_REBOOT_CLEANUP);
                cleanupIntent.putExtra(UpdaterService.EXTRA_DOWNLOAD_ID, downloadId);
                context.startService(cleanupIntent);
            }

            NotificationHelper.getInstance(context).checkAndNotifyUpdateFailedOnBoot();
        }
    }
}
