/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.ui

import androidx.compose.runtime.Immutable

/**
 * UI state for a single update list item. All subclasses are deeply immutable,
 * allowing the Compose compiler to skip recomposition when state is unchanged.
 */
@Immutable
sealed class UpdateListItemUiState {

    abstract val buildDate: String
    abstract val buildVersion: String
    abstract val menuState: MenuState

    data class Active(
        override val buildDate: String,
        override val buildVersion: String,
        override val menuState: MenuState,
        val primaryAction: PrimaryAction,
        val isPrimaryActionEnabled: Boolean,
        val isCancelVisible: Boolean,
        val cancelAction: CancelAction,
        val isProgressIndeterminate: Boolean,
        val progressValue: Int,
        val progressText: StringWrapper,
        val percentageText: String,
    ) : UpdateListItemUiState()

    data class Inactive(
        override val buildDate: String,
        override val buildVersion: String,
        override val menuState: MenuState,
        val primaryAction: PrimaryAction,
        val isPrimaryActionEnabled: Boolean,
        val buildSize: String,
    ) : UpdateListItemUiState()

    enum class PrimaryAction {
        DOWNLOAD,
        PAUSE,
        RESUME,
        INSTALL,
        INFO,
        DELETE,
        CANCEL_INSTALLATION,
        REBOOT,
        SUSPEND_INSTALLATION,
        RESUME_INSTALLATION,
    }

    enum class CancelAction {
        CANCEL_DOWNLOAD,
        CANCEL_INSTALLATION,
    }

    @Immutable
    data class MenuState(
        val showDelete: Boolean,
        val showCopyUrl: Boolean,
        val showExport: Boolean,
    )

    companion object {
        /**
         * Placeholder returned when [org.lineageos.updater.controller.UpdaterController.getUpdate]
         * returns null (e.g. the entry was just deleted). All controls should be disabled.
         */
        val LOADING: UpdateListItemUiState = Inactive(
            buildDate = "",
            buildVersion = "",
            menuState = MenuState(showDelete = false, showCopyUrl = false, showExport = false),
            primaryAction = PrimaryAction.DOWNLOAD,
            isPrimaryActionEnabled = false,
            buildSize = "",
        )
    }
}
