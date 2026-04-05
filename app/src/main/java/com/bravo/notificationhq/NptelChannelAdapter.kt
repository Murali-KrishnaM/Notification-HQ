package com.bravo.notificationhq

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class NptelChannelAdapter(
    private val channels: List<NptelChannelModel>,
    private val notifCounts: Map<String, Int> = emptyMap(),
    private val onItemClick: (NptelChannelModel) -> Unit,
    private val onItemLongClick: (NptelChannelModel) -> Unit
) : RecyclerView.Adapter<NptelChannelAdapter.ViewHolder>() {

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvLabel: TextView  = itemView.findViewById(R.id.tvChannelLabel)
        val tvBadge: TextView  = itemView.findViewById(R.id.tvChannelNotifCount)
        val tvEmails: TextView = itemView.findViewById(R.id.tvEmailAddresses)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_placement_channel, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val channel = channels[position]

        // ── Override accent bar color to NEON AMBER for NPTEL ──
        holder.itemView.findViewById<View>(R.id.viewChannelAccent)
            .setBackgroundColor(0xFFFFB340.toInt())

        holder.tvLabel.text = channel.label

        // ── Email addresses ────────────────────────────────────
        if (channel.emailAddresses.isNotBlank()) {
            holder.tvEmails.visibility = View.VISIBLE
            holder.tvEmails.text = channel.emailAddresses
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n")
        } else {
            holder.tvEmails.visibility = View.GONE
        }

        // ── Hide WA row — NPTEL has no WhatsApp ───────────────
        holder.itemView.findViewById<View>(R.id.rowWaGroup).visibility = View.GONE

        // ── Badge ──────────────────────────────────────────────
        val count = notifCounts[channel.label] ?: 0
        if (count > 0) {
            holder.tvBadge.visibility = View.VISIBLE
            holder.tvBadge.text = if (count > 99) "99+" else count.toString()
        } else {
            holder.tvBadge.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { onItemClick(channel) }
        holder.itemView.setOnLongClickListener {
            onItemLongClick(channel)
            true
        }
    }

    override fun getItemCount() = channels.size
}