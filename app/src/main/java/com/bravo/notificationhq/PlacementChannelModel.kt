package com.bravo.notificationhq

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "placement_channels_table")
data class PlacementChannelModel(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val label: String,                    // Display name e.g. "Placement Cell"
    val whatsappGroupName: String? = null, // Optional WA group name
    val emailAddresses: String = ""        // Comma-separated email list
)