/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater

import android.content.Context
import android.text.format.Formatter
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.android.settingslib.utils.ColorUtil
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.lineageos.updater.controller.UpdaterController
import org.lineageos.updater.misc.StringGenerator
import org.lineageos.updater.misc.Utils
import org.lineageos.updater.model.UpdateInfo
import org.lineageos.updater.model.UpdateStatus
import java.text.DateFormat
import java.text.NumberFormat

class UpdatePreference(context: Context) : Preference(context) {

    var downloadId = ""
    var updaterController: UpdaterController? = null
    var actionHandler: UpdateActionHandler? = null
    var onCollapseRequested: ((String) -> Unit)? = null

    private val alphaDisabled = ColorUtil.getDisabledAlpha(context)

    fun refresh() {
        notifyChanged()
    }

    init {
        layoutResource = R.layout.update_item_view
        isSelectable = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        holder.isDividerAllowedAbove = false
        holder.isDividerAllowedBelow = false

        val controller = updaterController ?: return
        val update = controller.getUpdate(downloadId) ?: return
        val ctx = context

        // Bind header
        val buildDate = holder.findViewById(R.id.build_date) as TextView
        val buildVersion = holder.findViewById(R.id.build_version) as TextView
        buildDate.text = StringGenerator.getDateLocalizedUTC(
            ctx, DateFormat.LONG, update.timestamp
        )
        buildVersion.text = ctx.getString(R.string.list_build_version, update.version)
        buildVersion.setCompoundDrawables(null, null, null, null)

        // Header click to collapse (only for non-active updates)
        val textContainer = holder.findViewById(R.id.text_container) as View
        val isActive = UpdateStatus.isActive(update.status, update.persistentStatus)
        if (isActive) {
            textContainer.setOnClickListener(null)
            textContainer.isClickable = false
        } else {
            textContainer.setOnClickListener { onCollapseRequested?.invoke(downloadId) }
        }

        // Bind details
        if (isActive) {
            bindActiveStatus(holder, update, controller, ctx)
        } else {
            bindNotActiveStatus(holder, update, ctx)
        }
    }

    private fun bindActiveStatus(
        holder: PreferenceViewHolder,
        update: UpdateInfo,
        controller: UpdaterController,
        ctx: Context
    ) {
        actionHandler ?: return
        val buildSize = holder.findViewById(R.id.build_size) as TextView
        val progressBar =
            holder.findViewById(R.id.progress_indicator) as LinearProgressIndicator
        val progressText = holder.findViewById(R.id.progress_text) as TextView
        val percentage = holder.findViewById(R.id.progress_percent) as TextView
        val actionButton = holder.findViewById(R.id.update_action) as Button
        val cancelButton = holder.findViewById(R.id.cancel_action) as Button

        val isVerifying = controller.isVerifyingUpdate(downloadId)
        val isInstalling = controller.isInstallingUpdate(downloadId) ||
                update.status == UpdateStatus.INSTALLATION_SUSPENDED
        val isABUpdate = controller.isInstallingABUpdate

        when {
            controller.isDownloading(downloadId) -> {
                val downloaded = Formatter.formatShortFileSize(ctx, update.file.length())
                val total = Formatter.formatShortFileSize(ctx, update.fileSize)
                val pctStr = NumberFormat.getPercentInstance().format(
                    update.progress / 100f
                )
                percentage.visibility = View.VISIBLE
                percentage.text = pctStr
                val eta = update.eta
                progressText.visibility = View.VISIBLE
                if (eta > 0) {
                    val etaString = StringGenerator.formatETA(ctx, eta * 1000)
                    progressText.text = ctx.getString(
                        R.string.list_download_progress_eta_newer,
                        downloaded, total, etaString
                    )
                } else {
                    progressText.text = ctx.getString(
                        R.string.list_download_progress_newer, downloaded, total
                    )
                }
                setButtonAction(actionButton, UpdateActionHandler.Action.PAUSE, true)
                progressBar.isIndeterminate = update.status == UpdateStatus.STARTING
                progressBar.progress = update.progress
            }
            isInstalling -> {
                if (isABUpdate) {
                    val isSuspended = update.status == UpdateStatus.INSTALLATION_SUSPENDED
                    setButtonAction(
                        actionButton,
                        if (isSuspended) UpdateActionHandler.Action.RESUME_INSTALLATION
                        else UpdateActionHandler.Action.SUSPEND_INSTALLATION,
                        true
                    )
                } else {
                    setButtonAction(
                        actionButton, UpdateActionHandler.Action.CANCEL_INSTALLATION, true
                    )
                }
                progressText.visibility = View.VISIBLE
                progressText.text = ctx.getString(
                    if (!isABUpdate) R.string.dialog_prepare_zip_message
                    else if (update.finalizing) R.string.finalizing_package
                    else R.string.preparing_ota_first_boot
                )
                val pctStr = NumberFormat.getPercentInstance().format(
                    update.installProgress / 100f
                )
                percentage.visibility = View.VISIBLE
                percentage.text = pctStr
                progressBar.isIndeterminate = false
                progressBar.progress = update.installProgress
            }
            isVerifying -> {
                setButtonAction(actionButton, UpdateActionHandler.Action.INSTALL, false)
                progressText.visibility = View.VISIBLE
                progressText.text = ctx.getString(R.string.list_verifying_update)
                percentage.visibility = View.GONE
                progressBar.isIndeterminate = true
            }
            else -> {
                setButtonAction(
                    actionButton, UpdateActionHandler.Action.RESUME,
                    Utils.isNetworkAvailable(ctx)
                )
                val downloadedBytes = update.file?.length() ?: 0
                val downloaded = Formatter.formatShortFileSize(ctx, downloadedBytes)
                val total = Formatter.formatShortFileSize(ctx, update.fileSize)
                val pctStr = NumberFormat.getPercentInstance().format(
                    update.progress / 100f
                )
                percentage.visibility = View.VISIBLE
                percentage.text = pctStr
                progressText.visibility = View.VISIBLE
                progressText.text = ctx.getString(
                    R.string.list_download_progress_newer, downloaded, total
                )
                progressBar.isIndeterminate = false
                progressBar.progress = update.progress
            }
        }

        // Cancel button
        val showCancel = !isVerifying && (!isInstalling || isABUpdate)
        if (showCancel) {
            cancelButton.visibility = View.VISIBLE
            setButtonAction(
                cancelButton,
                if (isInstalling) UpdateActionHandler.Action.CANCEL_INSTALLATION
                else UpdateActionHandler.Action.CANCEL,
                true
            )
        } else {
            cancelButton.visibility = View.GONE
        }

        // Inline buttons
        bindInlineButtons(holder, update)

        progressBar.visibility = View.VISIBLE
        progressText.visibility = View.VISIBLE
        buildSize.visibility = View.INVISIBLE
    }

    private fun bindNotActiveStatus(
        holder: PreferenceViewHolder,
        update: UpdateInfo,
        ctx: Context
    ) {
        val handler = actionHandler ?: return
        val buildSize = holder.findViewById(R.id.build_size) as TextView
        val progressBar = holder.findViewById(R.id.progress_indicator) as View
        val progressText = holder.findViewById(R.id.progress_text) as View
        val percentage = holder.findViewById(R.id.progress_percent) as View
        val actionButton = holder.findViewById(R.id.update_action) as Button
        val cancelButton = holder.findViewById(R.id.cancel_action) as Button

        val (action, enabled) = handler.resolveIdleAction(downloadId)
        setButtonAction(actionButton, action, enabled)

        bindInlineButtons(holder, update)

        buildSize.text = Formatter.formatShortFileSize(ctx, update.fileSize)

        cancelButton.visibility = View.GONE
        progressBar.visibility = View.GONE
        progressText.visibility = View.GONE
        percentage.visibility = View.GONE
        buildSize.visibility = View.VISIBLE
    }

    private fun setButtonAction(
        button: Button,
        action: UpdateActionHandler.Action,
        enabled: Boolean
    ) {
        val handler = actionHandler ?: return
        val ctx = context

        button.text = when (action) {
            UpdateActionHandler.Action.DOWNLOAD -> ctx.getString(R.string.action_download)
            UpdateActionHandler.Action.PAUSE,
            UpdateActionHandler.Action.SUSPEND_INSTALLATION ->
                ctx.getString(R.string.action_pause)
            UpdateActionHandler.Action.RESUME,
            UpdateActionHandler.Action.RESUME_INSTALLATION ->
                ctx.getString(R.string.action_resume)
            UpdateActionHandler.Action.INSTALL,
            UpdateActionHandler.Action.STREAM_INSTALL ->
                ctx.getString(R.string.action_install)
            UpdateActionHandler.Action.INFO -> ctx.getString(R.string.action_info)
            UpdateActionHandler.Action.DELETE -> ctx.getString(R.string.action_delete)
            UpdateActionHandler.Action.CANCEL_INSTALLATION,
            UpdateActionHandler.Action.CANCEL ->
                ctx.getString(android.R.string.cancel)
            UpdateActionHandler.Action.REBOOT -> ctx.getString(R.string.reboot)
        }
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else alphaDisabled
        button.setOnClickListener {
            handler.performAction(action, downloadId)
        }
    }

    private fun bindInlineButtons(
        holder: PreferenceViewHolder,
        update: UpdateInfo
    ) {
        val handler = actionHandler ?: return
        val ctx = context
        val deleteButton = holder.findViewById(R.id.button_delete_action) as View
        val copyUrl = holder.findViewById(R.id.button_copy_url) as View
        val exportUpdate = holder.findViewById(R.id.button_export_update) as View

        val canDelete = handler.canDeleteUpdate(downloadId)
        deleteButton.visibility = if (canDelete) View.VISIBLE else View.GONE
        deleteButton.setOnClickListener {
            handler.performAction(UpdateActionHandler.Action.DELETE, downloadId)
        }

        copyUrl.visibility = if (update.availableOnline) View.VISIBLE else View.GONE
        copyUrl.setOnClickListener {
            Utils.addToClipboard(
                ctx, ctx.getString(R.string.label_download_url), update.downloadUrl
            )
        }

        val isVerified = update.persistentStatus == UpdateStatus.Persistent.VERIFIED
        exportUpdate.visibility =
            if (isVerified && update.availableOnline) View.VISIBLE else View.GONE
        exportUpdate.setOnClickListener {
            handler.exportUpdate(update)
        }
    }

}
