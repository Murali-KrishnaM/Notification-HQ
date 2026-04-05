package com.bravo.notificationhq

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class NotificationAdapter(
    private val notifications: List<NotificationModel>
) : RecyclerView.Adapter<NotificationAdapter.ViewHolder>() {

    // ── THE NEON PALETTE FOR ACCENT BARS ──
    private val colorNeonGreen = 0xFF00E5A0.toInt()
    private val colorNeonRed   = 0xFFFF4D6A.toInt()
    private val colorNeonAmber = 0xFFFFB340.toInt()

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvTitle: TextView        = itemView.findViewById(R.id.tvTitle)
        val tvMessage: TextView      = itemView.findViewById(R.id.tvMessage)
        val tvSource: TextView       = itemView.findViewById(R.id.tvSource)
        val tvPriorityChip: TextView = itemView.findViewById(R.id.tvPriorityChip)
        val viewAccentBar: View      = itemView.findViewById(R.id.viewAccentBar)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_notification, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val notif = notifications[position]
        val text  = notif.text ?: ""

        // ── Title ──────────────────────────────────────────────
        holder.tvTitle.text = notif.title ?: "Unknown"

        // ── Clean message body ─────────────────────────────────
        val cleanText = text
            .replace("🚨 URGENT", "")
            .replace("⏰ DUE", "")
            .trim()
        holder.tvMessage.text = cleanText

        // ── Source chip ────────────────────────────────────────
        val source = notif.source ?: ""
        val sourceLabel = when {
            notif.packageSource.contains("whatsapp", ignoreCase = true) -> "WhatsApp"
            notif.packageSource.contains("gmail", ignoreCase = true) -> "Gmail"
            notif.packageSource.contains("classroom", ignoreCase = true) -> "Classroom"
            else -> source.take(12)
        }

        holder.tvSource.text = sourceLabel

        // Note: We removed the .setTint() here because the new dark theme
        // uses a custom glass drawable background for this chip. Tinting
        // it manually breaks the glass effect.

        // ── Priority chip + accent bar ─────────────────────────
        when {
            text.contains("🚨 URGENT") -> {
                holder.tvPriorityChip.visibility = View.VISIBLE
                holder.tvPriorityChip.text = "🚨 URGENT"
                holder.tvPriorityChip.setBackgroundResource(R.drawable.chip_urgent_dark)
                holder.viewAccentBar.setBackgroundColor(colorNeonRed)
            }
            text.contains("⏰ DUE") -> {
                holder.tvPriorityChip.visibility = View.VISIBLE
                holder.tvPriorityChip.text = "⏰ DUE"
                // Assuming Claude provided chip_due_dark.xml. If not, fallback to urgent chip style.
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

        // ── Click — open full notification view ────────────────
        holder.itemView.setOnClickListener {
            val context = holder.itemView.context
            val intent  = Intent(context, FullNotificationActivity::class.java).apply {
                putExtra("NOTIF_TITLE",     notif.title ?: "")
                putExtra("NOTIF_TEXT",      notif.text  ?: "")
                putExtra("NOTIF_SOURCE",    notif.source ?: "")
                putExtra("NOTIF_PACKAGE",   notif.packageSource)
                putExtra("NOTIF_TIMESTAMP", notif.timestamp)
            }
            context.startActivity(intent)
        }
    }

    override fun getItemCount() = notifications.size
}