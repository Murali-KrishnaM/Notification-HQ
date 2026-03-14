package com.bravo.notificationhq

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "courses_table")
data class CourseModel(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val courseName: String,        // e.g., "Design Thinking"
    val whatsappGroupName: String, // e.g., "Secret Teleport"
    val teacherName: String,       // e.g., "Mr. Red"
    val classroomName: String      // e.g., "DT 101"
)