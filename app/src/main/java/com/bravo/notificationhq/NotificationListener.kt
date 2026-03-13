package com.bravo.notificationhq

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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

                // -------------------------------------------------
                // THE SMART SCANNER (MVP Hack)
                // -------------------------------------------------
                val lowerText = text.lowercase()
                val isUrgent = lowerText.contains("room") ||
                        lowerText.contains("venue") ||
                        lowerText.contains("cancel") ||
                        lowerText.contains("rescheduled")

                // Slap a siren on it if it's important!
                val finalTitle = if (isUrgent) "🚨 URGENT: $title" else title

                Log.d("NOTIF_DEBUG", "✅ SAVING TO MEMORY: $text")

                // -------------------------------------------------
                // SAVE TO MEMORY DB
                // We use 'title' (e.g. "Secret Teleport") as the source so the Detail screen finds it
                // -------------------------------------------------
                val newNotification = NotificationModel(title = finalTitle, text = text, source = "Secret Teleport")
                // We launch a background thread (Dispatchers.IO) to save to the database safely
                CoroutineScope(Dispatchers.IO).launch {
                    val db = AppDatabase.getDatabase(applicationContext)
                    db.notificationDao().insertNotification(newNotification)
                    Log.d("NOTIF_DEBUG", "✅ SAVED TO ROOM DB: $text")
                }
            }

        } catch (e: Exception) {
            Log.e("NOTIF_DEBUG", "CRASH PREVENTED: ${e.message}")
        }
    }
}