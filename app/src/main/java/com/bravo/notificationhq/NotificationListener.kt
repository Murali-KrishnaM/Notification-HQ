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

            // 1. THE ALLOW LIST (Multi-Channel Ingestion)
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

            // Variables to hold where this message should go
            var finalTitle = title
            var routeToSource = ""

            // 2. ROUTING LOGIC (Where did it come from?)
            when (packageName) {
                "com.whatsapp" -> {
                    if (title.contains("Secret Teleport", ignoreCase = true)) {
                        // Summary Assassin
                        if (text.matches(Regex("^\\d+ new messages$"))) return

                        // Echo Killer 2.0
                        val currentTime = System.currentTimeMillis()
                        val isDuplicate = (text == lastSavedText) ||
                                (text.contains(lastSavedText, ignoreCase = true) && lastSavedText.isNotEmpty())
                        if (isDuplicate && (currentTime - lastSaveTime < 3000)) return

                        lastSavedText = text
                        lastSaveTime = currentTime

                        // Smart Scanner
                        val lowerText = text.lowercase()
                        if (lowerText.contains("room") || lowerText.contains("cancel")) {
                            finalTitle = "🚨 URGENT: $title"
                        }

                        routeToSource = "Secret Teleport" // Matches the Dashboard card
                    }
                }

                "com.google.android.apps.classroom" -> {
                    // Catch ALL Classroom notifications
                    finalTitle = "📘 $title" // Add a book emoji for style
                    routeToSource = "Classroom"
                }

                "com.google.android.gm" -> {
                    // For Gmail, let's only catch emails that look academic
                    val lowerTitle = title.lowercase()
                    val lowerText = text.lowercase()
                    if (lowerTitle.contains("assignment") || lowerText.contains("deadline") || lowerTitle.contains("project")) {
                        finalTitle = "📧 $title"
                        routeToSource = "Gmail"
                    }
                }
            }

            // 3. SAVE TO ROOM DB (If it was routed somewhere)
            if (routeToSource.isNotEmpty()) {
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