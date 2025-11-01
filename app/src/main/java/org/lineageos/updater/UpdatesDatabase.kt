/*
 * SPDX-FileCopyrightText: 2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import org.lineageos.updater.model.Update
import java.io.File

/* Defines the Room database, DAO, entity, and mappers. */

@Database(
    entities = [UpdateEntity::class],
    version = 1,
    exportSchema = false
)
abstract class UpdatesDatabase : RoomDatabase() {

    abstract fun updateDao(): UpdateDao

    companion object {
        const val DATABASE_NAME = "updates.db"

        @Volatile
        private var INSTANCE: UpdatesDatabase? = null

        fun getInstance(context: Context): UpdatesDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    UpdatesDatabase::class.java,
                    DATABASE_NAME
                )
                    // The old onUpgrade policy was to drop and recreate.
                    // This preserves that behavior.
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

@Dao
interface UpdateDao {
    /**
     * Inserts or replaces an update in the database.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun addUpdate(update: UpdateEntity)

    /**
     * Removes an update using its download ID.
     */
    @Query("DELETE FROM updates WHERE download_id = :downloadId")
    fun removeUpdate(downloadId: String)

    /**
     * Updates the persistent status of an update.
     */
    @Query("UPDATE updates SET status = :status WHERE download_id = :downloadId")
    fun changeUpdateStatus(downloadId: String, status: Int)

    /**
     * Gets all updates, sorted by timestamp descending.
     */
    @Query("SELECT * FROM updates ORDER BY timestamp DESC")
    fun getUpdates(): List<UpdateEntity>
}

/**
 * Defines the database entity for an update.
 * Matches the old schema with _id as auto-increment primary key (nullable)
 * and download_id as a non-null unique identifier.
 */
@Entity(tableName = "updates")
data class UpdateEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "_id")
    val id: Long? = null,

    @ColumnInfo(name = "download_id")
    val downloadId: String,

    @ColumnInfo(name = "status")
    val persistentStatus: Int?,

    @ColumnInfo(name = "path")
    val path: String?,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long?,

    @ColumnInfo(name = "type")
    val type: String?,

    @ColumnInfo(name = "version")
    val version: String?,

    @ColumnInfo(name = "size")
    val fileSize: Long?
)

/**
 * Converts an Update model object to an UpdateEntity for DB operations.
 */
fun Update.toEntity() = UpdateEntity(
    downloadId = this.downloadId,
    persistentStatus = this.persistentStatus,
    path = this.file?.absolutePath,
    timestamp = this.timestamp,
    type = this.type,
    version = this.version,
    fileSize = this.fileSize
)

/**
 * Converts an UpdateEntity from the DB to an Update model object.
 */
fun UpdateEntity.toModel() = Update().apply {
    downloadId = this@toModel.downloadId
    file = this@toModel.path?.let { File(it) }
    fileSize = this@toModel.fileSize ?: 0
    name = this.file?.name
    persistentStatus = this@toModel.persistentStatus ?: 0
    timestamp = this@toModel.timestamp ?: 0
    type = this@toModel.type
    version = this@toModel.version
}
