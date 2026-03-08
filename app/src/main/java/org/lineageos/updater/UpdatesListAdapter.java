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

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.text.SpannableString;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelStoreOwner;
import androidx.recyclerview.widget.RecyclerView;

import com.android.settingslib.spa.framework.theme.SettingsOpacity;

import org.lineageos.updater.controller.UpdaterController;
import org.lineageos.updater.model.UpdateInfo;
import org.lineageos.updater.ui.UpdateListItemUiEvent;
import org.lineageos.updater.ui.UpdateListItemUiState;
import org.lineageos.updater.ui.UpdateListItemUiState.Active;
import org.lineageos.updater.ui.UpdateListItemUiState.CancelAction;
import org.lineageos.updater.ui.UpdateListItemUiState.Inactive;
import org.lineageos.updater.ui.UpdateListItemUiState.MenuState;
import org.lineageos.updater.ui.UpdateListItemUiState.PrimaryAction;
import org.lineageos.updater.viewmodel.UpdateListItemViewModel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class UpdatesListAdapter extends RecyclerView.Adapter<UpdatesListAdapter.ViewHolder> {

    private final android.app.Activity mActivity;
    private final UpdateListItemViewModel mViewModel;
    private final Consumer<UpdateInfo> mExportUpdateCallback;

    private List<String> mDownloadIds;
    private AlertDialog mInfoDialog;

    @SuppressWarnings("deprecation")
    public UpdatesListAdapter(
            ViewModelStoreOwner vmStoreOwner,
            LifecycleOwner lifecycleOwner,
            SharedPreferences preferences,
            Consumer<UpdateInfo> exportUpdateCallback) {
        mActivity = (android.app.Activity) vmStoreOwner;
        mAlphaDisabledValue = ColorUtil.getDisabledAlpha(mActivity);
        mExportUpdateCallback = exportUpdateCallback;

        mViewModel = new ViewModelProvider(vmStoreOwner,
                new UpdateListItemViewModel.Factory(
                        mActivity.getApplication(),
                        preferences))
                .get(UpdateListItemViewModel.class);

        mViewModel.observeEvents(lifecycleOwner, this::handleEvent);
    }

    public void setUpdaterController(@Nullable UpdaterController controller) {
        mViewModel.setController(controller);
        notifyDataSetChanged();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        final Button mAction;
        final Button mCancel;
        final ImageButton mMenu;
        final TextView mBuildDate;
        final TextView mBuildVersion;
        final TextView mBuildSize;
        final LinearLayout mProgress;
        final ProgressBar mProgressBar;
        final TextView mProgressText;
        final TextView mPercentage;

        public ViewHolder(@NonNull View view) {
            super(view);
            mAction       = view.findViewById(R.id.update_action);
            mCancel       = view.findViewById(R.id.update_cancel);
            mMenu         = view.findViewById(R.id.update_menu);
            mBuildDate    = view.findViewById(R.id.build_date);
            mBuildVersion = view.findViewById(R.id.build_version);
            mBuildSize    = view.findViewById(R.id.build_size);
            mProgress     = view.findViewById(R.id.progress);
            mProgressBar  = view.findViewById(R.id.progress_bar);
            mProgressText = view.findViewById(R.id.progress_text);
            mPercentage   = view.findViewById(R.id.progress_percent);
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.update_item_view, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        if (mDownloadIds == null) {
            holder.mAction.setEnabled(false);
            return;
        }

        final String downloadId = mDownloadIds.get(position);
        UpdateListItemUiState state = mViewModel.buildUiState(downloadId);

        holder.itemView.setSelected(state.isSelected());
        holder.mBuildDate.setText(state.getBuildDate());
        holder.mBuildVersion.setText(state.getBuildVersion());
        holder.mBuildVersion.setCompoundDrawables(null, null, null, null);
        bindMenuButton(holder.mMenu, state.getMenuState(), downloadId);

        if (state instanceof Active active) {
            bindActiveState(holder, active, downloadId);
        } else if (state instanceof Inactive inactive) {
            bindInactiveState(holder, inactive, downloadId);
        }
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull ViewHolder holder) {
        super.onViewDetachedFromWindow(holder);
        if (mInfoDialog != null) {
            mInfoDialog.dismiss();
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
        if (mDownloadIds == null) mDownloadIds = new ArrayList<>();
        mDownloadIds.add(0, downloadId);
        notifyItemInserted(0);
    }

    public void notifyItemChanged(String downloadId) {
        if (mDownloadIds == null) return;
        int position = mDownloadIds.indexOf(downloadId);
        if (position != -1) notifyItemChanged(position);
    }

    public void removeItem(String downloadId) {
        if (mDownloadIds == null) return;
        int position = mDownloadIds.indexOf(downloadId);
        mDownloadIds.remove(downloadId);
        notifyItemRemoved(position);
        notifyItemRangeChanged(position, getItemCount());
    }

    private void bindActiveState(ViewHolder holder, Active state, String downloadId) {
        holder.mProgressText.setText(
                state.getProgressText().resolve(holder.mProgressText.getContext()));
        holder.mPercentage.setText(state.getPercentageText());
        holder.mProgressBar.setIndeterminate(state.isProgressIndeterminate());
        holder.mProgressBar.setProgress(state.getProgressValue());

        bindPrimaryButton(holder.mAction, state.getPrimaryAction(),
                state.isPrimaryActionEnabled(), downloadId);

        if (state.isCancelVisible()) {
            holder.mCancel.setVisibility(View.VISIBLE);
            bindCancelButton(holder.mCancel, state.getCancelAction(), downloadId);
        } else {
            holder.mCancel.setVisibility(View.GONE);
        }

        holder.mProgress.setVisibility(View.VISIBLE);
        holder.mProgressText.setVisibility(View.VISIBLE);
        holder.mBuildSize.setVisibility(View.INVISIBLE);
    }

    private void bindInactiveState(ViewHolder holder, Inactive state, String downloadId) {
        holder.mBuildSize.setText(state.getBuildSize());
        bindPrimaryButton(holder.mAction, state.getPrimaryAction(),
                state.isPrimaryActionEnabled(), downloadId);
        holder.mCancel.setVisibility(View.GONE);
        holder.mProgress.setVisibility(View.INVISIBLE);
        holder.mProgressText.setVisibility(View.INVISIBLE);
        holder.mBuildSize.setVisibility(View.VISIBLE);
    }

    private void bindPrimaryButton(Button button, PrimaryAction action,
            boolean enabled, String downloadId) {
        switch (action) {
            case DOWNLOAD -> {
                button.setText(R.string.action_download);
                button.setOnClickListener(v -> mViewModel.onDownloadClicked(downloadId));
            }
            case PAUSE -> {
                button.setText(R.string.action_pause);
                button.setOnClickListener(v -> mViewModel.onPause(downloadId));
            }
            case RESUME -> {
                button.setText(R.string.action_resume);
                button.setOnClickListener(v -> mViewModel.onResumeClicked(downloadId));
            }
            case INSTALL -> {
                button.setText(R.string.action_install);
                button.setOnClickListener(v -> mViewModel.onInstallClicked(downloadId));
            }
            case INFO -> {
                button.setText(R.string.action_info);
                button.setOnClickListener(v -> mViewModel.onInfoClicked());
            }
            case DELETE -> {
                button.setText(R.string.action_delete);
                button.setOnClickListener(v -> mViewModel.onDeleteClicked(downloadId));
            }
            case CANCEL_INSTALLATION -> {
                button.setText(android.R.string.cancel);
                button.setOnClickListener(v -> mViewModel.onCancelInstallationClicked());
            }
            case REBOOT -> {
                button.setText(R.string.reboot);
                button.setOnClickListener(v -> mViewModel.onReboot());
            }
            case SUSPEND_INSTALLATION -> {
                button.setText(R.string.action_pause);
                button.setOnClickListener(v -> mViewModel.onSuspendInstallation());
            }
            case RESUME_INSTALLATION -> {
                button.setText(R.string.action_resume);
                button.setOnClickListener(v -> mViewModel.onResumeInstallation());
            }
        }
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1.f : mAlphaDisabledValue);
    }

    private void bindCancelButton(Button button, CancelAction action, String downloadId) {
        button.setText(android.R.string.cancel);
        button.setEnabled(true);
        button.setAlpha(1.f);
        if (action == CancelAction.CANCEL_INSTALLATION) {
            button.setOnClickListener(v -> mViewModel.onCancelInstallationClicked());
        } else {
            button.setOnClickListener(v -> mViewModel.onCancelDownloadClicked(downloadId));
        }
    }

    private void bindMenuButton(ImageButton menu, MenuState state, String downloadId) {
        menu.setOnClickListener(v -> {
            mViewModel.selectDownload(downloadId);
            notifyItemChanged(downloadId);

            PopupMenu popup = new PopupMenu(v.getContext(), v, Gravity.NO_GRAVITY);
            popup.inflate(R.menu.menu_action_mode);
            popup.getMenu().findItem(R.id.menu_delete_action).setVisible(state.getShowDelete());
            popup.getMenu().findItem(R.id.menu_copy_url).setVisible(state.getShowCopyUrl());
            popup.getMenu().findItem(R.id.menu_export_update).setVisible(state.getShowExport());
            popup.setOnMenuItemClickListener(item -> {
                int id = item.getItemId();
                if (id == R.id.menu_delete_action) {
                    mViewModel.onDeleteClicked(downloadId);
                    return true;
                } else if (id == R.id.menu_copy_url) {
                    mViewModel.copyDownloadUrl(downloadId);
                    return true;
                } else if (id == R.id.menu_export_update) {
                    UpdateInfo update = mViewModel.getUpdateForExport(downloadId);
                    if (update != null) mExportUpdateCallback.accept(update);
                    return true;
                }
                return false;
            });
            popup.show();
        });
    }

    private void handleEvent(UpdateListItemUiEvent event) {
        if (event instanceof UpdateListItemUiEvent.ShowSwitchDownloadDialog e) {
            new AlertDialog.Builder(mActivity)
                    .setTitle(R.string.download_switch_confirm_title)
                    .setMessage(R.string.download_switch_confirm_message)
                    .setPositiveButton(android.R.string.ok, (d, w) -> e.getOnConfirm().invoke())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();

        } else if (event instanceof UpdateListItemUiEvent.ShowMeteredNetworkWarning e) {
            new AlertDialog.Builder(mActivity)
                    .setTitle(R.string.update_over_metered_network_title)
                    .setMessage(R.string.update_over_metered_network_message)
                    .setPositiveButton(android.R.string.ok, (d, w) -> e.getOnConfirm().invoke())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();

        } else if (event instanceof UpdateListItemUiEvent.ShowInstallConfirmDialog e) {
            String message = mActivity.getString(e.getMessageRes(),
                    e.getBuildInfo(), mActivity.getString(android.R.string.ok));
            new AlertDialog.Builder(mActivity)
                    .setTitle(R.string.apply_update_dialog_title)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, (d, w) -> e.getOnConfirm().invoke())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();

        } else if (event instanceof UpdateListItemUiEvent.ShowBatteryLowDialog) {
            new AlertDialog.Builder(mActivity)
                    .setTitle(R.string.dialog_battery_low_title)
                    .setMessage(mActivity.getString(R.string.dialog_battery_low_message_pct,
                            mActivity.getResources().getInteger(R.integer.battery_ok_percentage_discharging),
                            mActivity.getResources().getInteger(R.integer.battery_ok_percentage_charging)))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();

        } else if (event instanceof UpdateListItemUiEvent.ShowScratchMountedDialog) {
            new AlertDialog.Builder(mActivity)
                    .setTitle(R.string.dialog_scratch_mounted_title)
                    .setMessage(R.string.dialog_scratch_mounted_message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();

        } else if (event instanceof UpdateListItemUiEvent.ShowInfoDialog e) {
            if (mInfoDialog != null) mInfoDialog.dismiss();
            SpannableString message = new SpannableString(e.getMessage());
            Linkify.addLinks(message, Linkify.WEB_URLS);
            mInfoDialog = new AlertDialog.Builder(mActivity)
                    .setTitle(R.string.blocked_update_dialog_title)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            TextView textView = mInfoDialog.findViewById(android.R.id.message);
            if (textView != null) textView.setMovementMethod(LinkMovementMethod.getInstance());

        } else if (event instanceof UpdateListItemUiEvent.ShowCancelDownloadDialog e) {
            new AlertDialog.Builder(mActivity)
                    .setTitle(R.string.confirm_cancel_dialog_title)
                    .setMessage(R.string.confirm_cancel_dialog_message)
                    .setPositiveButton(android.R.string.ok, (d, w) -> e.getOnConfirm().invoke())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();

        } else if (event instanceof UpdateListItemUiEvent.ShowCancelInstallationDialog e) {
            new AlertDialog.Builder(mActivity)
                    .setTitle(R.string.cancel_installation_dialog_title)
                    .setMessage(R.string.cancel_installation_dialog_message)
                    .setPositiveButton(android.R.string.ok, (d, w) -> e.getOnConfirm().invoke())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();

        } else if (event instanceof UpdateListItemUiEvent.ShowDeleteDialog e) {
            new AlertDialog.Builder(mActivity)
                    .setTitle(R.string.confirm_delete_dialog_title)
                    .setMessage(R.string.confirm_delete_dialog_message)
                    .setPositiveButton(android.R.string.ok, (d, w) -> e.getOnConfirm().invoke())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();

        } else if (event instanceof UpdateListItemUiEvent.ShowNotInstallableToast) {
            Toast.makeText(mActivity, R.string.snack_update_not_installable,
                    Toast.LENGTH_LONG).show();
        }
    }
}
