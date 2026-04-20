package com.bravo.notificationhq

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        NotificationModel::class,
        CourseModel::class,
        PlacementChannelModel::class,
        NptelChannelModel::class,
        TaskStatusModel::class          // NEW — Sprint 2B
    ],
    version = 6,                        // BUMPED from 5
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun notificationDao(): NotificationDao
    abstract fun courseDao(): CourseDao
    abstract fun placementChannelDao(): PlacementChannelDao
    abstract fun nptelChannelDao(): NptelChannelDao
    abstract fun taskStatusDao(): TaskStatusDao              // NEW — Sprint 2B

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "notification_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}