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
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Displays the list of academic courses and handles add/edit/delete.
 * This is the course-list logic that previously lived in MainActivity,
 * now extracted into its own Activity for the bottom-nav architecture.
 */
class AcademicsActivity : BaseActivity() {

    private lateinit var recyclerView:     RecyclerView
    private lateinit var layoutEmptyState: LinearLayout
    private lateinit var fab:              FloatingActionButton

    private var academicCourses = listOf<CourseModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_academics)

        recyclerView     = findViewById(R.id.recyclerViewSubjects)
        layoutEmptyState = findViewById(R.id.layoutEmptyState)
        fab              = findViewById(R.id.fabAddCourse)

        recyclerView.layoutManager = LinearLayoutManager(this)

        loadCoursesFromDatabase()

        fab.setOnClickListener { showAddCourseDialog() }
    }

    override fun onResume() {
        super.onResume()
        loadCoursesFromDatabase()
    }

    // ─────────────────────────────────────────────────────────────────────
    // LOAD FROM DB
    // ─────────────────────────────────────────────────────────────────────

    private fun loadCoursesFromDatabase() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db      = AppDatabase.getDatabase(this@AcademicsActivity)
            val courses = db.courseDao().getAllCourses()

            val countMap = courses.associate { course ->
                course.courseName to db.notificationDao().getCountForCourse(course.courseName)
            }

            withContext(Dispatchers.Main) {
                academicCourses = courses
                renderCourseList(academicCourses, countMap)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // RENDER
    // ─────────────────────────────────────────────────────────────────────

    private fun renderCourseList(
        courses: List<CourseModel>,
        notifCounts: Map<String, Int> = emptyMap()
    ) {
        if (courses.isEmpty()) {
            recyclerView.visibility     = View.GONE
            layoutEmptyState.visibility = View.VISIBLE
        } else {
            recyclerView.visibility     = View.VISIBLE
            layoutEmptyState.visibility = View.GONE

            recyclerView.adapter = SubjectAdapter(
                courses         = courses,
                notifCounts     = notifCounts,
                onItemClick     = { course -> openDetailActivity(course) },
                onItemLongClick = { course -> if (course.id > 0) showCourseOptionsSheet(course) }
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // NAVIGATE TO DETAIL
    // ─────────────────────────────────────────────────────────────────────

    private fun openDetailActivity(course: CourseModel) {
        startActivity(Intent(this, DetailActivity::class.java).apply {
            putExtra("COURSE_NAME",   course.courseName)
            putExtra("COURSE_SYMBOL", course.courseSymbol)
        })
    }

    // ─────────────────────────────────────────────────────────────────────
    // LONG PRESS — Edit / Delete
    // ─────────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────
    // CONFIRM DELETE
    // ─────────────────────────────────────────────────────────────────────

    private fun confirmDeleteCourse(course: CourseModel) {
        AlertDialog.Builder(this)
            .setTitle("Delete \"${course.courseName}\"?")
            .setMessage("This will permanently delete the course and all its notifications.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val db = AppDatabase.getDatabase(this@AcademicsActivity)
                    db.courseDao().deleteCourse(course)
                    db.notificationDao().deleteNotificationsForCourse(course.courseName)
                    db.taskStatusDao().deleteStatusesForCourse(course.courseName)

                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@AcademicsActivity,
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

    // ─────────────────────────────────────────────────────────────────────
    // EDIT COURSE DIALOG
    // ─────────────────────────────────────────────────────────────────────

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
            val updated = course.copy(
                courseName        = newName,
                courseSymbol      = newSymbol.uppercase(),
                courseId          = newId.uppercase(),
                facultyName       = etFacultyName.text.toString().trim().ifEmpty { null },
                whatsappGroupName = etWhatsappGroup.text.toString().trim().ifEmpty { null },
                classroomName     = etClassroomName.text.toString().trim().ifEmpty { null }
            )

            lifecycleScope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(this@AcademicsActivity)
                db.courseDao().updateCourse(updated)
                if (oldName != newName) {
                    db.notificationDao().updateCourseNameInNotifications(
                        oldName = oldName, newName = newName
                    )
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AcademicsActivity, "✅ \"$newName\" updated!", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    loadCoursesFromDatabase()
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // ADD COURSE DIALOG
    // ─────────────────────────────────────────────────────────────────────

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
                val db = AppDatabase.getDatabase(this@AcademicsActivity)
                db.courseDao().insertCourse(newCourse)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AcademicsActivity, "✅ $name added!", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    loadCoursesFromDatabase()
                }
            }
        }
    }
}