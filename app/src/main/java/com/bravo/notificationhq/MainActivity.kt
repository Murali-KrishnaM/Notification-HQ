package com.bravo.notificationhq

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var tabLayout: TabLayout
    private lateinit var layoutEmptyState: LinearLayout
    private lateinit var fab: FloatingActionButton

    private var academicCourses = listOf<CourseModel>()

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
        fab              = findViewById(R.id.fabAddCourse)

        recyclerView.layoutManager = LinearLayoutManager(this)

        loadCoursesFromDatabase()

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        fab.show()
                        renderCourseList(academicCourses, isDynamic = true)
                    }
                    1 -> {
                        fab.hide()
                        recyclerView.visibility     = View.GONE
                        layoutEmptyState.visibility = View.GONE
                        startActivity(Intent(this@MainActivity, PlacementsActivity::class.java))
                        tabLayout.post { tabLayout.getTabAt(0)?.select() }
                    }
                    2 -> {
                        fab.hide()
                        recyclerView.visibility     = View.GONE
                        layoutEmptyState.visibility = View.GONE
                        startActivity(Intent(this@MainActivity, NptelActivity::class.java))
                        tabLayout.post { tabLayout.getTabAt(0)?.select() }
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        fab.setOnClickListener { showAddCourseDialog() }
    }

    override fun onResume() {
        super.onResume()
        if (tabLayout.selectedTabPosition == 0) {
            loadCoursesFromDatabase()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LOAD FROM DB
    // ─────────────────────────────────────────────────────────────────────────
    private fun loadCoursesFromDatabase() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db      = AppDatabase.getDatabase(this@MainActivity)
            val courses = db.courseDao().getAllCourses()

            val countMap = courses.associate { course ->
                course.courseName to db.notificationDao().getCountForCourse(course.courseName)
            }

            withContext(Dispatchers.Main) {
                academicCourses = courses
                if (tabLayout.selectedTabPosition == 0) {
                    renderCourseList(
                        academicCourses,
                        isDynamic   = true,
                        notifCounts = countMap
                    )
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
                courses         = courses,
                notifCounts     = notifCounts,
                onItemClick     = { course -> openDetailActivity(course) },
                onItemLongClick = { course ->
                    if (course.id > 0) showCourseOptionsSheet(course)
                }
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NAVIGATE TO DETAIL
    // ─────────────────────────────────────────────────────────────────────────
    private fun openDetailActivity(course: CourseModel) {
        startActivity(Intent(this, DetailActivity::class.java).apply {
            putExtra("COURSE_NAME",   course.courseName)
            putExtra("COURSE_SYMBOL", course.courseSymbol)
        })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LONG PRESS — Edit / Delete
    // ─────────────────────────────────────────────────────────────────────────
    private fun showCourseOptionsSheet(course: CourseModel) {
        AlertDialog.Builder(this)
            .setTitle(course.courseName)
            .setItems(arrayOf("✏️  Edit Course", "🗑️  Delete Course")) { _, which ->
                when (which) {
                    0 -> showEditCourseDialog(course)
                    1 -> confirmDeleteCourse(course)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONFIRM DELETE
    // ─────────────────────────────────────────────────────────────────────────
    private fun confirmDeleteCourse(course: CourseModel) {
        AlertDialog.Builder(this)
            .setTitle("Delete \"${course.courseName}\"?")
            .setMessage("This will permanently delete the course and all its notifications.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val db = AppDatabase.getDatabase(this@MainActivity)
                    db.courseDao().deleteCourse(course)
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
    // EDIT COURSE DIALOG
    // ─────────────────────────────────────────────────────────────────────────
    private fun showEditCourseDialog(course: CourseModel) {
        val dialogView      = LayoutInflater.from(this).inflate(R.layout.dialog_add_course, null)
        val tilCourseName   = dialogView.findViewById<TextInputLayout>(R.id.tilCourseName)
        val tilCourseSymbol = dialogView.findViewById<TextInputLayout>(R.id.tilCourseSymbol)
        val tilCourseId     = dialogView.findViewById<TextInputLayout>(R.id.tilCourseId)
        val etCourseName    = dialogView.findViewById<TextInputEditText>(R.id.etCourseName)
        val etCourseSymbol  = dialogView.findViewById<TextInputEditText>(R.id.etCourseSymbol)
        val etCourseId      = dialogView.findViewById<TextInputEditText>(R.id.etCourseId)
        val etFacultyName   = dialogView.findViewById<TextInputEditText>(R.id.etFacultyName)
        val etWhatsappGroup = dialogView.findViewById<TextInputEditText>(R.id.etWhatsappGroup)
        val etClassroomName = dialogView.findViewById<TextInputEditText>(R.id.etClassroomName)

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
            if (newName.isEmpty())   { tilCourseName.error   = "Required"; isValid = false }
            if (newSymbol.isEmpty()) { tilCourseSymbol.error = "Required"; isValid = false }
            if (newId.isEmpty())     { tilCourseId.error     = "Required"; isValid = false }
            if (!isValid) return@setOnClickListener

            val oldName = course.courseName
            val updatedCourse = course.copy(
                courseName        = newName,
                courseSymbol      = newSymbol.uppercase(),
                courseId          = newId.uppercase(),
                facultyName       = etFacultyName.text.toString().trim().ifEmpty { null },
                whatsappGroupName = etWhatsappGroup.text.toString().trim().ifEmpty { null },
                classroomName     = etClassroomName.text.toString().trim().ifEmpty { null }
            )

            lifecycleScope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(this@MainActivity)
                db.courseDao().updateCourse(updatedCourse)
                if (oldName != newName) {
                    db.notificationDao().updateCourseNameInNotifications(
                        oldName = oldName,
                        newName = newName
                    )
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "✅ \"$newName\" updated!",
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
        val dialogView      = LayoutInflater.from(this).inflate(R.layout.dialog_add_course, null)
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
            if (name.isEmpty())   { tilCourseName.error   = "Course name is required"; isValid = false }
            if (symbol.isEmpty()) { tilCourseSymbol.error = "Course symbol is required"; isValid = false }
            if (id.isEmpty())     { tilCourseId.error     = "Course ID is required"; isValid = false }
            if (!isValid) return@setOnClickListener

            val newCourse = CourseModel(
                courseName        = name,
                courseSymbol      = symbol.uppercase(),
                courseId          = id.uppercase(),
                facultyName       = etFacultyName.text.toString().trim().ifEmpty { null },
                whatsappGroupName = etWhatsappGroup.text.toString().trim().ifEmpty { null },
                classroomName     = etClassroomName.text.toString().trim().ifEmpty { null }
            )

            lifecycleScope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(this@MainActivity)
                db.courseDao().insertCourse(newCourse)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "✅ $name added!",
                        Toast.LENGTH_SHORT
                    ).show()
                    dialog.dismiss()
                    loadCoursesFromDatabase()
                }
            }
        }
    }
}