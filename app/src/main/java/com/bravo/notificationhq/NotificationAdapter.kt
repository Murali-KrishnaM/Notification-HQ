package com.bravo.notificationhq

import android.content.Intent
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Adapter for the per-course notification feed shown in [DetailActivity].
 *
 * Sprint 2B additions:
 *  - Accepts a [statusMap] (notifId → status string) pre-loaded from DB.
 *  - Binds the status pill on each card with the correct label + color.
 *  - Long-press on any card shows a two-option dialog:
 *      1. Update Status  → three-option chooser (Not Started / In Progress / Submitted)
 *      2. Delete         → confirmation dialog, removes from DB + refreshes list
 *  - [onListChanged] callback lets [DetailActivity] refresh the RecyclerView
 *    after a delete without needing to know about the adapter internals.
 */
class NotificationAdapter(
    private val notifications: MutableList<NotificationModel>,
    private val statusMap: MutableMap<Int, String>,
    private val onListChanged: () -> Unit
) : RecyclerView.Adapter<NotificationAdapter.ViewHolder>() {

    // ── Neon accent palette ────────────────────────────────────────────────
    private val colorNeonGreen = 0xFF00E5A0.toInt()
    private val colorNeonRed   = 0xFFFF4D6A.toInt()
    private val colorNeonAmber = 0xFFFFB340.toInt()
    private val colorMuted     = 0xFF6B7280.toInt()

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvTitle:        TextView = itemView.findViewById(R.id.tvTitle)
        val tvMessage:      TextView = itemView.findViewById(R.id.tvMessage)
        val tvSource:       TextView = itemView.findViewById(R.id.tvSource)
        val tvPriorityChip: TextView = itemView.findViewById(R.id.tvPriorityChip)
        val viewAccentBar:  View     = itemView.findViewById(R.id.viewAccentBar)
        val tvStatusPill:   TextView = itemView.findViewById(R.id.tvStatusPill)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_notification, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val notif = notifications[position]
        val text  = notif.text ?: ""

        // ── Title ──────────────────────────────────────────────────────────
        holder.tvTitle.text = notif.title ?: "Unknown"

        // ── Clean message body (strip priority prefix tags) ────────────────
        val cleanText = text
            .replace("🔴 URGENT", "")
            .replace("🟡 DUE", "")
            .trim()
        holder.tvMessage.text = cleanText

        // ── Source chip ────────────────────────────────────────────────────
        val sourceLabel = when {
            notif.packageSource.contains("whatsapp",  ignoreCase = true) -> "WhatsApp"
            notif.packageSource.contains("gmail",     ignoreCase = true) -> "Gmail"
            notif.packageSource.contains("classroom", ignoreCase = true) -> "Classroom"
            else -> (notif.source ?: "").take(12)
        }
        holder.tvSource.text = sourceLabel

        // ── Priority chip + accent bar ─────────────────────────────────────
        when {
            text.contains("🔴 URGENT") -> {
                holder.tvPriorityChip.visibility = View.VISIBLE
                holder.tvPriorityChip.text = "🔴 URGENT"
                holder.tvPriorityChip.setBackgroundResource(R.drawable.chip_urgent_dark)
                holder.viewAccentBar.setBackgroundColor(colorNeonRed)
            }
            text.contains("🟡 DUE") -> {
                holder.tvPriorityChip.visibility = View.VISIBLE
                holder.tvPriorityChip.text = "🟡 DUE"
                holder.tvPriorityChip.setBackgroundResource(
                    holder.itemView.context.resources.getIdentifier(
                        "chip_due_dark", "drawable", holder.itemView.context.packageName
                    ).takeIf { it != 0 } ?: R.drawable.chip_urgent_dark
                )
                holder.viewAccentBar.setBackgroundColor(colorNeonAmber)
            }
            else -> {
                holder.tvPriorityChip.visibility = View.GONE
                holder.viewAccentBar.setBackgroundColor(colorNeonGreen)
            }
        }

        // ── Status pill (Task Confidence Tracker) ──────────────────────────
        val currentStatus = statusMap[notif.id] ?: TaskStatusModel.STATUS_NOT_STARTED
        bindStatusPill(holder.tvStatusPill, currentStatus)

        // ── Click — open full notification view ────────────────────────────
        holder.itemView.setOnClickListener {
            val context = holder.itemView.context
            context.startActivity(
                Intent(context, FullNotificationActivity::class.java).apply {
                    putExtra("NOTIF_TITLE",     notif.title ?: "")
                    putExtra("NOTIF_TEXT",      notif.text  ?: "")
                    putExtra("NOTIF_SOURCE",    notif.source ?: "")
                    putExtra("NOTIF_PACKAGE",   notif.packageSource)
                    putExtra("NOTIF_TIMESTAMP", notif.timestamp)
                }
            )
        }

        // ── Long-press — two-option management sheet ───────────────────────
        holder.itemView.setOnLongClickListener {
            showManagementDialog(holder, notif, position)
            true
        }
    }

    override fun getItemCount() = notifications.size

    // ─────────────────────────────────────────────────────────────────────
    // STATUS PILL BINDING
    // ─────────────────────────────────────────────────────────────────────

    private fun bindStatusPill(pill: TextView, status: String) {
        when (status) {
            TaskStatusModel.STATUS_IN_PROGRESS -> {
                pill.text = "🔄 IN PROGRESS"
                pill.setTextColor(colorNeonAmber)
                pill.backgroundTintList = ColorStateList.valueOf(0x33FFB340.toInt())
            }
            TaskStatusModel.STATUS_SUBMITTED -> {
                pill.text = "✅ SUBMITTED"
                pill.setTextColor(colorNeonGreen)
                pill.backgroundTintList = ColorStateList.valueOf(0x3300E5A0.toInt())
            }
            else -> {
                // NOT_STARTED (default)
                pill.text = "⬜ NOT STARTED"
                pill.setTextColor(colorMuted)
                pill.backgroundTintList = ColorStateList.valueOf(0x226B7280.toInt())
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // LONG-PRESS MANAGEMENT DIALOG
    // Two options: Update Status | Delete Notification
    // ─────────────────────────────────────────────────────────────────────

    private fun showManagementDialog(
        holder: ViewHolder,
        notif: NotificationModel,
        position: Int
    ) {
        val context = holder.itemView.context

        AlertDialog.Builder(context)
            .setTitle(notif.title?.take(40) ?: "Notification")
            .setItems(arrayOf("📋  Update Status", "🗑️  Delete Notification")) { _, which ->
                when (which) {
                    0 -> showStatusChooser(holder, notif)
                    1 -> confirmDelete(context, notif, position)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─────────────────────────────────────────────────────────────────────
    // STATUS CHOOSER
    // Three status options displayed as a single-choice list.
    // ─────────────────────────────────────────────────────────────────────

    private fun showStatusChooser(holder: ViewHolder, notif: NotificationModel) {
        val context = holder.itemView.context

        val options = arrayOf(
            "⬜  Not Started",
            "🔄  In Progress",
            "✅  Submitted"
        )

        val statusValues = arrayOf(
            TaskStatusModel.STATUS_NOT_STARTED,
            TaskStatusModel.STATUS_IN_PROGRESS,
            TaskStatusModel.STATUS_SUBMITTED
        )

        // Highlight the currently selected option
        val currentStatus = statusMap[notif.id] ?: TaskStatusModel.STATUS_NOT_STARTED
        val currentIndex  = statusValues.indexOf(currentStatus).coerceAtLeast(0)

        AlertDialog.Builder(context)
            .setTitle("Update Task Status")
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                val chosenStatus = statusValues[which]

                // Update local map immediately for instant UI feedback
                statusMap[notif.id] = chosenStatus
                bindStatusPill(holder.tvStatusPill, chosenStatus)

                // Persist to DB on IO thread
                CoroutineScope(Dispatchers.IO).launch {
                    val db = AppDatabase.getDatabase(context)
                    db.taskStatusDao().upsertStatus(
                        TaskStatusModel(notifId = notif.id, status = chosenStatus)
                    )
                }

                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─────────────────────────────────────────────────────────────────────
    // DELETE NOTIFICATION
    // Confirms, removes from DB, removes from local list, animates out.
    // ─────────────────────────────────────────────────────────────────────

    private fun confirmDelete(
        context: android.content.Context,
        notif: NotificationModel,
        position: Int
    ) {
        AlertDialog.Builder(context)
            .setTitle("Delete Notification?")
            .setMessage("This will permanently remove this notification from the feed.")
            .setPositiveButton("Delete") { _, _ ->
                CoroutineScope(Dispatchers.IO).launch {
                    val db = AppDatabase.getDatabase(context)

                    // Delete notification and its status entry
                    db.notificationDao().deleteNotificationById(notif.id)
                    db.taskStatusDao().deleteStatus(notif.id)

                    withContext(Dispatchers.Main) {
                        // Remove from local list and animate the card out
                        if (position < notifications.size) {
                            notifications.removeAt(position)
                            statusMap.remove(notif.id)
                            notifyItemRemoved(position)
                            notifyItemRangeChanged(position, notifications.size)
                        }

                        Toast.makeText(context, "🗑️ Notification deleted", Toast.LENGTH_SHORT).show()

                        // Tell DetailActivity to check if list is now empty
                        onListChanged()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}