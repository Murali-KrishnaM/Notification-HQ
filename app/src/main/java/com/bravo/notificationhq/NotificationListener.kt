package com.bravo.notificationhq

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationListener : NotificationListenerService() {

    // 1. We put these in a companion object so they survive even if Android briefly pauses the service
    companion object {
        private var lastSavedText = ""
        private var lastSaveTime = 0L
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            if (sbn == null) return
            val packageName = sbn.packageName ?: "Unknown"

            if (packageName != "com.whatsapp") return

            val notification = sbn.notification ?: return
            val extras = notification.extras ?: return
            val title = extras.getString("android.title") ?: "No Title"
            val text = extras.getCharSequence("android.text")?.toString() ?: "No Text"

            if (title.contains("Secret Teleport", ignoreCase = true)) {

                // Summary Assassin
                val isSummary = text.matches(Regex("^\\d+ new messages$"))
                if (isSummary) return

                // -------------------------------------------------
                // NEW FIX: ECHO KILLER 2.0
                // -------------------------------------------------
                val currentTime = System.currentTimeMillis()

                // Check if the text is exactly the same, OR if one contains the other
                // (e.g., "Hello" vs "7S MOHAN: Hello")
                val isDuplicate = (text == lastSavedText) ||
                        (text.contains(lastSavedText, ignoreCase = true) && lastSavedText.isNotEmpty()) ||
                        (lastSavedText.contains(text, ignoreCase = true) && text.isNotEmpty())

                // If it's a duplicate and arrived within 3 seconds of the last one, KILL IT.
                if (isDuplicate && (currentTime - lastSaveTime < 3000)) {
                    Log.d("NOTIF_DEBUG", "❌ ECHO IGNORED: $text")
                    return
                }

                // Update our memory for the next incoming message
                lastSavedText = text
                lastSaveTime = currentTime

                // -------------------------------------------------
                // THE SMART SCANNER
                // -------------------------------------------------
                val lowerText = text.lowercase()
                val isUrgent = lowerText.contains("room") ||
                        lowerText.contains("venue") ||
                        lowerText.contains("cancel") ||
                        lowerText.contains("come") ||
                        lowerText.contains("class") ||
                        lowerText.contains("rescheduled")

                val finalTitle = if (isUrgent) "🚨 URGENT: $title" else title

                // -------------------------------------------------
                // SAVE TO ROOM DB
                // -------------------------------------------------
                CoroutineScope(Dispatchers.IO).launch {
                    val db = AppDatabase.getDatabase(applicationContext)
                    // Ensure your NotificationModel uses this syntax to auto-generate the ID
                    val newNotification = NotificationModel(title = finalTitle, text = text, source = "Secret Teleport")
                    db.notificationDao().insertNotification(newNotification)
                    Log.d("NOTIF_DEBUG", "✅ SAVED TO ROOM DB: $text")
                }
            }

        } catch (e: Exception) {
            Log.e("NOTIF_DEBUG", "CRASH PREVENTED: ${e.message}")
        }
    }
}