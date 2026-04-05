package com.bravo.notificationhq

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Delete
import androidx.room.Update

@Dao
interface CourseDao {

    // All saved courses
    @Query("SELECT * FROM courses_table ORDER BY courseName ASC")
    fun getAllCourses(): List<CourseModel>

    // Save a new course
    @Insert
    fun insertCourse(course: CourseModel)

    // Update an existing course
    @Update
    fun updateCourse(course: CourseModel)

    // Delete a course
    @Delete
    fun deleteCourse(course: CourseModel)
}