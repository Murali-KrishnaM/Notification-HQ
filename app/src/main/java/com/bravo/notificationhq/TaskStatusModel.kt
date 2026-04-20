package com.bravo.notificationhq

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Stores the student's personal confidence status for a single notification.
 * Keyed by the notification's Room-generated id from [NotificationModel].
 *
 * Status values (use the companion object constants):
 *   STATUS_NOT_STARTED  = "NOT_STARTED"  (default)
 *   STATUS_IN_PROGRESS  = "IN_PROGRESS"
 *   STATUS_SUBMITTED    = "SUBMITTED"
 */
@Entity(tableName = "task_status_table")
data class TaskStatusModel(
    @PrimaryKey val notifId: Int,   // FK to NotificationModel.id
    val status: String              // one of the STATUS_* constants below
) {
    companion object {
        const val STATUS_NOT_STARTED = "NOT_STARTED"
        const val STATUS_IN_PROGRESS = "IN_PROGRESS"
        const val STATUS_SUBMITTED   = "SUBMITTED"
    }
}