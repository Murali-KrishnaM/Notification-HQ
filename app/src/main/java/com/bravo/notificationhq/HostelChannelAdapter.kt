package com.bravo.notificationhq

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Adapter for the hostel channel list in [HostelActivity].
 * Identical logic to [PlacementChannelAdapter] with violet accent color
 * and [HostelChannelModel] as the data type.
 */
class HostelChannelAdapter(
    private val channels: List<HostelChannelModel>,
    private val notifCounts: Map<String, Int> = emptyMap(),
    private val onItemClick: (HostelChannelModel) -> Unit,
    private val onItemLongClick: (HostelChannelModel) -> Unit
) : RecyclerView.Adapter<HostelChannelAdapter.ViewHolder>() {

    // Neon violet as the primary accent — rotates through the full palette
    // for multiple channels so each card feels distinct
    private val neonColors = listOf(
        0xFFB06BFF.toInt(),  // neon violet  (primary for hostel)
        0xFF00E5A0.toInt(),  // neon green
        0xFF4D9EFF.toInt(),  // neon blue
        0xFFFFB340.toInt(),  // neon amber
        0xFFFF4D6A.toInt()   // neon red
    )

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val viewAccent:  View         = itemView.findViewById(R.id.viewChannelAccent)
        val tvLabel:     TextView     = itemView.findViewById(R.id.tvChannelLabel)
        val tvBadge:     TextView     = itemView.findViewById(R.id.tvChannelNotifCount)
        val rowWaGroup:  LinearLayout = itemView.findViewById(R.id.rowWaGroup)
        val tvWaGroup:   TextView     = itemView.findViewById(R.id.tvWaGroupName)
        val tvEmails:    TextView     = itemView.findViewById(R.id.tvEmailAddresses)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_placement_channel, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val channel = channels[position]

        // ── Label ──────────────────────────────────────────────────────────
        holder.tvLabel.text = channel.label

        // ── Accent bar color ───────────────────────────────────────────────
        holder.viewAccent.setBackgroundColor(neonColors[position % neonColors.size])

        // ── WhatsApp group row ─────────────────────────────────────────────
        if (!channel.whatsappGroupName.isNullOrBlank()) {
            holder.rowWaGroup.visibility = View.VISIBLE
            holder.tvWaGroup.text = channel.whatsappGroupName
        } else {
            holder.rowWaGroup.visibility = View.GONE
        }

        // ── Email addresses ────────────────────────────────────────────────
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

        // ── Notification badge ─────────────────────────────────────────────
        val count = notifCounts[channel.label] ?: 0
        if (count > 0) {
            holder.tvBadge.visibility = View.VISIBLE
            holder.tvBadge.text = if (count > 99) "99+" else count.toString()
        } else {
            holder.tvBadge.visibility = View.GONE
        }

        // ── Clicks ─────────────────────────────────────────────────────────
        holder.itemView.setOnClickListener { onItemClick(channel) }
        holder.itemView.setOnLongClickListener {
            onItemLongClick(channel)
            true
        }
    }

    override fun getItemCount() = channels.size
}