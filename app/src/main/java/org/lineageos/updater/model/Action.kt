/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.model

import androidx.annotation.StringRes
import org.lineageos.updater.R

/**
 * Represents a user-facing action that can be performed on an update.
 *
 * @property textResId The string resource ID for the button label.
 * @property isPrimary Whether this action is the primary (positive) action in the UI.
 */
enum class Action(
    @param:StringRes val textResId: Int,
    val isPrimary: Boolean,
) {
    CANCEL(android.R.string.cancel, false),
    CANCEL_INSTALLATION(android.R.string.cancel, false),
    DELETE(R.string.action_delete, true),
    DOWNLOAD(R.string.action_download, true),
    INFO(R.string.action_info, true),
    INSTALL(R.string.action_install, true),
    PAUSE(R.string.action_pause, true),
    REBOOT(R.string.reboot, true),
    RESUME(R.string.action_resume, true),
    RESUME_INSTALLATION(R.string.action_resume, true),
    SUSPEND_INSTALLATION(R.string.action_pause, true),
}
