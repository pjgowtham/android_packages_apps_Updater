/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.repository

import android.content.Context
import org.lineageos.updater.database.UpdateDao
import org.lineageos.updater.database.UpdaterDatabase
import org.lineageos.updater.database.toEntity
import org.lineageos.updater.database.toUpdateInfo
import org.lineageos.updater.model.UpdateInfo
import org.lineageos.updater.model.UpdateStatus

/**
 * Single source of truth for update data.
 *
 * Mediates between [UpdateDao] and the rest of the app, mapping between
 * the database entity and [UpdateInfo] (Domain/Presentation Layer).
 */
class UpdaterDbRepository(private val updateDao: UpdateDao) {

    fun getUpdates(): List<UpdateInfo> = updateDao.getUpdates().map { it.toUpdateInfo() }

    fun addUpdate(update: UpdateInfo) {
        updateDao.insertOrReplace(update.toEntity())
    }

    fun changeStatus(downloadId: String, status: UpdateStatus) =
        updateDao.changeStatus(downloadId, status.persistentStatus)

    fun removeUpdate(downloadId: String) = updateDao.delete(downloadId)

    companion object {
        @Volatile
        private var instance: UpdaterDbRepository? = null

        @JvmStatic
        fun getInstance(context: Context): UpdaterDbRepository = instance ?: synchronized(this) {
            instance ?: UpdaterDbRepository(
                UpdaterDatabase.getInstance(context).updateDao()
            ).also { instance = it }
        }
    }
}
