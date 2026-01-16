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

import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.text.SpannableString;
import android.text.format.Formatter;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import org.lineageos.updater.controller.UpdaterController;
import org.lineageos.updater.controller.UpdaterService;
import org.lineageos.updater.misc.Constants;
import org.lineageos.updater.misc.StringGenerator;
import org.lineageos.updater.misc.Utils;
import org.lineageos.updater.model.UpdateInfo;
import org.lineageos.updater.model.UpdateStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class UpdatesListAdapter extends RecyclerView.Adapter<UpdatesListAdapter.ViewHolder> {

    private static final String TAG = "UpdatesListAdapter";

    private static final int BATTERY_PLUGGED_ANY = BatteryManager.BATTERY_PLUGGED_AC
            | BatteryManager.BATTERY_PLUGGED_USB
            | BatteryManager.BATTERY_PLUGGED_WIRELESS;

    private final float mAlphaDisabledValue;

    private List<String> mDownloadIds;
    private String mSelectedDownload;
    private UpdaterController mUpdaterController;
    private final UpdatesActivity mActivity;

    private AlertDialog infoDialog;

    private enum Action {
        DOWNLOAD,
        PAUSE,
        RESUME,
        INSTALL,
        INFO,
        DELETE,
        CANCEL,
        CANCEL_INSTALLATION,
        REBOOT,
        SUSPEND_INSTALLATION,
        RESUME_INSTALLATION,
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        private final MaterialButton mAction;
        private final MaterialButton mCancel;
        private final ImageButton mDelete;
        private final ImageButton mCopyUrl;
        private final ImageButton mExport;

        private final TextView mBuildDate;
        private final TextView mBuildVersion;
        private final TextView mBuildSize;

        private final LinearProgressIndicator mProgressBar;
        private final TextView mProgressText;
        private final TextView mPercentage;

        public ViewHolder(final View view) {
            super(view);
            mAction = view.findViewById(R.id.update_action);
            mCancel = view.findViewById(R.id.cancel_action);
            mDelete = view.findViewById(R.id.button_delete_action);
            mCopyUrl = view.findViewById(R.id.button_copy_url);
            mExport = view.findViewById(R.id.button_export_update);

            mBuildDate = view.findViewById(R.id.build_date);
            mBuildVersion = view.findViewById(R.id.build_version);
            mBuildSize = view.findViewById(R.id.build_size);

            mProgressBar = view.findViewById(R.id.progress_indicator);
            mProgressText = view.findViewById(R.id.progress_text);
            mPercentage = view.findViewById(R.id.progress_percent);
        }
    }

    public UpdatesListAdapter(UpdatesActivity activity) {
        mActivity = activity;

        TypedValue tv = new TypedValue();
        mActivity.getTheme().resolveAttribute(android.R.attr.disabledAlpha, tv, true);
        mAlphaDisabledValue = tv.getFloat();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(ViewGroup viewGroup, int i) {
        View view = LayoutInflater.from(viewGroup.getContext())
                .inflate(R.layout.update_item_view, viewGroup, false);
        return new ViewHolder(view);
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull ViewHolder holder) {
        super.onViewDetachedFromWindow(holder);

        if (infoDialog != null) {
            infoDialog.dismiss();
        }
    }

    public void setUpdaterController(UpdaterController updaterController) {
        mUpdaterController = updaterController;
        notifyDataSetChanged();
    }

    private void handleActiveStatus(ViewHolder viewHolder, UpdateInfo update) {
        viewHolder.mAction.setVisibility(View.VISIBLE);
        viewHolder.mCancel.setVisibility(View.VISIBLE);
        
        final String downloadId = update.getDownloadId();
        if (mUpdaterController.isDownloading(downloadId)) {
            String downloaded = Formatter.formatShortFileSize(mActivity,
                    update.getFile().length());
            String total = Formatter.formatShortFileSize(mActivity, update.getFileSize());
            String percentage = NumberFormat.getPercentInstance().format(
                    update.getProgress() / 100.f);
            viewHolder.mPercentage.setText(percentage);
            long eta = update.getEta();
            if (eta > 0) {
                CharSequence etaString = StringGenerator.formatETA(mActivity, eta * 1000);
                viewHolder.mProgressText.setText(mActivity.getString(
                        R.string.list_download_progress_eta_newer, downloaded, total, etaString));
            } else {
                viewHolder.mProgressText.setText(mActivity.getString(
                        R.string.list_download_progress_newer, downloaded, total));
            }
            setButtonAction(viewHolder.mAction, Action.PAUSE, downloadId, true);
            setButtonAction(viewHolder.mCancel, Action.CANCEL, downloadId, true);
            viewHolder.mProgressBar.setIndeterminate(update.getStatus() == UpdateStatus.STARTING);
            viewHolder.mProgressBar.setProgress(update.getProgress());
        } else if (mUpdaterController.isInstallingUpdate(downloadId)
                && !mUpdaterController.isInstallingUpdateSuspended(downloadId)) {
            boolean notAB = !mUpdaterController.isInstallingABUpdate();
            if (notAB) {
                setButtonAction(viewHolder.mAction, Action.CANCEL_INSTALLATION, downloadId, true);
            } else {
                setButtonAction(viewHolder.mAction, Action.SUSPEND_INSTALLATION, downloadId, true);
            }
            setButtonAction(viewHolder.mCancel, Action.CANCEL_INSTALLATION, downloadId, true);

            viewHolder.mProgressText.setText(notAB ? R.string.dialog_prepare_zip_message :
                    update.getFinalizing() ?
                            R.string.finalizing_package :
                            R.string.preparing_ota_first_boot);
            String percentage = NumberFormat.getPercentInstance().format(
                    update.getInstallProgress() / 100.f);
            viewHolder.mPercentage.setText(percentage);
            viewHolder.mProgressBar.setIndeterminate(false);
            viewHolder.mProgressBar.setProgress(update.getInstallProgress());
        } else if (mUpdaterController.isVerifyingUpdate(downloadId)) {
            setButtonAction(viewHolder.mAction, Action.INSTALL, downloadId, false);
            setButtonAction(viewHolder.mCancel, Action.CANCEL, downloadId, false);
            viewHolder.mProgressText.setText(R.string.list_verifying_update);
            viewHolder.mProgressBar.setIndeterminate(true);
        } else {
            // Paused or Suspended
            setButtonAction(viewHolder.mAction, Action.RESUME, downloadId, true);

            if (update.getStatus() == UpdateStatus.INSTALLATION_SUSPENDED) {
                setButtonAction(viewHolder.mCancel, Action.CANCEL_INSTALLATION, downloadId, true);
                viewHolder.mProgressText.setText(R.string.installation_suspended_notification);
                String percentage = NumberFormat.getPercentInstance().format(
                        update.getInstallProgress() / 100.f);
                viewHolder.mPercentage.setText(percentage);
                viewHolder.mProgressBar.setIndeterminate(false);
                viewHolder.mProgressBar.setProgress(update.getInstallProgress());
            } else {
                // Paused download
                setButtonAction(viewHolder.mCancel, Action.CANCEL, downloadId, true);
                String downloaded = Formatter.formatShortFileSize(mActivity,
                        update.getFile().length());
                String total = Formatter.formatShortFileSize(mActivity, update.getFileSize());
                String percentage = NumberFormat.getPercentInstance().format(
                        update.getProgress() / 100.f);
                viewHolder.mPercentage.setText(percentage);
                viewHolder.mProgressText.setText(mActivity.getString(
                        R.string.list_download_progress_newer, downloaded, total));
                viewHolder.mProgressBar.setIndeterminate(false);
                viewHolder.mProgressBar.setProgress(update.getProgress());
            }
        }

        viewHolder.mPercentage.setVisibility(View.VISIBLE);
        viewHolder.mProgressText.setVisibility(View.VISIBLE);
        viewHolder.mBuildSize.setVisibility(View.INVISIBLE);
        viewHolder.mProgressBar.setVisibility(View.VISIBLE);

        viewHolder.mDelete.setVisibility(View.GONE);
        viewHolder.mCopyUrl.setVisibility(View.GONE);
        viewHolder.mExport.setVisibility(View.GONE);
    }

    private void handleNotActiveStatus(ViewHolder viewHolder, UpdateInfo update) {
        final String downloadId = update.getDownloadId();
        if (mUpdaterController.isWaitingForReboot(downloadId)) {
            setButtonAction(viewHolder.mAction, Action.REBOOT, downloadId, true);
        } else if (update.getPersistentStatus() == UpdateStatus.Persistent.VERIFIED) {
            setButtonAction(viewHolder.mAction,
                    Utils.canInstall(update) ? Action.INSTALL : Action.DELETE,
                    downloadId, !isBusy());
        } else if (!Utils.canInstall(update)) {
            setButtonAction(viewHolder.mAction, Action.INFO, downloadId, true);
        } else {
            setButtonAction(viewHolder.mAction, Action.DOWNLOAD, downloadId, !isBusy());
        }
        String fileSize = Formatter.formatShortFileSize(mActivity, update.getFileSize());
        viewHolder.mBuildSize.setText(fileSize);

        viewHolder.mPercentage.setVisibility(View.GONE);
        viewHolder.mProgressText.setVisibility(View.GONE);
        viewHolder.mBuildSize.setVisibility(View.VISIBLE);
        viewHolder.mProgressBar.setVisibility(View.GONE);

        viewHolder.mAction.setVisibility(View.VISIBLE);
        viewHolder.mCancel.setVisibility(View.GONE);

        boolean isVerified = update.getPersistentStatus() == UpdateStatus.Persistent.VERIFIED;

        // Delete action
        viewHolder.mDelete.setVisibility(isVerified ? View.VISIBLE : View.GONE);
        viewHolder.mDelete.setOnClickListener(v -> getDeleteDialog(downloadId).show());

        // Copy URL action
        viewHolder.mCopyUrl.setVisibility(update.getAvailableOnline() ? View.VISIBLE : View.GONE);
        viewHolder.mCopyUrl.setOnClickListener(v -> Utils.addToClipboard(mActivity,
                mActivity.getString(R.string.label_download_url),
                update.getDownloadUrl()));

        // Export update action
        viewHolder.mExport.setVisibility(isVerified ? View.VISIBLE : View.GONE);
        viewHolder.mExport.setOnClickListener(v -> {
            if (mActivity != null) {
                mActivity.exportUpdate(update);
            }
        });
    }

    @Override
    public void onBindViewHolder(@NonNull final ViewHolder viewHolder, int i) {
        if (mDownloadIds == null) {
            viewHolder.mAction.setEnabled(false);
            return;
        }

        final String downloadId = mDownloadIds.get(i);
        UpdateInfo update = mUpdaterController.getUpdate(downloadId);
        if (update == null) {
            // The update was deleted
            viewHolder.mAction.setEnabled(false);
            viewHolder.mAction.setText(R.string.action_download);
            return;
        }

        viewHolder.itemView.setSelected(downloadId.equals(mSelectedDownload));

        boolean activeLayout;
        switch (update.getPersistentStatus()) {
            case UpdateStatus.Persistent.UNKNOWN:
                activeLayout = update.getStatus() == UpdateStatus.STARTING
                        || update.getStatus() == UpdateStatus.PAUSED
                        || update.getStatus() == UpdateStatus.PAUSED_ERROR;
                break;
            case UpdateStatus.Persistent.VERIFIED:
                activeLayout = update.getStatus() == UpdateStatus.INSTALLING
                        || update.getStatus() == UpdateStatus.INSTALLATION_SUSPENDED;
                break;
            case UpdateStatus.Persistent.INCOMPLETE:
                activeLayout = true;
                break;
            default:
                throw new RuntimeException("Unknown update status");
        }

        String buildDate = StringGenerator.getDateLocalizedUTC(mActivity,
                DateFormat.LONG, update.getTimestamp());
        String buildVersion = mActivity.getString(R.string.list_build_version,
                update.getVersion());
        viewHolder.mBuildDate.setText(buildDate);
        viewHolder.mBuildVersion.setText(buildVersion);
        viewHolder.mBuildVersion.setCompoundDrawables(null, null, null, null);

        if (activeLayout) {
            handleActiveStatus(viewHolder, update);
        } else {
            handleNotActiveStatus(viewHolder, update);
        }
    }

    @Override
    public int getItemCount() {
        return mDownloadIds == null ? 0 : mDownloadIds.size();
    }

    public void setData(List<String> downloadIds) {
        mDownloadIds = downloadIds;
    }

    public void addItem(String downloadId) {
        if (mDownloadIds == null) {
            mDownloadIds = new ArrayList<>();
        }
        mDownloadIds.add(0, downloadId);
        notifyItemInserted(0);
    }

    public void notifyItemChanged(String downloadId) {
        if (mDownloadIds == null) {
            return;
        }
        notifyItemChanged(mDownloadIds.indexOf(downloadId));
    }

    public void removeItem(String downloadId) {
        if (mDownloadIds == null) {
            return;
        }
        int position = mDownloadIds.indexOf(downloadId);
        mDownloadIds.remove(downloadId);
        notifyItemRemoved(position);
        notifyItemRangeChanged(position, getItemCount());
    }

    private void startDownloadWithConfirmation(final String downloadId) {
        if (mUpdaterController.hasActiveDownloads()) {
            new AlertDialog.Builder(mActivity)
                    .setTitle(R.string.download_switch_confirm_title)
                    .setMessage(R.string.download_switch_confirm_message)
                    .setPositiveButton(android.R.string.ok,
                            (dialog, which) -> startDownloadWithWarning(downloadId))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } else {
            startDownloadWithWarning(downloadId);
        }
    }

    private void resumeDownloadWithConfirmation(final String downloadId) {
        if (mUpdaterController.hasActiveDownloads()) {
            new AlertDialog.Builder(mActivity)
                    .setTitle(R.string.download_switch_confirm_title)
                    .setMessage(R.string.download_switch_confirm_message)
                    .setPositiveButton(android.R.string.ok,
                            (dialog, which) -> resumeDownloadWithWarning(downloadId))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } else {
            resumeDownloadWithWarning(downloadId);
        }
    }

    private void resumeDownloadWithWarning(final String downloadId) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(mActivity);
        boolean warn = preferences.getBoolean(Constants.PREF_METERED_NETWORK_WARNING, true);
        if (!(Utils.isNetworkMetered(mActivity) && warn)) {
            mUpdaterController.resumeDownload(downloadId);
            return;
        }

        new AlertDialog.Builder(mActivity)
                .setTitle(R.string.update_over_metered_network_title)
                .setMessage(R.string.update_over_metered_network_message)
                .setPositiveButton(R.string.action_resume,
                        (dialog, which) -> mUpdaterController.resumeDownload(downloadId))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void startDownloadWithWarning(final String downloadId) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(mActivity);
        boolean warn = preferences.getBoolean(Constants.PREF_METERED_NETWORK_WARNING, true);
        if (!(Utils.isNetworkMetered(mActivity) && warn)) {
            mUpdaterController.startDownload(downloadId);
            return;
        }

        new AlertDialog.Builder(mActivity)
                .setTitle(R.string.update_over_metered_network_title)
                .setMessage(R.string.update_over_metered_network_message)
                .setPositiveButton(R.string.action_download,
                        (dialog, which) -> {
                            mUpdaterController.startDownload(downloadId);
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void setButtonAction(MaterialButton button, Action action, final String downloadId,
            boolean enabled) {
        final View.OnClickListener clickListener;
        switch (action) {
            case DOWNLOAD:
                button.setText(R.string.action_download);
                button.setEnabled(enabled);
                clickListener = enabled ? view -> startDownloadWithConfirmation(downloadId) : null;
                break;
            case PAUSE:
                button.setText(R.string.action_pause);
                button.setEnabled(enabled);
                clickListener = enabled ? view -> mUpdaterController.pauseDownload(downloadId)
                        : null;
                break;
            case RESUME: {
                button.setText(R.string.action_resume);
                button.setEnabled(enabled);
                UpdateInfo update = mUpdaterController.getUpdate(downloadId);
                
                // Handle both download resume and installation resume
                if (update.getStatus() == UpdateStatus.INSTALLATION_SUSPENDED) {
                    clickListener = enabled ? view -> {
                        Intent intent = new Intent(mActivity, UpdaterService.class);
                        intent.setAction(UpdaterService.ACTION_INSTALL_RESUME);
                        mActivity.startService(intent);
                    } : null;
                } else {
                    final boolean canInstall = Utils.canInstall(update) ||
                            update.getFile().length() == update.getFileSize();
                    clickListener = enabled ? view -> {
                        if (canInstall) {
                            resumeDownloadWithConfirmation(downloadId);
                        } else {
                            mActivity.showSnackbar(R.string.snack_update_not_installable,
                                    Snackbar.LENGTH_LONG);
                        }
                    } : null;
                }
            }
            break;
            case INSTALL: {
                button.setText(R.string.action_install);
                button.setEnabled(enabled);
                UpdateInfo update = mUpdaterController.getUpdate(downloadId);
                final boolean canInstall = Utils.canInstall(update);
                clickListener = enabled ? view -> {
                    if (canInstall) {
                        AlertDialog.Builder installDialog = getInstallDialog(downloadId);
                        if (installDialog != null) {
                            installDialog.show();
                        }
                    } else {
                        mActivity.showSnackbar(R.string.snack_update_not_installable,
                                Snackbar.LENGTH_LONG);
                    }
                } : null;
            }
            break;
            case INFO: {
                button.setText(R.string.action_info);
                button.setEnabled(enabled);
                clickListener = enabled ? view -> showInfoDialog() : null;
            }
            break;
            case DELETE: {
                button.setText(R.string.action_delete);
                button.setEnabled(enabled);
                clickListener = enabled ? view -> getDeleteDialog(downloadId).show() : null;
            }
            break;
            case CANCEL: {
                button.setText(android.R.string.cancel);
                button.setEnabled(enabled);
                clickListener = enabled ? view -> getCancelDownloadDialog(downloadId).show() : null;
            }
            break;
            case CANCEL_INSTALLATION: {
                button.setText(android.R.string.cancel);
                button.setEnabled(enabled);
                clickListener = enabled ? view -> getCancelInstallationDialog().show() : null;
            }
            break;
            case REBOOT: {
                button.setText(R.string.reboot);
                button.setEnabled(enabled);
                clickListener = enabled ? view -> {
                    PowerManager pm = mActivity.getSystemService(PowerManager.class);
                    pm.reboot(null);
                } : null;
            }
            break;
            case SUSPEND_INSTALLATION: {
                button.setText(R.string.action_pause);
                button.setEnabled(enabled);
                clickListener = enabled ? view -> {
                    Intent intent = new Intent(mActivity, UpdaterService.class);
                    intent.setAction(UpdaterService.ACTION_INSTALL_SUSPEND);
                    mActivity.startService(intent);
                } : null;
            }
            break;
            default:
                clickListener = null;
        }
        button.setAlpha(enabled ? 1.f : mAlphaDisabledValue);

        // Disable action mode when a button is clicked
        button.setOnClickListener(v -> {
            if (clickListener != null) {
                clickListener.onClick(v);
            }
        });
    }

    private boolean isBusy() {
        return mUpdaterController.hasActiveDownloads() || mUpdaterController.isVerifyingUpdate()
                || mUpdaterController.isInstallingUpdate();
    }

    private AlertDialog.Builder getDeleteDialog(final String downloadId) {
        return new AlertDialog.Builder(mActivity)
                .setTitle(R.string.confirm_delete_dialog_title)
                .setMessage(R.string.confirm_delete_dialog_message)
                .setPositiveButton(android.R.string.ok,
                        (dialog, which) -> {
                            mUpdaterController.pauseDownload(downloadId);
                            mUpdaterController.deleteUpdate(downloadId);
                        })
                .setNegativeButton(android.R.string.cancel, null);
    }

    private AlertDialog.Builder getCancelDownloadDialog(final String downloadId) {
        return new AlertDialog.Builder(mActivity)
                .setTitle(R.string.confirm_cancel_dialog_title)
                .setMessage(R.string.confirm_cancel_dialog_message)
                .setPositiveButton(android.R.string.ok,
                        (dialog, which) -> mUpdaterController.cancelDownload(downloadId))
                .setNegativeButton(android.R.string.cancel, null);
    }

    private AlertDialog.Builder getInstallDialog(final String downloadId) {
        if (!isBatteryLevelOk()) {
            Resources resources = mActivity.getResources();
            String message = resources.getString(R.string.dialog_battery_low_message_pct,
                    resources.getInteger(R.integer.battery_ok_percentage_discharging),
                    resources.getInteger(R.integer.battery_ok_percentage_charging));
            return new AlertDialog.Builder(mActivity)
                    .setTitle(R.string.dialog_battery_low_title)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null);
        }
        if (isScratchMounted()) {
            return new AlertDialog.Builder(mActivity)
                    .setTitle(R.string.dialog_scratch_mounted_title)
                    .setMessage(R.string.dialog_scratch_mounted_message)
                    .setPositiveButton(android.R.string.ok, null);
        }
        UpdateInfo update = mUpdaterController.getUpdate(downloadId);
        int resId;
        try {
            if (Utils.isABUpdate(update.getFile())) {
                resId = R.string.apply_update_dialog_message_ab;
            } else {
                resId = R.string.apply_update_dialog_message;
            }
        } catch (IOException e) {
            Log.e(TAG, "Could not determine the type of the update");
            return null;
        }

        String buildDate = StringGenerator.getDateLocalizedUTC(mActivity,
                DateFormat.MEDIUM, update.getTimestamp());
        String buildInfoText = mActivity.getString(R.string.list_build_version_date,
                update.getVersion(), buildDate);
        return new AlertDialog.Builder(mActivity)
                .setTitle(R.string.apply_update_dialog_title)
                .setMessage(mActivity.getString(resId, buildInfoText,
                        mActivity.getString(android.R.string.ok)))
                .setPositiveButton(android.R.string.ok,
                        (dialog, which) -> {
                            Utils.triggerUpdate(mActivity, downloadId);
                            maybeShowInfoDialog();
                        })
                .setNegativeButton(android.R.string.cancel, null);
    }

    private AlertDialog.Builder getCancelInstallationDialog() {
        return new AlertDialog.Builder(mActivity)
                .setMessage(R.string.cancel_installation_dialog_message)
                .setPositiveButton(android.R.string.ok,
                        (dialog, which) -> {
                            Intent intent = new Intent(mActivity, UpdaterService.class);
                            intent.setAction(UpdaterService.ACTION_INSTALL_STOP);
                            mActivity.startService(intent);
                        })
                .setNegativeButton(android.R.string.cancel, null);
    }

    private void maybeShowInfoDialog() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(mActivity);
        boolean alreadySeen = preferences.getBoolean(Constants.PREF_HAS_SEEN_INFO_DIALOG, false);
        if (alreadySeen) {
            return;
        }
        new AlertDialog.Builder(mActivity)
                .setTitle(R.string.info_dialog_title)
                .setMessage(R.string.info_dialog_message)
                .setPositiveButton(R.string.info_dialog_ok, (dialog, which) -> preferences.edit()
                        .putBoolean(Constants.PREF_HAS_SEEN_INFO_DIALOG, true)
                        .apply())
                .show();
    }

    private void showInfoDialog() {
        String messageString = String.format(StringGenerator.getCurrentLocale(mActivity),
                mActivity.getString(R.string.blocked_update_dialog_message),
                Utils.getUpgradeBlockedURL(mActivity));
        SpannableString message = new SpannableString(messageString);
        Linkify.addLinks(message, Linkify.WEB_URLS);
        if (infoDialog != null) {
            infoDialog.dismiss();
        }
        infoDialog = new AlertDialog.Builder(mActivity)
                .setTitle(R.string.blocked_update_dialog_title)
                .setPositiveButton(android.R.string.ok, null)
                .setMessage(message)
                .show();
        TextView textView = infoDialog.findViewById(android.R.id.message);
        if (textView != null) {
            textView.setMovementMethod(LinkMovementMethod.getInstance());
        }
    }

    private boolean isBatteryLevelOk() {
        Intent intent = mActivity.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (intent == null || !intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)) {
            return true;
        }
        int percent = Math.round(100.f * intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) /
                intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
        int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        int required = (plugged & BATTERY_PLUGGED_ANY) != 0 ?
                mActivity.getResources().getInteger(R.integer.battery_ok_percentage_charging) :
                mActivity.getResources().getInteger(R.integer.battery_ok_percentage_discharging);
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
