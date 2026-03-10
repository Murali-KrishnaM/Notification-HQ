package com.bravo.notificationhq

object MemoryDB {
    // This list will hold all captured notifications while the app is open!
    val savedNotifications = mutableListOf<NotificationModel>()
}