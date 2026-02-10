package com.bravo.notificationhq

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class NotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            if (sbn == null) return
            val packageName = sbn.packageName ?: "Unknown"

            // 1. Only WhatsApp
            if (packageName != "com.whatsapp") return

            val notification = sbn.notification ?: return
            val extras = notification.extras ?: return
            val title = extras.getString("android.title") ?: "No Title"
            val text = extras.getCharSequence("android.text")?.toString() ?: "No Text"

            // 2. The "Secret Teleport" Filter
            if (title.contains("Secret Teleport", ignoreCase = true)) {

                // -------------------------------------------------
                // NEW FIX: The "Summary Assassin"
                // If the text is just "5 new messages", ignore it.
                // Regex explanation: ^\d+ matches "starts with a number"
                // -------------------------------------------------
                val isSummary = text.matches(Regex("^\\d+ new messages$"))
                if (isSummary) {
                    Log.d("NOTIF_DEBUG", "❌ IGNORED: Generic summary '$text'")
                    return
                }

                // If we get here, it's a REAL message
                Log.d("NOTIF_DEBUG", "✅ MATCH FOUND! Sending to App: $text")

                val intent = Intent("Msg_Received")
                intent.putExtra("title", title)
                intent.putExtra("text", text)
                intent.putExtra("source", "WhatsApp")
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
            }

        } catch (e: Exception) {
            Log.e("NOTIF_DEBUG", "CRASH PREVENTED: ${e.message}")
        }
    }
}