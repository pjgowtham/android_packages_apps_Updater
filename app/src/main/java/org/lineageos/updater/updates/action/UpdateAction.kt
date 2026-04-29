/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.updates.action

import android.content.Context
import androidx.annotation.StringRes
import org.lineageos.updater.R

enum class UpdateOperation {
    DOWNLOAD,
    INSTALL,
    EXPORT,
    DELETE,
    VIEW_DOWNLOADS,
    REBOOT,
}

data class UpdateActionConfirmation(
    @param:StringRes val titleRes: Int,
    @param:StringRes val messageRes: Int,
    @param:StringRes val positiveRes: Int = android.R.string.ok,
    @param:StringRes val negativeRes: Int = android.R.string.cancel,
)

enum class UpdateActionType(
    @param:StringRes val titleRes: Int,
    val operation: UpdateOperation? = null,
    val destructive: Boolean = false,
    val confirmation: UpdateActionConfirmation? = null,
) {
    START_DOWNLOAD(R.string.action_download, UpdateOperation.DOWNLOAD),
    PAUSE_DOWNLOAD(R.string.action_pause, UpdateOperation.DOWNLOAD),
    RESUME_DOWNLOAD(R.string.action_resume, UpdateOperation.DOWNLOAD),
    CANCEL_DOWNLOAD(
        android.R.string.cancel,
        UpdateOperation.DOWNLOAD,
        destructive = true,
        confirmation = UpdateActionConfirmation(
            titleRes = R.string.confirm_cancel_dialog_title,
            messageRes = R.string.confirm_cancel_dialog_message,
        ),
    ),
    START_INSTALL(R.string.action_install, UpdateOperation.INSTALL),
    PAUSE_INSTALL(R.string.action_pause, UpdateOperation.INSTALL),
    RESUME_INSTALL(R.string.action_resume, UpdateOperation.INSTALL),
    CANCEL_INSTALL(
        android.R.string.cancel,
        UpdateOperation.INSTALL,
        destructive = true,
        confirmation = UpdateActionConfirmation(
            titleRes = R.string.cancel_installation_dialog_title,
            messageRes = R.string.cancel_installation_dialog_message,
        ),
    ),
    SHOW_INFO(R.string.action_info),
    DELETE(
        R.string.menu_delete_update,
        UpdateOperation.DELETE,
        destructive = true,
        confirmation = UpdateActionConfirmation(
            titleRes = R.string.confirm_delete_dialog_title,
            messageRes = R.string.confirm_delete_dialog_message,
        ),
    ),
    EXPORT(R.string.menu_export_update, UpdateOperation.EXPORT),
    VIEW_DOWNLOADS(R.string.menu_view_downloads, UpdateOperation.VIEW_DOWNLOADS),
    REBOOT(R.string.reboot, UpdateOperation.REBOOT),
}

data class UpdateAction(
    val type: UpdateActionType,
    val titleArgRes: List<Int> = emptyList(),
    val enabled: Boolean = true,
    val confirmation: UpdateActionConfirmation? = type.confirmation,
) {
    @get:StringRes
    val titleRes: Int
        get() = type.titleRes

    val operation: UpdateOperation?
        get() = type.operation

    val destructive: Boolean
        get() = type.destructive

    fun title(context: Context): String =
        if (titleArgRes.isEmpty()) {
            context.getString(titleRes)
        } else {
            context.getString(
                titleRes,
                *titleArgRes.map(context::getString).toTypedArray(),
            )
        }
}

data class UpdateActions(
    val primary: UpdateAction?,
    val secondary: UpdateAction? = null,
    val overflow: List<UpdateAction> = emptyList(),
)
