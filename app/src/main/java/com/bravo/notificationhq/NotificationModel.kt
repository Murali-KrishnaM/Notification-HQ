package com.bravo.notificationhq

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "notifications_table")
data class NotificationModel(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val text: String,
    val source: String,               // stores the courseName it was routed to
    val packageSource: String = "",   // "whatsapp" | "gmail" | "classroom" | "other"
    val timestamp: Long = System.currentTimeMillis(),

    // ── Gemini-powered tagging fields (added in DB version 8) ──────────────
    val isUrgent: Boolean = false,    // true if Gemini (or regex fallback) flagged as urgent
    val dueDate: String? = null,      // ISO date string "2026-04-28" or null
    val summaryText: String? = null   // cached Gemini summary — null until user taps Summarize
)