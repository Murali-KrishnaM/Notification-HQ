package com.bravo.notificationhq

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var tabLayout: TabLayout
    private lateinit var layoutEmptyState: LinearLayout

    // Cached list of DB courses (used by Academics tab)
    private var academicCourses = listOf<CourseModel>()

    // Hardcoded demo entries for other tabs — each is a fake CourseModel
    // so the same SubjectAdapter can render all three tabs identically
    private val placementCourses = listOf(
        CourseModel(id = -1, courseName = "Placement Cell Updates", courseSymbol = "PLAC", courseId = "PLAC-01"),
        CourseModel(id = -2, courseName = "Interview Schedules",    courseSymbol = "INTR", courseId = "INTR-01")
    )
    private val clubCourses = listOf(
        CourseModel(id = -3, courseName = "Coding Club",        courseSymbol = "CODE", courseId = "CLUB-01"),
        CourseModel(id = -4, courseName = "Photography Club",   courseSymbol = "PHOT", courseId = "CLUB-02")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView    = findViewById(R.id.recyclerViewSubjects)
        tabLayout       = findViewById(R.id.tabLayout)
        layoutEmptyState = findViewById(R.id.layoutEmptyState)

        recyclerView.layoutManager = LinearLayoutManager(this)

        // Load DB data and populate the Academics tab
        loadCoursesFromDatabase()

        // Tab switching
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> renderCourseList(academicCourses, isDynamic = true)
                    1 -> renderCourseList(placementCourses, isDynamic = false)
                    2 -> renderCourseList(clubCourses, isDynamic = false)
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // FAB → Add Course dialog
        findViewById<FloatingActionButton>(R.id.fabAddCourse).setOnClickListener {
            showAddCourseDialog()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LOAD FROM DB
    // ─────────────────────────────────────────────────────────────────────────
    private fun loadCoursesFromDatabase() {
        CoroutineScope(Dispatchers.IO).launch {
            val db      = AppDatabase.getDatabase(this@MainActivity)
            val courses = db.courseDao().getAllCourses()

            // Build notification count badge map: courseName → count
            val countMap = courses.associate { course ->
                course.courseName to db.notificationDao().getCountForCourse(course.courseName)
            }

            withContext(Dispatchers.Main) {
                academicCourses = courses
                // Only refresh if we're on the Academics tab
                if (tabLayout.selectedTabPosition == 0) {
                    renderCourseList(academicCourses, isDynamic = true, notifCounts = countMap)
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RENDER COURSE LIST + EMPTY STATE
    // ─────────────────────────────────────────────────────────────────────────
    private fun renderCourseList(
        courses: List<CourseModel>,
        isDynamic: Boolean,
        notifCounts: Map<String, Int> = emptyMap()
    ) {
        if (courses.isEmpty() && isDynamic) {
            // Show empty state only on the Academics tab (not placement/clubs demo data)
            recyclerView.visibility    = View.GONE
            layoutEmptyState.visibility = View.VISIBLE
        } else {
            recyclerView.visibility    = View.VISIBLE
            layoutEmptyState.visibility = View.GONE

            recyclerView.adapter = SubjectAdapter(
                courses    = courses,
                notifCounts = notifCounts,
                onItemClick = { course -> openDetailActivity(course) }
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NAVIGATE TO DETAIL SCREEN
    // ─────────────────────────────────────────────────────────────────────────
    private fun openDetailActivity(course: CourseModel) {
        val intent = Intent(this, DetailActivity::class.java).apply {
            putExtra("COURSE_NAME", course.courseName)
            putExtra("COURSE_SYMBOL", course.courseSymbol)
        }
        startActivity(intent)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ADD COURSE DIALOG — validated, won't dismiss on empty fields
    // ─────────────────────────────────────────────────────────────────────────
    private fun showAddCourseDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_course, null)

        // TextInputLayouts (for showing error messages)
        val tilCourseName   = dialogView.findViewById<TextInputLayout>(R.id.tilCourseName)
        val tilCourseSymbol = dialogView.findViewById<TextInputLayout>(R.id.tilCourseSymbol)
        val tilCourseId     = dialogView.findViewById<TextInputLayout>(R.id.tilCourseId)

        // EditTexts (for reading values)
        val etCourseName    = dialogView.findViewById<TextInputEditText>(R.id.etCourseName)
        val etCourseSymbol  = dialogView.findViewById<TextInputEditText>(R.id.etCourseSymbol)
        val etCourseId      = dialogView.findViewById<TextInputEditText>(R.id.etCourseId)
        val etFacultyName   = dialogView.findViewById<TextInputEditText>(R.id.etFacultyName)
        val etWhatsappGroup = dialogView.findViewById<TextInputEditText>(R.id.etWhatsappGroup)
        val etClassroomName = dialogView.findViewById<TextInputEditText>(R.id.etClassroomName)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Save", null)   // null — we override below to control dismiss
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .create()

        dialog.show()

        // Override positive button so validation can block auto-dismiss
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {

            // Clear old errors
            tilCourseName.error   = null
            tilCourseSymbol.error = null
            tilCourseId.error     = null

            val name    = etCourseName.text.toString().trim()
            val symbol  = etCourseSymbol.text.toString().trim()
            val id      = etCourseId.text.toString().trim()

            // Validate — all three required fields must be filled
            var isValid = true
            if (name.isEmpty()) {
                tilCourseName.error = "Course name is required"
                isValid = false
            }
            if (symbol.isEmpty()) {
                tilCourseSymbol.error = "Course symbol is required (e.g. NLPA)"
                isValid = false
            }
            if (id.isEmpty()) {
                tilCourseId.error = "Course ID is required (e.g. AD23B32)"
                isValid = false
            }
            if (!isValid) return@setOnClickListener   // don't dismiss — let user fix

            // ✅ Valid — save to DB
            val newCourse = CourseModel(
                courseName        = name,
                courseSymbol      = symbol.uppercase(),
                courseId          = id.uppercase(),
                facultyName       = etFacultyName.text.toString().trim().ifEmpty { null },
                whatsappGroupName = etWhatsappGroup.text.toString().trim().ifEmpty { null },
                classroomName     = etClassroomName.text.toString().trim().ifEmpty { null }
            )

            CoroutineScope(Dispatchers.IO).launch {
                val db = AppDatabase.getDatabase(this@MainActivity)
                db.courseDao().insertCourse(newCourse)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "✅ ${name} added!", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    loadCoursesFromDatabase()   // refresh list
                }
            }
        }
    }
}
