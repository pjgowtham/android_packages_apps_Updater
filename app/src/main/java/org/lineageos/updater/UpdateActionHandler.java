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

import org.lineageos.updater.model.UpdateInfo;

public interface UpdateActionHandler {
    void onStartDownload(String downloadId);
    void onResumeDownload(String downloadId);
    void onPauseDownload(String downloadId);
    void onCancelDownload(String downloadId);
    void onInstallUpdate(String downloadId);
    void onStreamInstallUpdate(String downloadId);
    void onSuspendInstallation();
    void onResumeInstallation();
    void onCancelInstallation();
    void onRebootDevice();
    void onDeleteUpdate(String downloadId);
    void onShowBlockedUpdateInfo();
    void onExportUpdate(UpdateInfo update);
    void onCopyDownloadUrl(String downloadId);
}
