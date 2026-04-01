/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.ui.dialog

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.android.settingslib.spa.debug.UiModePreviews
import com.android.settingslib.spa.widget.dialog.AlertDialogButton
import com.android.settingslib.spa.widget.dialog.AlertDialogPresenter
import com.android.settingslib.spa.widget.dialog.SettingsAlertDialogWithIcon
import com.android.settingslib.spa.framework.theme.SettingsTheme
import org.lineageos.updater.R

/**
 * [SettingsAlertDialogWithIcon] variant of
 * [com.android.settingslib.spa.widget.dialog.rememberAlertDialogPresenter].
 */
@Composable
fun rememberAlertDialogWithIconPresenter(
    confirmButton: AlertDialogButton? = null,
    dismissButton: AlertDialogButton? = null,
    title: String? = null,
    icon: ImageVector = ImageVector.vectorResource(R.drawable.ic_system_update),
    text: @Composable (() -> Unit)? = null,
): AlertDialogPresenter {
    var openDialog by rememberSaveable { mutableStateOf(false) }
    val presenter = remember {
        object : AlertDialogPresenter {
            override fun open() { openDialog = true }
            override fun close() { openDialog = false }
        }
    }
    if (openDialog) {
        SettingsAlertDialogWithIcon(
            onDismissRequest = presenter::close,
            confirmButton = confirmButton?.withAutoClose(presenter),
            dismissButton = dismissButton?.withAutoClose(presenter),
            title = title,
            icon = icon,
            text = text,
        )
    }
    return presenter
}

/** Wraps [AlertDialogButton.onClick] to close the dialog first. */
private fun AlertDialogButton.withAutoClose(presenter: AlertDialogPresenter) = copy(
    onClick = {
        presenter.close()
        onClick()
    },
)

@UiModePreviews
@Composable
private fun AlertDialogWithIconPresenterPreview() {
    SettingsTheme {
        SettingsAlertDialogWithIcon(
            onDismissRequest = {},
            confirmButton = AlertDialogButton("OK"),
            dismissButton = AlertDialogButton("Cancel"),
            title = "Dialog title",
            icon = ImageVector.vectorResource(R.drawable.ic_system_update),
            text = { Text("Dialog body text goes here.") },
        )
    }
}
