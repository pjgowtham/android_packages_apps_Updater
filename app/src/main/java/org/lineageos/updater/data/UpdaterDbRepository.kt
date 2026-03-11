/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.data

import org.lineageos.updater.data.source.local.UpdateDao
import org.lineageos.updater.data.source.local.toEntity
import org.lineageos.updater.data.source.local.toUpdate

/**
 * Single source of truth for update data.
 *
 * Mediates between [UpdateDao] and the rest of the app, mapping between
 * the database entity and [Update] (Domain/Presentation Layer).
 */
class UpdaterDbRepository(private val updateDao: UpdateDao) {

    fun getUpdates(): List<Update> = updateDao.getUpdates().map { it.toUpdate() }

    fun addUpdate(update: Update) {
        updateDao.insertOrReplace(update.toEntity())
    }

    fun changeStatus(downloadId: String, status: UpdateStatus) =
        updateDao.changeStatus(downloadId, status.persistentStatus)

    fun removeUpdate(downloadId: String) = updateDao.delete(downloadId)
}
