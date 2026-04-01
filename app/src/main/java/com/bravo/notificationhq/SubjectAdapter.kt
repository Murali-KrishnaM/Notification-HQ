package com.bravo.notificationhq

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SubjectAdapter(
    private val courses: List<CourseModel>,
    // Pass a map of courseId -> notification count from MainActivity
    private val notifCounts: Map<String, Int> = emptyMap(),
    private val onItemClick: (CourseModel) -> Unit
) : RecyclerView.Adapter<SubjectAdapter.ViewHolder>() {

    // Palette of colors for the symbol pill — cycles through courses
    private val symbolColors = listOf(
        "#4CAF50", // green  (brand)
        "#1A73E8", // blue
        "#F57F17", // amber
        "#7B1FA2", // purple
        "#00838F", // teal
        "#D32F2F", // red
        "#2E7D32", // dark green
        "#0277BD"  // dark blue
    )

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvSymbol: TextView = itemView.findViewById(R.id.tvSubjectSymbol)
        val tvName: TextView = itemView.findViewById(R.id.tvSubjectName)
        val tvCourseId: TextView = itemView.findViewById(R.id.tvCourseId)
        val tvBadge: TextView = itemView.findViewById(R.id.tvNotifCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_subject, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val course = courses[position]

        // ── Symbol pill ────────────────────────────────────────
        // Use courseSymbol if available, else first 2-3 chars of name
        val symbol = when {
            !course.courseSymbol.isNullOrBlank() ->
                course.courseSymbol.take(4)
            !course.courseName.isNullOrBlank() ->
                course.courseName.take(3).uppercase()
            else -> "?"
        }
        holder.tvSymbol.text = symbol

        // Assign a consistent color based on position in list
        val colorHex = symbolColors[position % symbolColors.size]
        holder.tvSymbol.background.setTint(
            android.graphics.Color.parseColor(colorHex)
        )

        // ── Name & Course ID ───────────────────────────────────
        holder.tvName.text = course.courseName ?: "Unnamed Course"

        val idText = buildString {
            if (!course.courseId.isNullOrBlank()) append(course.courseId)
            if (!course.courseSymbol.isNullOrBlank() && !course.courseId.isNullOrBlank()) append(" · ")
            if (!course.courseSymbol.isNullOrBlank()) append(course.courseSymbol)
        }
        holder.tvCourseId.text = idText

        // ── Notification count badge ───────────────────────────
        val count = notifCounts[course.courseId] ?: 0
        if (count > 0) {
            holder.tvBadge.visibility = View.VISIBLE
            holder.tvBadge.text = if (count > 99) "99+" else count.toString()
        } else {
            holder.tvBadge.visibility = View.GONE
        }

        // ── Click ──────────────────────────────────────────────
        holder.itemView.setOnClickListener { onItemClick(course) }
    }

    override fun getItemCount() = courses.size
}
