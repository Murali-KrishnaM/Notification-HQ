package com.bravo.notificationhq

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class NotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // 1. Get the basics
        val packageName = sbn?.packageName ?: return
        val extras = sbn.notification.extras
        val title = extras.getString("android.title") ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""

        // 2. THE FILTER: Only let WhatsApp through if the Title matches your target
        // CHANGE "7S MOHAN" to the exact name of your class group or friend!
        if (packageName == "com.whatsapp" && title.contains("Secret Teleport", ignoreCase = true)) {

            Log.d("NOTIF_HQ", ">>> IMPORTANT DEADLINE FOUND: $text")

            // TODO: In the future, we will save this to a database here.
        }
    }
}