package com.bravo.notificationhq

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface NotificationDao {

    // All notifications, newest first
    @Query("SELECT * FROM notifications_table ORDER BY timestamp DESC")
    fun getAllNotifications(): List<NotificationModel>

    // All notifications for a specific course (by source = courseName)
    @Query("SELECT * FROM notifications_table WHERE source = :courseName ORDER BY timestamp DESC")
    fun getNotificationsForCourse(courseName: String): List<NotificationModel>

    // Count of notifications per course — used for the badge on subject cards
    @Query("SELECT COUNT(*) FROM notifications_table WHERE source = :courseName")
    fun getCountForCourse(courseName: String): Int

    // Insert a new notification
    @Insert
    fun insertNotification(notification: NotificationModel)
}
