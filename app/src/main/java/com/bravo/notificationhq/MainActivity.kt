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
import com.google.android.material.bottomsheet.BottomSheetDialog
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

    private var academicCourses = listOf<CourseModel>()

    private val placementCourses = listOf(
        CourseModel(id = -1, courseName = "Placement Cell Updates", courseSymbol = "PLAC", courseId = "PLAC-01"),
        CourseModel(id = -2, courseName = "Interview Schedules",    courseSymbol = "INTR", courseId = "INTR-01")
    )
    private val clubCourses = listOf(
        CourseModel(id = -3, courseName = "Coding Club",      courseSymbol = "CODE", courseId = "CLUB-01"),
        CourseModel(id = -4, courseName = "Photography Club", courseSymbol = "PHOT", courseId = "CLUB-02")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView     = findViewById(R.id.recyclerViewSubjects)
        tabLayout        = findViewById(R.id.tabLayout)
        layoutEmptyState = findViewById(R.id.layoutEmptyState)

        recyclerView.layoutManager = LinearLayoutManager(this)

        loadCoursesFromDatabase()

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

            val countMap = courses.associate { course ->
                course.courseName to db.notificationDao().getCountForCourse(course.courseName)
            }

            withContext(Dispatchers.Main) {
                academicCourses = courses
                if (tabLayout.selectedTabPosition == 0) {
                    renderCourseList(academicCourses, isDynamic = true, notifCounts = countMap)
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RENDER COURSE LIST
    // ─────────────────────────────────────────────────────────────────────────
    private fun renderCourseList(
        courses: List<CourseModel>,
        isDynamic: Boolean,
        notifCounts: Map<String, Int> = emptyMap()
    ) {
        if (courses.isEmpty() && isDynamic) {
            recyclerView.visibility     = View.GONE
            layoutEmptyState.visibility = View.VISIBLE
        } else {
            recyclerView.visibility     = View.VISIBLE
            layoutEmptyState.visibility = View.GONE

            recyclerView.adapter = SubjectAdapter(
                courses      = courses,
                notifCounts  = notifCounts,
                onItemClick  = { course -> openDetailActivity(course) },
                onItemLongClick = { course ->
                    // Only allow edit/delete on real DB courses (not placeholder tabs)
                    if (course.id > 0) showCourseOptionsSheet(course)
                }
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NAVIGATE TO DETAIL
    // ─────────────────────────────────────────────────────────────────────────
    private fun openDetailActivity(course: CourseModel) {
        val intent = Intent(this, DetailActivity::class.java).apply {
            putExtra("COURSE_NAME",   course.courseName)
            putExtra("COURSE_SYMBOL", course.courseSymbol)
        }
        startActivity(intent)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LONG-PRESS BOTTOM SHEET — Edit / Delete
    // ─────────────────────────────────────────────────────────────────────────
    private fun showCourseOptionsSheet(course: CourseModel) {
        val sheet     = BottomSheetDialog(this)
        val sheetView = LayoutInflater.from(this).inflate(
            android.R.layout.simple_list_item_1, null
        )

        // Build a simple list manually inside an AlertDialog-style sheet
        val items    = arrayOf("✏️  Edit Course", "🗑️  Delete Course")
        val builder  = AlertDialog.Builder(this)
            .setTitle(course.courseName)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showEditCourseDialog(course)
                    1 -> confirmDeleteCourse(course)
                }
            }
            .setNegativeButton("Cancel", null)
        builder.show()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONFIRM DELETE
    // ─────────────────────────────────────────────────────────────────────────
    private fun confirmDeleteCourse(course: CourseModel) {
        AlertDialog.Builder(this)
            .setTitle("Delete \"${course.courseName}\"?")
            .setMessage("This will permanently delete the course and all its notifications. This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                CoroutineScope(Dispatchers.IO).launch {
                    val db = AppDatabase.getDatabase(this@MainActivity)
                    db.courseDao().deleteCourse(course)
                    // Also delete all notifications routed to this course
                    db.notificationDao().deleteNotificationsForCourse(course.courseName)

                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            "🗑️ \"${course.courseName}\" deleted",
                            Toast.LENGTH_SHORT
                        ).show()
                        loadCoursesFromDatabase()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // EDIT COURSE DIALOG — pre-filled
    // ─────────────────────────────────────────────────────────────────────────
    private fun showEditCourseDialog(course: CourseModel) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_course, null)

        val tilCourseName   = dialogView.findViewById<TextInputLayout>(R.id.tilCourseName)
        val tilCourseSymbol = dialogView.findViewById<TextInputLayout>(R.id.tilCourseSymbol)
        val tilCourseId     = dialogView.findViewById<TextInputLayout>(R.id.tilCourseId)

        val etCourseName    = dialogView.findViewById<TextInputEditText>(R.id.etCourseName)
        val etCourseSymbol  = dialogView.findViewById<TextInputEditText>(R.id.etCourseSymbol)
        val etCourseId      = dialogView.findViewById<TextInputEditText>(R.id.etCourseId)
        val etFacultyName   = dialogView.findViewById<TextInputEditText>(R.id.etFacultyName)
        val etWhatsappGroup = dialogView.findViewById<TextInputEditText>(R.id.etWhatsappGroup)
        val etClassroomName = dialogView.findViewById<TextInputEditText>(R.id.etClassroomName)

        // ── Pre-fill all fields with existing data ─────────────
        etCourseName.setText(course.courseName)
        etCourseSymbol.setText(course.courseSymbol)
        etCourseId.setText(course.courseId)
        etFacultyName.setText(course.facultyName ?: "")
        etWhatsappGroup.setText(course.whatsappGroupName ?: "")
        etClassroomName.setText(course.classroomName ?: "")

        val dialog = AlertDialog.Builder(this)
            .setTitle("Edit Course")
            .setView(dialogView)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {

            tilCourseName.error   = null
            tilCourseSymbol.error = null
            tilCourseId.error     = null

            val newName   = etCourseName.text.toString().trim()
            val newSymbol = etCourseSymbol.text.toString().trim()
            val newId     = etCourseId.text.toString().trim()

            var isValid = true
            if (newName.isEmpty()) {
                tilCourseName.error = "Course name is required"
                isValid = false
            }
            if (newSymbol.isEmpty()) {
                tilCourseSymbol.error = "Course symbol is required"
                isValid = false
            }
            if (newId.isEmpty()) {
                tilCourseId.error = "Course ID is required"
                isValid = false
            }
            if (!isValid) return@setOnClickListener

            val oldCourseName = course.courseName  // capture before overwrite

            val updatedCourse = course.copy(
                courseName        = newName,
                courseSymbol      = newSymbol.uppercase(),
                courseId          = newId.uppercase(),
                facultyName       = etFacultyName.text.toString().trim().ifEmpty { null },
                whatsappGroupName = etWhatsappGroup.text.toString().trim().ifEmpty { null },
                classroomName     = etClassroomName.text.toString().trim().ifEmpty { null }
            )

            CoroutineScope(Dispatchers.IO).launch {
                val db = AppDatabase.getDatabase(this@MainActivity)
                db.courseDao().updateCourse(updatedCourse)

                // Re-link all old notifications to the new course name
                if (oldCourseName != newName) {
                    db.notificationDao().updateCourseNameInNotifications(
                        oldName = oldCourseName,
                        newName = newName
                    )
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "✅ \"${newName}\" updated!",
                        Toast.LENGTH_SHORT
                    ).show()
                    dialog.dismiss()
                    loadCoursesFromDatabase()
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ADD COURSE DIALOG
    // ─────────────────────────────────────────────────────────────────────────
    private fun showAddCourseDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_course, null)

        val tilCourseName   = dialogView.findViewById<TextInputLayout>(R.id.tilCourseName)
        val tilCourseSymbol = dialogView.findViewById<TextInputLayout>(R.id.tilCourseSymbol)
        val tilCourseId     = dialogView.findViewById<TextInputLayout>(R.id.tilCourseId)

        val etCourseName    = dialogView.findViewById<TextInputEditText>(R.id.etCourseName)
        val etCourseSymbol  = dialogView.findViewById<TextInputEditText>(R.id.etCourseSymbol)
        val etCourseId      = dialogView.findViewById<TextInputEditText>(R.id.etCourseId)
        val etFacultyName   = dialogView.findViewById<TextInputEditText>(R.id.etFacultyName)
        val etWhatsappGroup = dialogView.findViewById<TextInputEditText>(R.id.etWhatsappGroup)
        val etClassroomName = dialogView.findViewById<TextInputEditText>(R.id.etClassroomName)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {

            tilCourseName.error   = null
            tilCourseSymbol.error = null
            tilCourseId.error     = null

            val name   = etCourseName.text.toString().trim()
            val symbol = etCourseSymbol.text.toString().trim()
            val id     = etCourseId.text.toString().trim()

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
            if (!isValid) return@setOnClickListener

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
                    Toast.makeText(this@MainActivity, "✅ $name added!", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    loadCoursesFromDatabase()
                }
            }
        }
    }
}