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

import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.android.settingslib.utils.ColorUtil;
import com.android.settingslib.widget.DrawableStateLinearLayout;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import org.lineageos.updater.controller.UpdaterController;
import org.lineageos.updater.misc.DeviceInfoUtils;
import org.lineageos.updater.misc.StringGenerator;
import org.lineageos.updater.misc.Utils;
import org.lineageos.updater.model.UpdateInfo;
import org.lineageos.updater.model.UpdateStatus;

import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;

public class UpdatesListAdapter extends RecyclerView.Adapter<UpdatesListAdapter.ViewHolder> {

    private static final int DEFAULT_MAX_VISIBLE = 1;

    private final float mAlphaDisabledValue;
    private final Context mContext;
    private final UpdateActionHandler mActionHandler;

    private List<String> mDownloadIds;
    private String mSelectedDownload;
    private String mExpandedDownloadId;
    private int mMaxVisibleCount = DEFAULT_MAX_VISIBLE;
    private UpdaterController mUpdaterController;
    private OnVisibilityChangeListener mVisibilityChangeListener;

    public interface OnVisibilityChangeListener {
        void onVisibilityChanged();
    }

    private enum Action {
        DOWNLOAD,
        PAUSE,
        RESUME,
        INSTALL,
        INFO,
        DELETE,
        CANCEL_INSTALLATION,
        REBOOT,
        CANCEL,
        SUSPEND_INSTALLATION,
        RESUME_INSTALLATION,
        STREAM_INSTALL,
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        private final Button mAction;
        private final Button mCancel;
        private final ImageButton mDeleteAction;
        private final ImageButton mCopyUrl;
        private final ImageButton mExportUpdate;

        private final ImageView mUpdateIcon;
        private final TextView mBuildDate;
        private final TextView mBuildVersion;

        private final View mDetailContainer;
        private final TextView mDetailSize;
        private final TextView mDetailAndroid;
        private final TextView mDetailSecurity;

        private final View mProgressContainer;
        private final LinearProgressIndicator mProgressBar;
        private final TextView mProgressText;
        private final TextView mPercentage;

        private final View mActionsContainer;
        private final ImageView mExpandIcon;

        public ViewHolder(final View view) {
            super(view);
            mAction = view.findViewById(R.id.update_action);
            mCancel = view.findViewById(R.id.cancel_action);
            mDeleteAction = view.findViewById(R.id.button_delete_action);
            mCopyUrl = view.findViewById(R.id.button_copy_url);
            mExportUpdate = view.findViewById(R.id.button_export_update);

            mUpdateIcon = view.findViewById(R.id.update_icon);
            mBuildDate = view.findViewById(R.id.build_date);
            mBuildVersion = view.findViewById(R.id.build_version);

            mDetailContainer = view.findViewById(R.id.detail_container);
            mDetailSize = view.findViewById(R.id.detail_size);
            mDetailAndroid = view.findViewById(R.id.detail_android);
            mDetailSecurity = view.findViewById(R.id.detail_security);

            mProgressContainer = view.findViewById(R.id.progress_container);
            mProgressBar = view.findViewById(R.id.progress_indicator);
            mProgressText = view.findViewById(R.id.progress_text);
            mPercentage = view.findViewById(R.id.progress_percent);

            mActionsContainer = view.findViewById(R.id.actions_container);
            mExpandIcon = view.findViewById(R.id.expand_icon);
        }
    }

    public UpdatesListAdapter(Context context, UpdateActionHandler actionHandler) {
        mContext = context;
        mActionHandler = actionHandler;
        mAlphaDisabledValue = ColorUtil.getDisabledAlpha(mContext);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(ViewGroup viewGroup, int i) {
        View view = LayoutInflater.from(viewGroup.getContext())
                .inflate(R.layout.update_item_view, viewGroup, false);
        return new ViewHolder(view);
    }

    public void setUpdaterController(UpdaterController updaterController) {
        mUpdaterController = updaterController;
        notifyDataSetChanged();
    }

    public void setOnVisibilityChangeListener(OnVisibilityChangeListener listener) {
        mVisibilityChangeListener = listener;
    }

    public void showAll() {
        mMaxVisibleCount = Integer.MAX_VALUE;
        notifyDataSetChanged();
        if (mVisibilityChangeListener != null) {
            mVisibilityChangeListener.onVisibilityChanged();
        }
    }

    public boolean hasMore() {
        return mDownloadIds != null && mDownloadIds.size() > mMaxVisibleCount;
    }

    public int getAvailableCount() {
        return mDownloadIds == null ? 0 : mDownloadIds.size();
    }

    private void toggleExpand(String downloadId, int position) {
        if (downloadId.equals(mExpandedDownloadId)) {
            mExpandedDownloadId = null;
            notifyItemChanged(position);
        } else {
            String oldExpanded = mExpandedDownloadId;
            mExpandedDownloadId = downloadId;
            notifyItemChanged(position);
            if (oldExpanded != null && mDownloadIds != null) {
                int oldPosition = mDownloadIds.indexOf(oldExpanded);
                if (oldPosition >= 0 && oldPosition < getItemCount()) {
                    notifyItemChanged(oldPosition);
                }
            }
        }
    }

    private void handleActiveStatus(ViewHolder viewHolder, UpdateInfo update) {
        final String downloadId = update.getDownloadId();
        boolean isVerifying = mUpdaterController.isVerifyingUpdate(downloadId);
        boolean isInstalling = mUpdaterController.isInstallingUpdate(downloadId)
                || update.getStatus() == UpdateStatus.INSTALLATION_SUSPENDED;
        boolean isABUpdate = mUpdaterController.isInstallingABUpdate();

        if (mUpdaterController.isDownloading(downloadId)) {
            String downloaded = Formatter.formatShortFileSize(mContext,
                    update.getFile().length());
            String total = Formatter.formatShortFileSize(mContext, update.getFileSize());
            String percentage = NumberFormat.getPercentInstance().format(
                    update.getProgress() / 100.f);
            viewHolder.mPercentage.setText(percentage);
            long eta = update.getEta();
            if (eta > 0) {
                CharSequence etaString = StringGenerator.formatETA(mContext, eta * 1000);
                viewHolder.mProgressText.setText(mContext.getString(
                        R.string.list_download_progress_eta_newer, downloaded, total, etaString));
            } else {
                viewHolder.mProgressText.setText(mContext.getString(
                        R.string.list_download_progress_newer, downloaded, total));
            }
            setButtonAction(viewHolder.mAction, Action.PAUSE, downloadId, true);
            viewHolder.mProgressBar.setIndeterminate(update.getStatus() == UpdateStatus.STARTING);
            viewHolder.mProgressBar.setProgress(update.getProgress());
        } else if (isInstalling) {
            if (isABUpdate) {
                boolean isSuspended = update.getStatus() == UpdateStatus.INSTALLATION_SUSPENDED;
                setButtonAction(viewHolder.mAction,
                        isSuspended ? Action.RESUME_INSTALLATION : Action.SUSPEND_INSTALLATION,
                        downloadId, true);
            } else {
                setButtonAction(viewHolder.mAction, Action.CANCEL_INSTALLATION, downloadId, true);
            }
            viewHolder.mProgressText.setText(!isABUpdate ? R.string.dialog_prepare_zip_message :
                    update.getFinalizing() ?
                            R.string.finalizing_package :
                            R.string.preparing_ota_first_boot);
            String percentage = NumberFormat.getPercentInstance().format(
                    update.getInstallProgress() / 100.f);
            viewHolder.mPercentage.setText(percentage);
            viewHolder.mProgressBar.setIndeterminate(false);
            viewHolder.mProgressBar.setProgress(update.getInstallProgress());
        } else if (isVerifying) {
            setButtonAction(viewHolder.mAction, Action.INSTALL, downloadId, false);
            viewHolder.mProgressText.setText(R.string.list_verifying_update);
            viewHolder.mProgressBar.setIndeterminate(true);
        } else {
            setButtonAction(viewHolder.mAction, Action.RESUME, downloadId,
                    Utils.isNetworkAvailable(mContext));
            long downloadedBytes = update.getFile() != null ? update.getFile().length() : 0;
            String downloaded = Formatter.formatShortFileSize(mContext, downloadedBytes);
            String total = Formatter.formatShortFileSize(mContext, update.getFileSize());
            String percentage = NumberFormat.getPercentInstance().format(
                    update.getProgress() / 100.f);
            viewHolder.mPercentage.setText(percentage);
            viewHolder.mProgressText.setText(mContext.getString(
                    R.string.list_download_progress_newer, downloaded, total));
            viewHolder.mProgressBar.setIndeterminate(false);
            viewHolder.mProgressBar.setProgress(update.getProgress());
        }

        boolean showCancel = !isVerifying && (!isInstalling || isABUpdate);
        if (showCancel) {
            viewHolder.mCancel.setVisibility(View.VISIBLE);
            setButtonAction(viewHolder.mCancel,
                    isInstalling ? Action.CANCEL_INSTALLATION : Action.CANCEL, downloadId, true);
        } else {
            viewHolder.mCancel.setVisibility(View.GONE);
        }

        boolean canDelete = update.getPersistentStatus() == UpdateStatus.Persistent.VERIFIED;
        setInlineButtonHandlers(viewHolder, update, canDelete);
        viewHolder.mProgressContainer.setVisibility(View.VISIBLE);
        viewHolder.mProgressBar.setVisibility(View.VISIBLE);
        viewHolder.mProgressText.setVisibility(View.VISIBLE);
        viewHolder.mPercentage.setVisibility(View.VISIBLE);
    }

    private void handleNotActiveStatus(ViewHolder viewHolder, UpdateInfo update) {
        final String downloadId = update.getDownloadId();
        boolean canDelete = false;
        if (mUpdaterController.isWaitingForReboot(downloadId) ||
                update.getStatus() == UpdateStatus.INSTALLED) {
            setButtonAction(viewHolder.mAction, Action.REBOOT, downloadId, true);
        } else if (update.getPersistentStatus() == UpdateStatus.Persistent.VERIFIED) {
            canDelete = true;
            setButtonAction(viewHolder.mAction,
                    Utils.canInstall(update) ? Action.INSTALL : Action.DELETE,
                    downloadId, !isBusy());
        } else if (!Utils.canInstall(update)) {
            setButtonAction(viewHolder.mAction, Action.INFO, downloadId, !isBusy());
        } else if (Utils.isStreamingEnabled(mContext)) {
            boolean canStream = !mUpdaterController.isVerifyingUpdate(downloadId)
                    && !mUpdaterController.isInstallingUpdate(downloadId)
                    && Utils.isNetworkAvailable(mContext)
                    && update.getAvailableOnline();
            setButtonAction(viewHolder.mAction, Action.STREAM_INSTALL, downloadId, canStream);
        } else {
            boolean canDownload = !mUpdaterController.isVerifyingUpdate(downloadId)
                    && !mUpdaterController.isInstallingUpdate(downloadId)
                    && Utils.isNetworkAvailable(mContext);
            setButtonAction(viewHolder.mAction, Action.DOWNLOAD, downloadId, canDownload);
        }
        setInlineButtonHandlers(viewHolder, update, canDelete);

        viewHolder.mCancel.setVisibility(View.GONE);
        viewHolder.mProgressContainer.setVisibility(View.GONE);
        viewHolder.mProgressBar.setVisibility(View.GONE);
        viewHolder.mProgressText.setVisibility(View.GONE);
        viewHolder.mPercentage.setVisibility(View.GONE);
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

        if (viewHolder.itemView instanceof DrawableStateLinearLayout) {
            DrawableStateLinearLayout stateLayout =
                    (DrawableStateLinearLayout) viewHolder.itemView;
            int count = getItemCount();
            int state;
            if (count == 1) {
                state = android.R.attr.state_single;
            } else if (i == 0) {
                state = android.R.attr.state_first;
            } else if (i == count - 1) {
                state = android.R.attr.state_last;
            } else {
                state = android.R.attr.state_middle;
            }
            stateLayout.setExtraDrawableState(new int[]{state});

            Drawable background = stateLayout.getBackground();
            if (background != null) {
                Rect padding = new Rect();
                background.getPadding(padding);
                stateLayout.setPadding(padding.left, padding.top,
                        padding.right, padding.bottom);
            }
        }

        viewHolder.itemView.setSelected(downloadId.equals(mSelectedDownload));

        boolean isExpanded = downloadId.equals(mExpandedDownloadId);

        boolean activeLayout = switch (update.getPersistentStatus()) {
            case UpdateStatus.Persistent.UNKNOWN -> update.getStatus() == UpdateStatus.STARTING
                    || update.getStatus() == UpdateStatus.INSTALLING;
            case UpdateStatus.Persistent.VERIFIED -> update.getStatus() == UpdateStatus.INSTALLING
                    || update.getStatus() == UpdateStatus.INSTALLATION_SUSPENDED;
            case UpdateStatus.Persistent.INCOMPLETE -> true;
            default -> throw new RuntimeException("Unknown update status");
        };

        String buildDate = StringGenerator.getDateLocalizedUTC(mContext,
                DateFormat.MEDIUM, update.getTimestamp());
        String buildVersion = mContext.getString(R.string.list_build_version,
                update.getVersion());
        viewHolder.mBuildDate.setText(buildDate);
        viewHolder.mBuildVersion.setText(buildVersion);
        viewHolder.mUpdateIcon.setVisibility(isExpanded ? View.GONE : View.VISIBLE);
        viewHolder.mDetailContainer.setVisibility(isExpanded ? View.VISIBLE : View.GONE);
        viewHolder.mActionsContainer.setVisibility(isExpanded ? View.VISIBLE : View.GONE);
        viewHolder.mExpandIcon.setRotation(isExpanded ? 180f : 0f);

        if (isExpanded) {
            String fileSize = Formatter.formatShortFileSize(mContext, update.getFileSize());
            viewHolder.mDetailSize.setText(mContext.getString(
                    R.string.update_detail_size, fileSize));
            viewHolder.mDetailAndroid.setText(mContext.getString(
                    R.string.update_detail_android, Build.VERSION.RELEASE));
            String securityPatch = DeviceInfoUtils.getSecurityPatchShort(
                    StringGenerator.getCurrentLocale(mContext));
            viewHolder.mDetailSecurity.setText(mContext.getString(
                    R.string.update_detail_security,
                    securityPatch));

            if (activeLayout) {
                handleActiveStatus(viewHolder, update);
            } else {
                handleNotActiveStatus(viewHolder, update);
            }
        } else {
            viewHolder.mProgressContainer.setVisibility(View.GONE);
            viewHolder.mProgressBar.setVisibility(View.GONE);
            viewHolder.mProgressText.setVisibility(View.GONE);
            viewHolder.mPercentage.setVisibility(View.GONE);
        }

        View.OnClickListener expandClickListener = v -> {
            int pos = viewHolder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) {
                toggleExpand(downloadId, pos);
            }
        };
        viewHolder.itemView.setOnClickListener(expandClickListener);
        viewHolder.mExpandIcon.setOnClickListener(expandClickListener);
    }

    @Override
    public int getItemCount() {
        if (mDownloadIds == null) return 0;
        return Math.min(mDownloadIds.size(), mMaxVisibleCount);
    }

    public void setData(List<String> downloadIds) {
        mDownloadIds = downloadIds;
        if (mDownloadIds != null && !mDownloadIds.isEmpty()) {
            mExpandedDownloadId = mDownloadIds.get(0);
        } else {
            mExpandedDownloadId = null;
        }
        mMaxVisibleCount = DEFAULT_MAX_VISIBLE;
    }

    public void addItem(String downloadId) {
        if (mDownloadIds == null) {
            mDownloadIds = new ArrayList<>();
        }
        mDownloadIds.add(0, downloadId);
        mExpandedDownloadId = downloadId;
        notifyItemInserted(0);
        if (mDownloadIds.size() > mMaxVisibleCount) {
            notifyItemRemoved(mMaxVisibleCount);
        }
        notifyItemRangeChanged(0, getItemCount());
        if (mVisibilityChangeListener != null) {
            mVisibilityChangeListener.onVisibilityChanged();
        }
    }

    public void notifyItemChanged(String downloadId) {
        if (mDownloadIds == null) {
            return;
        }
        int position = mDownloadIds.indexOf(downloadId);
        if (position >= 0 && position < getItemCount()) {
            notifyItemChanged(position);
        }
    }

    public void removeItem(String downloadId) {
        if (mDownloadIds == null) {
            return;
        }
        int position = mDownloadIds.indexOf(downloadId);
        if (position < 0) {
            return;
        }
        mDownloadIds.remove(downloadId);
        if (downloadId.equals(mExpandedDownloadId)) {
            mExpandedDownloadId = mDownloadIds.isEmpty() ? null : mDownloadIds.get(0);
        }

        if (position < mMaxVisibleCount) {
             notifyItemRemoved(position);
             if (mDownloadIds.size() >= mMaxVisibleCount) {
                  notifyItemInserted(mMaxVisibleCount - 1);
             }
        }

        notifyItemRangeChanged(0, getItemCount());
        if (mVisibilityChangeListener != null) {
            mVisibilityChangeListener.onVisibilityChanged();
        }
    }

    private static int getActionTextResId(Action action) {
        return switch (action) {
            case DOWNLOAD -> R.string.action_download;
            case PAUSE, SUSPEND_INSTALLATION -> R.string.action_pause;
            case RESUME, RESUME_INSTALLATION -> R.string.action_resume;
            case INSTALL, STREAM_INSTALL -> R.string.action_install;
            case INFO -> R.string.action_info;
            case DELETE -> R.string.action_delete;
            case REBOOT -> R.string.reboot;
            case CANCEL, CANCEL_INSTALLATION -> android.R.string.cancel;
        };
    }

    private void dispatchAction(Action action, String downloadId) {
        switch (action) {
            case DOWNLOAD -> mActionHandler.onStartDownload(downloadId);
            case PAUSE -> mActionHandler.onPauseDownload(downloadId);
            case RESUME -> mActionHandler.onResumeDownload(downloadId);
            case INSTALL -> mActionHandler.onInstallUpdate(downloadId);
            case STREAM_INSTALL -> mActionHandler.onStreamInstallUpdate(downloadId);
            case INFO -> mActionHandler.onShowBlockedUpdateInfo();
            case DELETE -> mActionHandler.onDeleteUpdate(downloadId);
            case CANCEL_INSTALLATION -> mActionHandler.onCancelInstallation();
            case REBOOT -> mActionHandler.onRebootDevice();
            case CANCEL -> mActionHandler.onCancelDownload(downloadId);
            case SUSPEND_INSTALLATION -> mActionHandler.onSuspendInstallation();
            case RESUME_INSTALLATION -> mActionHandler.onResumeInstallation();
        }
    }

    private void setButtonAction(Button button, Action action, String downloadId,
            boolean enabled) {
        button.setText(getActionTextResId(action));
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1.f : mAlphaDisabledValue);
        button.setOnClickListener(enabled ? v -> dispatchAction(action, downloadId) : null);
    }

    private boolean isBusy() {
        return mUpdaterController.hasActiveDownloads() || mUpdaterController.isVerifyingUpdate()
                || mUpdaterController.isInstallingUpdate();
    }

    private void setInlineButtonHandlers(ViewHolder viewHolder, UpdateInfo update,
            boolean canDelete) {
        boolean isVerified = update.getPersistentStatus() == UpdateStatus.Persistent.VERIFIED;
        boolean shouldShowDelete = canDelete;
        if (isVerified && !Utils.canInstall(update) && !update.getAvailableOnline()) {
            shouldShowDelete = false;
        }

        viewHolder.mDeleteAction.setVisibility(shouldShowDelete ? View.VISIBLE : View.GONE);
        viewHolder.mDeleteAction.setOnClickListener(
                v -> mActionHandler.onDeleteUpdate(update.getDownloadId()));

        viewHolder.mCopyUrl.setVisibility(
                update.getAvailableOnline() ? View.VISIBLE : View.GONE);
        viewHolder.mCopyUrl.setOnClickListener(
                v -> mActionHandler.onCopyDownloadUrl(update.getDownloadId()));

        viewHolder.mExportUpdate.setVisibility(
                isVerified && update.getAvailableOnline() ? View.VISIBLE : View.GONE);
        viewHolder.mExportUpdate.setOnClickListener(
                v -> mActionHandler.onExportUpdate(update));
    }
}
