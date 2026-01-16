/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import org.lineageos.updater.model.Update
import java.io.File

/**
 * Manages the database instance and provides model conversion utilities.
 */
@Database(
    entities = [UpdateEntity::class],
    version = 2,
    exportSchema = false
)
abstract class UpdatesDatabase : RoomDatabase() {

    abstract fun updateDao(): UpdateDao

    companion object {
        private const val DATABASE_NAME = "updates.db"

        @Volatile
        private var INSTANCE: UpdatesDatabase? = null

        fun getInstance(context: Context): UpdatesDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    UpdatesDatabase::class.java,
                    DATABASE_NAME
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }

        /**
         * Converts an Update model to UpdateEntity for database operations.
         *
         * @param update The update model to convert
         * @return The corresponding UpdateEntity
         */
        @JvmStatic
        fun toEntity(update: Update) = UpdateEntity(
            downloadId = update.downloadId,
            fileSize = update.fileSize,
            path = update.file?.absolutePath,
            persistentStatus = update.persistentStatus,
            timestamp = update.timestamp,
            type = update.type,
            version = update.version,
        )

        /**
         * Converts an UpdateEntity from database to Update model.
         *
         * @param entity The UpdateEntity to convert
         * @return The corresponding Update model
         */
        @JvmStatic
        fun toModel(entity: UpdateEntity) = Update().apply {
            downloadId = entity.downloadId
            fileSize = entity.fileSize
            persistentStatus = entity.persistentStatus
            timestamp = entity.timestamp
            type = entity.type
            version = entity.version
            file = entity.path?.takeIf { it.isNotEmpty() }?.let { File(it) }
            name = file?.name ?: entity.downloadId
        }
    }
}
