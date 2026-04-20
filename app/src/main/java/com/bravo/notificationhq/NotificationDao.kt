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

    // Deletes all notifications belonging to a course (used when course is deleted)
    @Query("DELETE FROM notifications_table WHERE source = :courseName")
    fun deleteNotificationsForCourse(courseName: String)

    // Sprint 2B: Delete a single notification by its primary key
    @Query("DELETE FROM notifications_table WHERE id = :notifId")
    fun deleteNotificationById(notifId: Int)

    @Query("UPDATE notifications_table SET source = :newName WHERE source = :oldName")
    fun updateCourseNameInNotifications(oldName: String, newName: String)
}