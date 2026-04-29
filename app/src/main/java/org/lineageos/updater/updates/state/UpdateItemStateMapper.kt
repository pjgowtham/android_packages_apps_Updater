/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.updates.state

import android.content.Context
import android.text.format.Formatter
import org.lineageos.updater.R
import org.lineageos.updater.controller.UpdaterController
import org.lineageos.updater.data.Update
import org.lineageos.updater.data.UpdateStatus
import org.lineageos.updater.updates.action.UpdateActionResolver
import org.lineageos.updater.util.NetworkState
import org.lineageos.updater.util.StringUtil
import java.time.format.FormatStyle

class UpdateItemStateMapper(
    private val context: Context,
    private val updaterController: UpdaterController,
) {

    fun map(update: Update, networkState: NetworkState): UpdateItemState {
        val state = UpdateOperationState.from(updaterController, update)
        val resolver = UpdateActionResolver(updaterController, networkState)

        return UpdateItemState(
            downloadId = update.downloadId,
            buildVersion = context.getString(
                R.string.list_build_version, update.version),
            buildDate = StringUtil.getDateLocalizedUTC(
                context, FormatStyle.LONG, update.timestamp
            ),
            status = state.statusRes?.let { context.getString(it) } ?: "",
            isLocal = state.isLocal,
            fileSize = Formatter.formatShortFileSize(context, update.fileSize),
            progress = resolveProgress(update, state),
            actions = resolver.resolve(update, state),
        )
    }

    private fun resolveProgress(update: Update, state: UpdateOperationState) = when {
        state.isDownloading -> downloadProgress(update)
        state.isInstalling -> installProgress(update)
        state.isVerifying -> ProgressState.Indeterminate
        state.isDownloadPaused -> pausedProgress(update)
        else -> null
    }

    private fun downloadProgress(update: Update): ProgressState {
        if (update.status == UpdateStatus.STARTING) {
            return ProgressState.Indeterminate
        }

        val downloaded = Formatter.formatShortFileSize(context, update.file?.length() ?: 0)
        val total = Formatter.formatShortFileSize(context, update.fileSize)

        return ProgressState.Determinate(
            progress = update.progress.toFloat(),
            downloadedFileSize = context.getString(
                R.string.list_download_progress_newer, downloaded, total
            ),
            eta = update.eta.takeIf { it > 0 }
                ?.let { StringUtil.formatETA(context, it * 1000).toString() }
                ?: "",
        )
    }

    private fun installProgress(update: Update) = ProgressState.Determinate(
        progress = update.installProgress.toFloat(),
        downloadedFileSize = "",
        eta = "",
    )

    private fun pausedProgress(update: Update): ProgressState {
        val downloaded = Formatter.formatShortFileSize(context, update.file?.length() ?: 0)
        val total = Formatter.formatShortFileSize(context, update.fileSize)

        return ProgressState.Determinate(
            progress = update.progress.toFloat(),
            downloadedFileSize = context.getString(
                R.string.list_download_progress_newer, downloaded, total
            ),
            eta = "",
        )
    }
}
