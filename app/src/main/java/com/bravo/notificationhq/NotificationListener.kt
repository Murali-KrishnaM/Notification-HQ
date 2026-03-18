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
        // The Cache Vault: Remembers the last 10 messages to aggressively kill echoes
        private val recentMessages = mutableListOf<String>()
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

            // 2. THE DEEP EXTRACTOR (Crucial for Gmail)
            // 'text' is usually just the Subject. 'bigText' holds the email body.
            val standardText = extras.getCharSequence("android.text")?.toString() ?: ""
            val bigText = extras.getCharSequence("android.bigText")?.toString() ?: ""

            // Combine them so our scanner doesn't miss anything!
            val fullText = if (bigText.isNotEmpty() && !bigText.contains(standardText)) {
                "$standardText\n$bigText"
            } else {
                standardText
            }

            if (fullText.trim().isEmpty()) return

            // 3. THE IRONCLAD ECHO KILLER
            // Check if this exact text (or a massive chunk of it) is in our recent memory
            val isDuplicate = recentMessages.any {
                it == fullText ||
                        (it.contains(fullText, ignoreCase = true) && fullText.length > 3) ||
                        (fullText.contains(it, ignoreCase = true) && it.length > 3)
            }

            if (isDuplicate) {
                Log.d("NOTIF_DEBUG", "❌ ECHO KILLED: $fullText")
                return
            }

            // Save to memory, and keep the list small (Max 10 items) so it doesn't drain RAM
            recentMessages.add(fullText)
            if (recentMessages.size > 10) recentMessages.removeAt(0)

            // 4. LAUNCH BACKGROUND THREAD FOR DB ROUTING
            CoroutineScope(Dispatchers.IO).launch {
                val db = AppDatabase.getDatabase(applicationContext)
                val savedCourses = db.courseDao().getAllCourses()

                var finalTitle = title
                var routeToSource = ""
                val lowerTitle = title.lowercase()
                val lowerText = fullText.lowercase() // Scanning the DEEP text

                // ==========================================================
                // STEP A: DATA EXTRACTION & FORMATTING
                // ==========================================================
                when (packageName) {
                    "com.whatsapp" -> {
                        if (fullText.matches(Regex("^\\d+ new messages$"))) return@launch
                        if (lowerText.contains("room") || lowerText.contains("cancel") || lowerText.contains("rescheduled")) {
                            finalTitle = "🚨 URGENT: $title"
                        }
                    }
                    "com.google.android.apps.classroom" -> {
                        finalTitle = "📘 $title"
                    }
                    "com.google.android.gm" -> {
                        // The Garbage Filter
                        val validKeywords = listOf("quiz", "deadline", "assignment", "notes", "resource", "deadline changed")
                        val isValidEmail = validKeywords.any { lowerTitle.contains(it) || lowerText.contains(it) }

                        if (!isValidEmail) {
                            Log.d("NOTIF_DEBUG", "🗑️ TRASHED (Garbage Mail): $title")
                            return@launch
                        }

                        // The Date Extractor
                        if (fullText.contains("Closes on :", ignoreCase = true)) {
                            val datePart = fullText.substringAfter("Closes on :").take(22).trim()
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

                    // Match Gmail using Subject AND Body (lowerText now contains both)
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

                // Fallback for valid emails that didn't match a course
                if (routeToSource.isEmpty()) {
                    if (packageName == "com.google.android.gm") routeToSource = "Important Emails"
                    if (packageName == "com.google.android.apps.classroom") routeToSource = "Classroom"
                }

                // ==========================================================
                // STEP C: SAVE TO DATABASE
                // ==========================================================
                if (routeToSource.isNotEmpty()) {
                    // We save the fullText so the user can read the email body in the app!
                    val newNotification = NotificationModel(title = finalTitle, text = fullText, source = routeToSource)
                    db.notificationDao().insertNotification(newNotification)
                    Log.d("NOTIF_DEBUG", "✅ ROUTED TO [$routeToSource]: $finalTitle")
                }
            }

        } catch (e: Exception) {
            Log.e("NOTIF_DEBUG", "CRASH PREVENTED: ${e.message}")
        }
    }
}