package com.bravo.notificationhq

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class NotificationAdapter(
    private val notifications: List<NotificationModel>
) : RecyclerView.Adapter<NotificationAdapter.ViewHolder>() {

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        val tvMessage: TextView = itemView.findViewById(R.id.tvMessage)
        val tvSource: TextView = itemView.findViewById(R.id.tvSource)
        val tvPriorityChip: TextView = itemView.findViewById(R.id.tvPriorityChip)
        val viewAccentBar: View = itemView.findViewById(R.id.viewAccentBar)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_notification, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val notif = notifications[position]
        val text = notif.text ?: ""

        // ── Title ──────────────────────────────────────────────
        holder.tvTitle.text = notif.title ?: "Unknown"

        // ── Strip priority tags from displayed message body ────
        // The Smart Scanner embeds "🚨 URGENT" or "⏰ DUE" at the
        // start of the text field. We show those in the chip instead.
        val cleanText = text
            .replace("🚨 URGENT", "")
            .replace("⏰ DUE", "")
            .trim()
        holder.tvMessage.text = cleanText

        // ── Source chip ────────────────────────────────────────
        val source = notif.source ?: ""
        val (sourceLabel, sourceColor) = when {
            source.contains("whatsapp", ignoreCase = true) ->
                Pair("WhatsApp", Color.parseColor("#075E54"))   // WhatsApp dark green
            source.contains("gmail", ignoreCase = true) ->
                Pair("Gmail", Color.parseColor("#D93025"))      // Gmail red
            source.contains("classroom", ignoreCase = true) ->
                Pair("Classroom", Color.parseColor("#1A73E8"))  // Google blue
            else ->
                Pair(source.take(12), Color.parseColor("#424242"))
        }
        holder.tvSource.text = sourceLabel
        holder.tvSource.background.setTint(sourceColor)

        // ── Priority chip + accent bar ─────────────────────────
        when {
            text.contains("🚨 URGENT") -> {
                holder.tvPriorityChip.visibility = View.VISIBLE
                holder.tvPriorityChip.text = "🚨 URGENT"
                holder.tvPriorityChip.background.setTint(Color.parseColor("#D32F2F"))
                holder.viewAccentBar.setBackgroundColor(Color.parseColor("#D32F2F"))
            }
            text.contains("⏰ DUE") -> {
                holder.tvPriorityChip.visibility = View.VISIBLE
                holder.tvPriorityChip.text = "⏰ DUE"
                holder.tvPriorityChip.background.setTint(Color.parseColor("#F57F17"))
                holder.viewAccentBar.setBackgroundColor(Color.parseColor("#F57F17"))
            }
            else -> {
                holder.tvPriorityChip.visibility = View.GONE
                holder.viewAccentBar.setBackgroundColor(Color.parseColor("#4CAF50"))
            }
        }
    }

    override fun getItemCount() = notifications.size
}
