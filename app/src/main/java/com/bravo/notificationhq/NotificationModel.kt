package com.bravo.notificationhq

data class NotificationModel(
    val title: String,
    val text: String,
    val source: String // e.g., "WhatsApp" or "Classroom"
)