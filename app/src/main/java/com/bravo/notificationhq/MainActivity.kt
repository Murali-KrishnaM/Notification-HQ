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
    private lateinit var tabLayout: TabLayout

    // This will hold your actual database courses
    private var myRealCourses = listOf<SubjectModel>()

    // Hardcoded streams for the other tabs (for the demo)
    private val placementsList = listOf(
        SubjectModel("Placement Cell Updates", "Placement Cell"),
        SubjectModel("Interview Schedules", "HR Updates")
    )

    private val clubsList = listOf(
        SubjectModel("Coding Club", "Dev Core Team"),
        SubjectModel("Photography Club", "Shutterbugs")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.recyclerViewSubjects)
        recyclerView.layoutManager = LinearLayoutManager(this)
        tabLayout = findViewById(R.id.tabLayout)

        // Set an empty adapter initially so it doesn't crash
        adapter = SubjectAdapter(emptyList())
        recyclerView.adapter = adapter

        // 1. Load the real data from the database!
        loadCoursesFromDatabase()

        // 2. Listen for Tab Clicks to swap the data
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> updateList(myRealCourses)   // Academics Tab (Dynamic!)
                    1 -> updateList(placementsList)  // Placements Tab
                    2 -> updateList(clubsList)       // Clubs Tab
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // 3. THE FAB CLICK LISTENER
        val fabAddCourse = findViewById<FloatingActionButton>(R.id.fabAddCourse)
        fabAddCourse.setOnClickListener {
            showAddCourseDialog()
        }
    }

    // ----------------------------------------------------
    // FETCH DATA FROM ROOM DB
    // ----------------------------------------------------
    private fun loadCoursesFromDatabase() {
        CoroutineScope(Dispatchers.IO).launch {
            val db = AppDatabase.getDatabase(this@MainActivity)
            val savedCourses = db.courseDao().getAllCourses()

            // Convert the Database CourseModel into the SubjectModel our UI uses
            // We set the targetGroup to the courseName so our Detail Activity knows what to look for!
            val mappedCourses = savedCourses.map { course ->
                SubjectModel(course.courseName, course.courseName)
            }

            withContext(Dispatchers.Main) {
                myRealCourses = mappedCourses

                // If we are currently on the Academics tab, refresh the screen immediately
                if (tabLayout.selectedTabPosition == 0) {
                    updateList(myRealCourses)
                }
            }
        }
    }

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
                    val newCourse = CourseModel(
                        courseName = courseName,
                        whatsappGroupName = whatsapp,
                        teacherName = teacher,
                        classroomName = classroom
                    )

                    CoroutineScope(Dispatchers.IO).launch {
                        val db = AppDatabase.getDatabase(this@MainActivity)
                        db.courseDao().insertCourse(newCourse)

                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Course Saved!", Toast.LENGTH_SHORT).show()
                            // Refresh the list automatically!
                            loadCoursesFromDatabase()
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