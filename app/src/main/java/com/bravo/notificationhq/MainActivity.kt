package com.bravo.notificationhq

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import android.app.AlertDialog
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Toast
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: SubjectAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.recyclerViewSubjects)
        recyclerView.layoutManager = LinearLayoutManager(this)

        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)

        // 1. Define our Segregated Streams (Defaults)
        val academicsList = listOf(
            SubjectModel("Design Thinking", "Secret Teleport"),
            SubjectModel("Google Classroom", "Classroom"),
            SubjectModel("Important Emails", "Gmail")
        )

        val placementsList = listOf(
            SubjectModel("Placement Cell Updates", "Placement Cell"),
            SubjectModel("Interview Schedules", "HR Updates")
        )

        val clubsList = listOf(
            SubjectModel("Coding Club", "Dev Core Team"),
            SubjectModel("Photography Club", "Shutterbugs")
        )

        // 2. Set the default view (Academics) when the app opens
        adapter = SubjectAdapter(academicsList)
        recyclerView.adapter = adapter

        // 3. Listen for Tab Clicks to swap the data
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> updateList(academicsList)   // Academics Tab
                    1 -> updateList(placementsList)  // Placements Tab
                    2 -> updateList(clubsList)       // Clubs Tab
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // ----------------------------------------------------
        // THE FAB CLICK LISTENER
        // ----------------------------------------------------
        val fabAddCourse = findViewById<FloatingActionButton>(R.id.fabAddCourse)
        fabAddCourse.setOnClickListener {
            showAddCourseDialog()
        }
    } // <-- This is the closing bracket for onCreate()

    // ----------------------------------------------------
    // SHOW DIALOG & SAVE TO DATABASE
    // ----------------------------------------------------
    private fun showAddCourseDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_course, null)

        val etCourseName = dialogView.findViewById<EditText>(R.id.etCourseName)
        val etWhatsapp = dialogView.findViewById<EditText>(R.id.etWhatsappGroup)
        val etTeacher = dialogView.findViewById<EditText>(R.id.etTeacherName)
        val etClassroom = dialogView.findViewById<EditText>(R.id.etClassroomName)

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Save") { dialog, _ ->
                val courseName = etCourseName.text.toString().trim()
                val whatsapp = etWhatsapp.text.toString().trim()
                val teacher = etTeacher.text.toString().trim()
                val classroom = etClassroom.text.toString().trim()

                if (courseName.isNotEmpty()) {
                    // Create the Database Model
                    val newCourse = CourseModel(
                        courseName = courseName,
                        whatsappGroupName = whatsapp,
                        teacherName = teacher,
                        classroomName = classroom
                    )

                    // Save it in the background thread
                    CoroutineScope(Dispatchers.IO).launch {
                        val db = AppDatabase.getDatabase(this@MainActivity)
                        db.courseDao().insertCourse(newCourse)

                        // Switch back to Main thread to show the success message
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Course Saved!", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    Toast.makeText(this, "Course Name is required!", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
            .show()
    }

    // Helper function to smoothly swap the data in the list
    private fun updateList(newList: List<SubjectModel>) {
        adapter = SubjectAdapter(newList)
        recyclerView.adapter = adapter
    }
}