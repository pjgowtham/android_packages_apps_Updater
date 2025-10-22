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
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import java.io.File

/**
 * Defines the database entity for a persistent update.
 * This mirrors the schema previously managed by UpdatesDbHelper.
 *
 * Properties are 'val' to promote immutability, as Room
 * creates new objects on read and updates are done via @Query.
 */
@Entity(
    tableName = "updates", indices = [Index(value = ["download_id"], unique = true)]
)
data class UpdateEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "_id") val id: Long = 0,

    @ColumnInfo(name = "status") val persistentStatus: Int,

    @ColumnInfo(name = "path") val file: File?,

    @ColumnInfo(name = "download_id") val downloadId: String,

    val timestamp: Long, val type: String, val version: String,

    @ColumnInfo(name = "size") val fileSize: Long
)

/**
 * Data Access Object (DAO) for update entities.
 * Replaces the query methods from UpdatesDbHelper.
 */
@Dao
interface UpdateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun addUpdate(update: UpdateEntity)

    @Query("DELETE FROM updates WHERE download_id = :downloadId")
    fun removeUpdate(downloadId: String)

    @Query("UPDATE updates SET status = :status WHERE download_id = :downloadId")
    fun changeUpdateStatus(downloadId: String, status: Int)

    @Query("SELECT * FROM updates ORDER BY timestamp DESC")
    fun getUpdates(): List<UpdateEntity>
}

/**
 * Room type converters for unsupported types (e.g., File).
 */
@Suppress("unused")
class Converters {
    @TypeConverter
    fun fromFile(file: File?): String? = file?.absolutePath

    @TypeConverter
    fun toFile(path: String?): File? = path?.let { File(it) }
}

/**
 * Room database definition for the application.
 * This class provides a singleton instance of the database.
 */
@Database(entities = [UpdateEntity::class], version = 1)
@TypeConverters(Converters::class)
abstract class UpdatesDatabase : RoomDatabase() {

    abstract fun updateDao(): UpdateDao

    companion object {
        private const val DATABASE_NAME = "updates_room.db"

        @Volatile
        private var INSTANCE: UpdatesDatabase? = null

        @JvmStatic
        fun getInstance(context: Context): UpdatesDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext, UpdatesDatabase::class.java, DATABASE_NAME
            ).build().also { INSTANCE = it }
        }
    }
}
