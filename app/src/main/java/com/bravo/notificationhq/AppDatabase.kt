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
        TaskStatusModel::class,
        HostelChannelModel::class       // NEW — Sprint 2C
    ],
    version = 7,                        // BUMPED from 6
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun notificationDao(): NotificationDao
    abstract fun courseDao(): CourseDao
    abstract fun placementChannelDao(): PlacementChannelDao
    abstract fun nptelChannelDao(): NptelChannelDao
    abstract fun taskStatusDao(): TaskStatusDao
    abstract fun hostelChannelDao(): HostelChannelDao       // NEW — Sprint 2C

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