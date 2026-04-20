package com.bravo.notificationhq

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TaskStatusDao {

    /**
     * Get the status for a single notification by its id.
     * Returns null if the student has never set a status (treat as NOT_STARTED).
     */
    @Query("SELECT * FROM task_status_table WHERE notifId = :notifId")
    fun getStatus(notifId: Int): TaskStatusModel?

    /**
     * Get statuses for a batch of notification ids in one query.
     * Used by the adapter to load all statuses for a course feed at once.
     */
    @Query("SELECT * FROM task_status_table WHERE notifId IN (:notifIds)")
    fun getStatusesForIds(notifIds: List<Int>): List<TaskStatusModel>

    /**
     * Insert or update a status. REPLACE strategy acts as upsert —
     * if a row for this notifId already exists it is overwritten.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertStatus(status: TaskStatusModel)

    /**
     * Delete the status entry when a notification is deleted,
     * so we don't accumulate orphaned rows.
     */
    @Query("DELETE FROM task_status_table WHERE notifId = :notifId")
    fun deleteStatus(notifId: Int)

    /**
     * Bulk-delete statuses for all notifications belonging to a course.
     * Called when the user deletes an entire course.
     */
    @Query("""
        DELETE FROM task_status_table 
        WHERE notifId IN (
            SELECT id FROM notifications_table WHERE source = :courseName
        )
    """)
    fun deleteStatusesForCourse(courseName: String)
}