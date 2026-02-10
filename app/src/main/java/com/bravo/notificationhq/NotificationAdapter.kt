package com.bravo.notificationhq

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class NotificationAdapter(private val notificationList: MutableList<NotificationModel>) :
    RecyclerView.Adapter<NotificationAdapter.ViewHolder>() {

    // This creates a NEW card layout when the list needs one
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_notification, parent, false)
        return ViewHolder(view)
    }

    // This updates the card with the actual data (Title, Message)
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = notificationList[position]
        holder.tvTitle.text = item.title
        holder.tvMessage.text = item.text
    }

    // Tells the list how many items we have
    override fun getItemCount(): Int {
        return notificationList.size
    }

    // Helper class to find the Textviews in your XML
    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
        val tvMessage: TextView = itemView.findViewById(R.id.tvMessage)
    }

    // Helper function to add a new notification and refresh the list
    fun addNotification(item: NotificationModel) {
        notificationList.add(0, item) // Add to the TOP of the list
        notifyItemInserted(0)
    }
}