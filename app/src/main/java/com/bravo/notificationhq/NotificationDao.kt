package com.bravo.notificationhq

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface NotificationDao {

    @Query("SELECT * FROM notifications_table ORDER BY timestamp DESC")
    fun getAllNotifications(): List<NotificationModel>

    @Query("SELECT * FROM notifications_table WHERE source = :courseName ORDER BY timestamp DESC")
    fun getNotificationsForCourse(courseName: String): List<NotificationModel>

    @Query("SELECT COUNT(*) FROM notifications_table WHERE source = :courseName")
    fun getCountForCourse(courseName: String): Int

    @Insert
    fun insertNotification(notification: NotificationModel)

    @Query("DELETE FROM notifications_table WHERE source = :courseName")
    fun deleteNotificationsForCourse(courseName: String)

    @Query("UPDATE notifications_table SET source = :newName WHERE source = :oldName")
    fun updateCourseNameInNotifications(oldName: String, newName: String)
}