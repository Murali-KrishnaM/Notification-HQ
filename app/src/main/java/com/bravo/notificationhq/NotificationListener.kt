package com.bravo.notificationhq

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationListener : NotificationListenerService() {

    companion object {
        private var lastSavedText = ""
        private var lastSaveTime = 0L
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            if (sbn == null) return
            val packageName = sbn.packageName ?: "Unknown"

            // 1. THE ALLOW LIST
            val allowedPackages = listOf(
                "com.whatsapp",
                "com.google.android.gm",
                "com.google.android.apps.classroom"
            )
            if (packageName !in allowedPackages) return

            val notification = sbn.notification ?: return
            val extras = notification.extras ?: return
            val title = extras.getString("android.title") ?: "No Title"
            val text = extras.getCharSequence("android.text")?.toString() ?: "No Text"

            var finalTitle = title
            var routeToSource = ""

            // 2. ROUTING LOGIC
            when (packageName) {
                "com.whatsapp" -> {
                    if (title.contains("Secret Teleport", ignoreCase = true)) {
                        if (text.matches(Regex("^\\d+ new messages$"))) return

                        val lowerText = text.lowercase()
                        if (lowerText.contains("room") || lowerText.contains("cancel") || lowerText.contains("rescheduled")) {
                            finalTitle = "🚨 URGENT: $title"
                        }
                        routeToSource = "Secret Teleport"
                    }
                }

                "com.google.android.apps.classroom" -> {
                    finalTitle = "📘 $title"
                    routeToSource = "Classroom"
                }

                "com.google.android.gm" -> {
                    val lowerTitle = title.lowercase()
                    val lowerText = text.lowercase()
                    if (lowerTitle.contains("assignment") || lowerText.contains("deadline") || lowerTitle.contains("project")) {
                        finalTitle = "📧 $title"
                        routeToSource = "Gmail"
                    }
                }
            }

            // 3. THE UNIVERSAL ECHO KILLER & DB SAVE
            if (routeToSource.isNotEmpty()) {
                val currentTime = System.currentTimeMillis()

                // We check if the text is a duplicate
                val isDuplicate = (text == lastSavedText) ||
                        (text.contains(lastSavedText, ignoreCase = true) && lastSavedText.isNotEmpty()) ||
                        (lastSavedText.contains(text, ignoreCase = true) && text.isNotEmpty())

                // If it's a duplicate within 3 seconds, block it!
                if (isDuplicate && (currentTime - lastSaveTime < 3000)) {
                    Log.d("NOTIF_DEBUG", "❌ ECHO IGNORED [$routeToSource]: $text")
                    return
                }

                // Update memory
                lastSavedText = text
                lastSaveTime = currentTime

                // Save to Room
                CoroutineScope(Dispatchers.IO).launch {
                    val db = AppDatabase.getDatabase(applicationContext)
                    val newNotification = NotificationModel(title = finalTitle, text = text, source = routeToSource)
                    db.notificationDao().insertNotification(newNotification)
                    Log.d("NOTIF_DEBUG", "✅ SAVED [$routeToSource]: $text")
                }
            }

        } catch (e: Exception) {
            Log.e("NOTIF_DEBUG", "CRASH PREVENTED: ${e.message}")
        }
    }
}