package com.bravo.notificationhq

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "nptel_channels_table")
data class NptelChannelModel(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val label: String,                  // e.g. "Cloud Computing - NPTEL"
    val emailAddresses: String = ""     // Comma-separated sender addresses
)