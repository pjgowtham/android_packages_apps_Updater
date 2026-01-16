/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Provides methods to insert, query, update, and delete updates.
 *
 * **Threading Note**: All methods are blocking and must be called from a background thread.
 * These methods are intentionally non-suspend to maintain Java interoperability.
 */
@Dao
interface UpdateDao {
    /**
     * Inserts or updates an update in the database.
     *
     * @param update The update entity to insert or replace
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun addUpdate(update: UpdateEntity)

    /**
     * Updates the status of a specific update.
     *
     * @param downloadId The download ID of the update to modify
     * @param status The new status value
     * @return The number of rows updated (0 if download ID not found, 1 if successful)
     */
    @Query("UPDATE updates SET status = :status WHERE download_id = :downloadId")
    fun changeUpdateStatus(downloadId: String, status: Int): Int

    /**
     * Retrieves a single update by its download ID.
     *
     * @param downloadId The download ID to query
     * @return The update entity if found, null otherwise
     */
    @Query("SELECT * FROM updates WHERE download_id = :downloadId LIMIT 1")
    fun getUpdate(downloadId: String): UpdateEntity?

    /**
     * Retrieves all updates ordered by timestamp (newest first).
     *
     * @return List of all update entities
     */
    @Query("SELECT * FROM updates ORDER BY timestamp DESC")
    fun getUpdates(): List<UpdateEntity>

    /**
     * Removes a specific update from the database.
     *
     * @param downloadId The download ID of the update to remove
     */
    @Query("DELETE FROM updates WHERE download_id = :downloadId")
    fun removeUpdate(downloadId: String)
}
