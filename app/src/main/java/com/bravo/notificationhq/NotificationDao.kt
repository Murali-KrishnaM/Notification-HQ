package com.bravo.notificationhq

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface NotificationDao {
    // Gets all notifications, newest first
    @Query("SELECT * FROM notifications_table ORDER BY id DESC")
    fun getAllNotifications(): List<NotificationModel>

    // Gets notifications for a specific subject/group
    @Query("SELECT * FROM notifications_table WHERE source = :targetSource ORDER BY id DESC")
    fun getNotificationsBySource(targetSource: String): List<NotificationModel>

    // Saves a new notification
    @Insert
    fun insertNotification(notification: NotificationModel)
}