package com.bravo.notificationhq

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Delete

@Dao
interface CourseDao {

    // All saved courses
    @Query("SELECT * FROM courses_table ORDER BY courseName ASC")
    fun getAllCourses(): List<CourseModel>

    // Save a new course
    @Insert
    fun insertCourse(course: CourseModel)

    // Delete a course
    @Delete
    fun deleteCourse(course: CourseModel)
}
