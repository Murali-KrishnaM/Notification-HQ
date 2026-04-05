package com.bravo.notificationhq

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SubjectAdapter(
    private val courses: List<CourseModel>,
    private val notifCounts: Map<String, Int> = emptyMap(),
    private val onItemClick: (CourseModel) -> Unit,
    private val onItemLongClick: (CourseModel) -> Unit  // NEW
) : RecyclerView.Adapter<SubjectAdapter.ViewHolder>() {

    private val symbolColors = listOf(
        "#4CAF50",
        "#1A73E8",
        "#F57F17",
        "#7B1FA2",
        "#00838F",
        "#D32F2F",
        "#2E7D32",
        "#0277BD"
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
        val symbol = when {
            !course.courseSymbol.isNullOrBlank() -> course.courseSymbol.take(4)
            !course.courseName.isNullOrBlank()   -> course.courseName.take(3).uppercase()
            else -> "?"
        }
        holder.tvSymbol.text = symbol

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
        val count = notifCounts[course.courseName] ?: 0
        if (count > 0) {
            holder.tvBadge.visibility = View.VISIBLE
            holder.tvBadge.text = if (count > 99) "99+" else count.toString()
        } else {
            holder.tvBadge.visibility = View.GONE
        }

        // ── Click & Long-press ─────────────────────────────────
        holder.itemView.setOnClickListener { onItemClick(course) }
        holder.itemView.setOnLongClickListener {
            onItemLongClick(course)
            true  // consume the event
        }
    }

    override fun getItemCount() = courses.size
}