/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.updates

sealed class UpdateListOperation {
    data object Download : UpdateListOperation()
    data object Install : UpdateListOperation()
}

sealed class UpdateListPrimaryAction {
    abstract val enabled: Boolean

    data class Start(
        val operation: UpdateListOperation,
        override val enabled: Boolean,
    ) : UpdateListPrimaryAction()

    data class Pause(
        val operation: UpdateListOperation,
        override val enabled: Boolean = true,
    ) : UpdateListPrimaryAction()

    data class Resume(
        val operation: UpdateListOperation,
        override val enabled: Boolean,
    ) : UpdateListPrimaryAction()

    data class Info(override val enabled: Boolean = true) : UpdateListPrimaryAction()

    data object Reboot : UpdateListPrimaryAction() {
        override val enabled = true
    }
}

sealed class UpdateListSecondaryAction {
    abstract val enabled: Boolean

    data class Cancel(
        val operation: UpdateListOperation,
        override val enabled: Boolean = true,
    ) : UpdateListSecondaryAction()
}

sealed class UpdateListMenuAction {
    data object Delete : UpdateListMenuAction()
    data object Export : UpdateListMenuAction()
    data object ViewDownloads : UpdateListMenuAction()
}
