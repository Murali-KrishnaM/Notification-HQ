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

class NotificationAdapter(
    private val notifications: MutableList<NotificationModel>,
    private val statusMap: MutableMap<Int, String>,
    private val scope: CoroutineScope,
    private val onListChanged: () -> Unit
) : RecyclerView.Adapter<NotificationAdapter.ViewHolder>() {

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
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_notification, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val notif = notifications[position]
        val text  = notif.text ?: ""

        holder.tvTitle.text = notif.title ?: "Unknown"

        val cleanText = text.replace("🔴 URGENT", "").replace("🟡 DUE", "").trim()
        holder.tvMessage.text = cleanText

        val sourceLabel = when {
            notif.packageSource.contains("whatsapp",  ignoreCase = true) -> "WhatsApp"
            notif.packageSource.contains("gmail",     ignoreCase = true) -> "Gmail"
            notif.packageSource.contains("classroom", ignoreCase = true) -> "Classroom"
            else -> (notif.source ?: "").take(12)
        }
        holder.tvSource.text = sourceLabel

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
                    holder.itemView.context.resources.getIdentifier("chip_due_dark", "drawable", holder.itemView.context.packageName).takeIf { it != 0 } ?: R.drawable.chip_urgent_dark
                )
                holder.viewAccentBar.setBackgroundColor(colorNeonAmber)
            }
            else -> {
                holder.tvPriorityChip.visibility = View.GONE
                holder.viewAccentBar.setBackgroundColor(colorNeonGreen)
            }
        }

        val currentStatus = statusMap[notif.id] ?: TaskStatusModel.STATUS_NOT_STARTED
        bindStatusPill(holder.tvStatusPill, currentStatus)

        holder.itemView.setOnClickListener {
            val context = holder.itemView.context
            context.startActivity(Intent(context, FullNotificationActivity::class.java).apply {
                putExtra("NOTIF_TITLE",     notif.title ?: "")
                putExtra("NOTIF_TEXT",      notif.text  ?: "")
                putExtra("NOTIF_SOURCE",    notif.source ?: "")
                putExtra("NOTIF_PACKAGE",   notif.packageSource)
                putExtra("NOTIF_TIMESTAMP", notif.timestamp)
            })
        }

        holder.itemView.setOnLongClickListener {
            val snapshotPos = holder.adapterPosition
            if (snapshotPos == RecyclerView.NO_ID.toInt() || snapshotPos < 0 || snapshotPos >= notifications.size) return@setOnLongClickListener true
            showManagementDialog(holder, notif, snapshotPos)
            true
        }
    }

    override fun getItemCount() = notifications.size

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
                pill.text = "⬜ NOT STARTED"
                pill.setTextColor(colorMuted)
                pill.backgroundTintList = ColorStateList.valueOf(0x226B7280.toInt())
            }
        }
    }

    private fun showManagementDialog(holder: ViewHolder, notif: NotificationModel, snapshotPos: Int) {
        val context = holder.itemView.context
        AlertDialog.Builder(context)
            .setTitle(notif.title?.take(40) ?: "Notification")
            .setItems(arrayOf("📋  Update Status", "🗑️  Delete Notification")) { _, which ->
                when (which) {
                    0 -> showStatusChooser(holder, notif)
                    1 -> confirmDelete(context, notif, snapshotPos)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showStatusChooser(holder: ViewHolder, notif: NotificationModel) {
        val context = holder.itemView.context
        val options = arrayOf("⬜  Not Started", "🔄  In Progress", "✅  Submitted")
        val statusValues = arrayOf(TaskStatusModel.STATUS_NOT_STARTED, TaskStatusModel.STATUS_IN_PROGRESS, TaskStatusModel.STATUS_SUBMITTED)

        val currentStatus = statusMap[notif.id] ?: TaskStatusModel.STATUS_NOT_STARTED
        val currentIndex  = statusValues.indexOf(currentStatus).coerceAtLeast(0)

        AlertDialog.Builder(context)
            .setTitle("Update Task Status")
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                val chosenStatus = statusValues[which]
                statusMap[notif.id] = chosenStatus
                bindStatusPill(holder.tvStatusPill, chosenStatus)

                scope.launch(Dispatchers.IO) {
                    val db = AppDatabase.getDatabase(context.applicationContext)
                    db.taskStatusDao().upsertStatus(TaskStatusModel(notifId = notif.id, status = chosenStatus))

                    withContext(Dispatchers.Main) {
                        onListChanged()
                        // 🟢 TRIGGER WIDGET REFRESH
                        DashboardWidgetProvider.triggerWidgetUpdate(context.applicationContext)
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(context: android.content.Context, notif: NotificationModel, snapshotPos: Int) {
        AlertDialog.Builder(context)
            .setTitle("Delete Notification?")
            .setMessage("This will permanently remove this notification from the feed.")
            .setPositiveButton("Delete") { _, _ ->
                if (snapshotPos < 0 || snapshotPos >= notifications.size) return@setPositiveButton

                scope.launch(Dispatchers.IO) {
                    val db = AppDatabase.getDatabase(context.applicationContext)
                    db.notificationDao().deleteNotificationById(notif.id)
                    db.taskStatusDao().deleteStatus(notif.id)

                    withContext(Dispatchers.Main) {
                        if (snapshotPos < notifications.size) {
                            notifications.removeAt(snapshotPos)
                            statusMap.remove(notif.id)
                            notifyItemRemoved(snapshotPos)
                            notifyItemRangeChanged(snapshotPos, notifications.size)
                        }
                        Toast.makeText(context, "🗑️ Notification deleted", Toast.LENGTH_SHORT).show()
                        onListChanged()
                        // 🟢 TRIGGER WIDGET REFRESH
                        DashboardWidgetProvider.triggerWidgetUpdate(context.applicationContext)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}