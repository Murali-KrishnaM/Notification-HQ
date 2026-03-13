package com.bravo.notificationhq

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "notifications_table")
data class NotificationModel(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val text: String,
    val source: String
)