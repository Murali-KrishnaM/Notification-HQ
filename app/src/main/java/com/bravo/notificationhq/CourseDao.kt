package com.bravo.notificationhq

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface CourseDao {
    // Fetches all the courses the user has created
    @Query("SELECT * FROM courses_table")
    fun getAllCourses(): List<CourseModel>

    // Saves a new course to the database
    @Insert
    fun insertCourse(course: CourseModel)
}