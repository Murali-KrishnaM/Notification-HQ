package com.bravo.notificationhq

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "courses_table")
data class CourseModel(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val courseName: String,
    val courseSymbol: String,
    val courseId: String,
    val facultyName: String,
    val whatsappGroupName: String,
    val classroomName: String
)