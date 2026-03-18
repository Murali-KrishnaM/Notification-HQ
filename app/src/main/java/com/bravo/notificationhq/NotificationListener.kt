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

            // 2. THE UNIVERSAL ECHO KILLER (Runs before heavy DB queries)
            val currentTime = System.currentTimeMillis()
            val isDuplicate = (text == lastSavedText) ||
                    (text.contains(lastSavedText, ignoreCase = true) && lastSavedText.isNotEmpty()) ||
                    (lastSavedText.contains(text, ignoreCase = true) && text.isNotEmpty())

            if (isDuplicate && (currentTime - lastSaveTime < 3000)) {
                return // Kill the echo instantly
            }

            lastSavedText = text
            lastSaveTime = currentTime

            // 3. LAUNCH BACKGROUND THREAD FOR DB ROUTING & EXTRACTION
            CoroutineScope(Dispatchers.IO).launch {
                val db = AppDatabase.getDatabase(applicationContext)
                val savedCourses = db.courseDao().getAllCourses()

                var finalTitle = title
                var routeToSource = ""
                val lowerTitle = title.lowercase()
                val lowerText = text.lowercase()

                // ==========================================================
                // STEP A: DATA EXTRACTION & FORMATTING
                // ==========================================================
                when (packageName) {
                    "com.whatsapp" -> {
                        if (text.matches(Regex("^\\d+ new messages$"))) return@launch
                        if (lowerText.contains("room") || lowerText.contains("cancel") || lowerText.contains("rescheduled")) {
                            finalTitle = "🚨 URGENT: $title"
                        }
                    }
                    "com.google.android.apps.classroom" -> {
                        finalTitle = "📘 $title"
                    }
                    "com.google.android.gm" -> {
                        // The Garbage Filter: Only allow academic emails
                        val validKeywords = listOf("quiz", "deadline", "assignment", "notes", "resource", "deadline changed")
                        val isValidEmail = validKeywords.any { lowerTitle.contains(it) || lowerText.contains(it) }

                        if (!isValidEmail) {
                            Log.d("NOTIF_DEBUG", "🗑️ TRASHED (Garbage Mail): $title")
                            return@launch // Stop processing this notification
                        }

                        // The Date Extractor: Look for the Digii Campus "Closes on" format
                        if (text.contains("Closes on :", ignoreCase = true)) {
                            // Grabs the date string right after "Closes on :"
                            val datePart = text.substringAfter("Closes on :").take(22).trim()
                            finalTitle = "⏰ DUE $datePart"
                        } else {
                            finalTitle = "📧 $title"
                        }
                    }
                }

// ==========================================================
                // STEP B: DYNAMIC ROUTING ENGINE (The 3-Pronged Net)
                // ==========================================================
                for (course in savedCourses) {
                    val cName = course.courseName.lowercase()
                    val cSymbol = course.courseSymbol.lowercase()
                    val cId = course.courseId.lowercase()
                    val wGroup = course.whatsappGroupName.lowercase()
                    val cRoom = course.classroomName.lowercase()

                    // Match WhatsApp
                    if (packageName == "com.whatsapp" && wGroup.isNotEmpty() && lowerTitle.contains(wGroup)) {
                        routeToSource = course.courseName
                        break
                    }

                    // Match Classroom
                    if (packageName == "com.google.android.apps.classroom" && cRoom.isNotEmpty() && (lowerTitle.contains(cRoom) || lowerText.contains(cRoom))) {
                        routeToSource = course.courseName
                        break
                    }

                    // Match Gmail: If the email contains the Course Name, Symbol, OR ID.
                    // NOTICE: We do NOT use facultyName here!
                    if (packageName == "com.google.android.gm") {
                        val isMatch = (cName.isNotEmpty() && (lowerTitle.contains(cName) || lowerText.contains(cName))) ||
                                (cSymbol.isNotEmpty() && (lowerTitle.contains(cSymbol) || lowerText.contains(cSymbol))) ||
                                (cId.isNotEmpty() && (lowerTitle.contains(cId) || lowerText.contains(cId)))

                        if (isMatch) {
                            routeToSource = course.courseName
                            break
                        }
                    }
                }

                // ==========================================================
                // STEP C: SAVE TO DATABASE
                // ==========================================================
                if (routeToSource.isNotEmpty()) {
                    val newNotification = NotificationModel(title = finalTitle, text = text, source = routeToSource)
                    db.notificationDao().insertNotification(newNotification)
                    Log.d("NOTIF_DEBUG", "✅ ROUTED TO [$routeToSource]: $finalTitle")
                }
            }

        } catch (e: Exception) {
            Log.e("NOTIF_DEBUG", "CRASH PREVENTED: ${e.message}")
        }
    }
}