package com.bravo.notificationhq

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class PlacementChannelAdapter(
    private val channels: List<PlacementChannelModel>,
    private val notifCounts: Map<String, Int> = emptyMap(),
    private val onItemClick: (PlacementChannelModel) -> Unit,
    private val onItemLongClick: (PlacementChannelModel) -> Unit
) : RecyclerView.Adapter<PlacementChannelAdapter.ViewHolder>() {

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvLabel: TextView         = itemView.findViewById(R.id.tvChannelLabel)
        val tvBadge: TextView         = itemView.findViewById(R.id.tvChannelNotifCount)
        val rowWaGroup: LinearLayout  = itemView.findViewById(R.id.rowWaGroup)
        val tvWaGroup: TextView       = itemView.findViewById(R.id.tvWaGroupName)
        val tvEmails: TextView        = itemView.findViewById(R.id.tvEmailAddresses)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_placement_channel, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val channel = channels[position]

        holder.tvLabel.text = channel.label

        // ── WhatsApp group row ─────────────────────────────────
        if (!channel.whatsappGroupName.isNullOrBlank()) {
            holder.rowWaGroup.visibility = View.VISIBLE
            holder.tvWaGroup.text = channel.whatsappGroupName
        } else {
            holder.rowWaGroup.visibility = View.GONE
        }

        // ── Email addresses ────────────────────────────────────
        if (channel.emailAddresses.isNotBlank()) {
            holder.tvEmails.visibility = View.VISIBLE
            // Format: show each email on its own line
            holder.tvEmails.text = channel.emailAddresses
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n")
        } else {
            holder.tvEmails.visibility = View.GONE
        }

        // ── Notification badge ─────────────────────────────────
        val count = notifCounts[channel.label] ?: 0
        if (count > 0) {
            holder.tvBadge.visibility = View.VISIBLE
            holder.tvBadge.text = if (count > 99) "99+" else count.toString()
        } else {
            holder.tvBadge.visibility = View.GONE
        }

        // ── Clicks ─────────────────────────────────────────────
        holder.itemView.setOnClickListener { onItemClick(channel) }
        holder.itemView.setOnLongClickListener {
            onItemLongClick(channel)
            true
        }
    }

    override fun getItemCount() = channels.size
}