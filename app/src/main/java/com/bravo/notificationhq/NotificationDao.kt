package com.bravo.notificationhq

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface NotificationDao {

    @Query("SELECT * FROM notifications_table ORDER BY timestamp DESC")
    suspend fun getAllNotifications(): List<NotificationModel>

    @Query("SELECT * FROM notifications_table WHERE source = :courseName ORDER BY timestamp DESC")
    suspend fun getNotificationsForCourse(courseName: String): List<NotificationModel>

    @Query("SELECT COUNT(*) FROM notifications_table WHERE source = :courseName")
    suspend fun getCountForCourse(courseName: String): Int

    // Returns the inserted row ID (Long) — required by GeminiTagger post-save update
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotification(notification: NotificationModel): Long

    @Query("DELETE FROM notifications_table WHERE source = :courseName")
    suspend fun deleteNotificationsForCourse(courseName: String)

    @Query("DELETE FROM notifications_table WHERE id = :notifId")
    suspend fun deleteNotificationById(notifId: Int)

    @Query("UPDATE notifications_table SET source = :newName WHERE source = :oldName")
    suspend fun updateCourseNameInNotifications(oldName: String, newName: String)

    // ── Gemini tagging fields (added DB v8) ───────────────────────────────

    @Query("UPDATE notifications_table SET isUrgent = :isUrgent, dueDate = :dueDate WHERE id = :id")
    suspend fun updateTagFields(id: Int, isUrgent: Boolean, dueDate: String?)

    @Query("UPDATE notifications_table SET summaryText = :summary WHERE id = :id")
    suspend fun updateSummary(id: Int, summary: String)

    @Query("SELECT * FROM notifications_table WHERE id = :id LIMIT 1")
    suspend fun getNotificationById(id: Int): NotificationModel?
}