package com.bravo.notificationhq

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class NotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val packageName = sbn?.packageName ?: "Unknown"
        val extras = sbn?.notification?.extras
        val title = extras?.getString("android.title") ?: "No Title"
        val text = extras?.getCharSequence("android.text")?.toString() ?: "No Text"

        Log.d("NOTIF_HQ", "Captured: $packageName | Title: $title | Text: $text")
    }
}