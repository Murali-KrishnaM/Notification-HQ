package com.bravo.notificationhq

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a hostel notification channel.
 * Same shape as [PlacementChannelModel] — hostel blocks also communicate
 * via WhatsApp groups and institutional email addresses.
 */
@Entity(tableName = "hostel_channels_table")
data class HostelChannelModel(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val label: String,                     // e.g. "Block A Warden"
    val whatsappGroupName: String? = null, // Optional WA group name
    val emailAddresses: String = ""        // Comma-separated email list
)