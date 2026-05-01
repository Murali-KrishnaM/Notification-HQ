package com.bravo.notificationhq

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        NotificationModel::class,
        CourseModel::class,
        PlacementChannelModel::class,
        NptelChannelModel::class,
        TaskStatusModel::class,
        HostelChannelModel::class
    ],
    version = 8,                        // BUMPED from 7 — adds Gemini tagging columns
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun notificationDao(): NotificationDao
    abstract fun courseDao(): CourseDao
    abstract fun placementChannelDao(): PlacementChannelDao
    abstract fun nptelChannelDao(): NptelChannelDao
    abstract fun taskStatusDao(): TaskStatusDao
    abstract fun hostelChannelDao(): HostelChannelDao

    companion object {

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Migration 7 → 8
         * Adds three nullable columns to notifications_table.
         * Existing rows get: isUrgent=0, dueDate=null, summaryText=null
         * NO DATA LOSS.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE notifications_table ADD COLUMN isUrgent INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE notifications_table ADD COLUMN dueDate TEXT"
                )
                database.execSQL(
                    "ALTER TABLE notifications_table ADD COLUMN summaryText TEXT"
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "notification_database"
                )
                    .addMigrations(MIGRATION_7_8)   // ← proper migration, no data wipe
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}