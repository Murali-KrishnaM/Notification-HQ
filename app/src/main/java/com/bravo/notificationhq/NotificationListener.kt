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
        // The Cache Vault
        private val recentMessages = mutableListOf<String>()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            if (sbn == null) return
            val packageName = sbn.packageName ?: "Unknown"

            val allowedPackages = listOf("com.whatsapp", "com.google.android.gm", "com.google.android.apps.classroom")
            if (packageName !in allowedPackages) return

            val notification = sbn.notification ?: return
            val extras = notification.extras ?: return

            // Extract the raw pieces
            val title = extras.getString("android.title") ?: "No Title"
            val standardText = extras.getCharSequence("android.text")?.toString() ?: ""
            val bigText = extras.getCharSequence("android.bigText")?.toString() ?: ""

            val textLinesArray = extras.getCharSequenceArray("android.textLines")
            val textLines = textLinesArray?.joinToString(" ") ?: ""

            // 1. WHATSAPP SUMMARY ASSASSIN (Fast Kill)
            if (packageName == "com.whatsapp" && standardText.matches(Regex("^\\d+ new messages$"))) return

            // 2. THE ECHO KILLER (Track ONLY the actual message text to prevent false echoes)
            val checkText = if (standardText.isNotEmpty()) standardText else title
            val isDuplicate = recentMessages.any {
                it == checkText || (it.contains(checkText, ignoreCase = true) && checkText.length > 5)
            }

            if (isDuplicate) return

            recentMessages.add(checkText)
            if (recentMessages.size > 15) recentMessages.removeAt(0)

            // 3. THE "UGLY" SEARCH STRING (Used ONLY by the routing engine)
            val searchString = "$title \n $standardText \n $bigText \n $textLines".lowercase()

            // 4. THE "CLEAN" DISPLAY STRING (What the user actually reads on the card)
            var displayBody = if (bigText.isNotEmpty() && !bigText.contains(standardText, ignoreCase = true)) {
                "$standardText\n$bigText"
            } else if (bigText.isNotEmpty()) {
                bigText
            } else {
                standardText
            }

            // LAUNCH BACKGROUND THREAD FOR DB ROUTING
            CoroutineScope(Dispatchers.IO).launch {
                val db = AppDatabase.getDatabase(applicationContext)
                val savedCourses = db.courseDao().getAllCourses()

                var finalTitle = title
                var routeToSource = ""

                // ==========================================================
                // STEP A: DATA EXTRACTION & FORMATTING
                // ==========================================================
                when (packageName) {
                    "com.whatsapp" -> {
                        // Clean up the WhatsApp title to remove the ugly "(X messages)" part
                        finalTitle = title.substringBefore(" (").trim()

                        if (searchString.contains("room") || searchString.contains("cancel") || searchString.contains("rescheduled")) {
                            finalTitle = "🚨 URGENT: $finalTitle"
                        }
                    }
                    "com.google.android.apps.classroom" -> {
                        finalTitle = "📘 $title"
                    }
                    "com.google.android.gm" -> {
                        val validKeywords = listOf("quiz", "deadline", "assignment", "notes", "resource", "deadline changed")
                        val isValidEmail = validKeywords.any { searchString.contains(it) }

                        if (!isValidEmail) return@launch

                        if (searchString.contains("closes on :")) {
                            // Extract date safely from the search string
                            val datePart = extras.getCharSequence("android.bigText")?.toString()
                                ?.split(Regex("(?i)Closes on :"))?.getOrNull(1)?.take(22)?.trim() ?: ""

                            if (datePart.isNotEmpty()) {
                                finalTitle = "⏰ DUE $datePart"
                            } else {
                                finalTitle = "📧 $title"
                            }
                        } else {
                            finalTitle = "📧 $title"
                        }
                    }
                }

                // ==========================================================
                // STEP B: DYNAMIC ROUTING ENGINE (Using the Ugly Search String)
                // ==========================================================
                for (course in savedCourses) {
                    val cName = course.courseName.lowercase()
                    val cSymbol = course.courseSymbol.lowercase()
                    val cId = course.courseId.lowercase()
                    val wGroup = course.whatsappGroupName.lowercase()
                    val cRoom = course.classroomName.lowercase()

                    if (packageName == "com.whatsapp" && wGroup.isNotEmpty() && title.lowercase().contains(wGroup)) {
                        routeToSource = course.courseName
                        break
                    }
                    if (packageName == "com.google.android.apps.classroom" && cRoom.isNotEmpty() && searchString.contains(cRoom)) {
                        routeToSource = course.courseName
                        break
                    }
                    if (packageName == "com.google.android.gm") {
                        if ((cName.isNotEmpty() && searchString.contains(cName)) ||
                            (cSymbol.isNotEmpty() && searchString.contains(cSymbol)) ||
                            (cId.isNotEmpty() && searchString.contains(cId))) {
                            routeToSource = course.courseName
                            break
                        }
                    }
                }

                if (routeToSource.isEmpty()) {
                    if (packageName == "com.google.android.gm") routeToSource = "Important Emails"
                    if (packageName == "com.google.android.apps.classroom") routeToSource = "Classroom"
                }

                // ==========================================================
                // STEP C: SAVE TO DATABASE (Using the Clean Display Strings)
                // ==========================================================
                if (routeToSource.isNotEmpty()) {
                    val newNotification = NotificationModel(title = finalTitle, text = displayBody.trim(), source = routeToSource)
                    db.notificationDao().insertNotification(newNotification)
                }
            }

        } catch (e: Exception) {
            Log.e("NOTIF_DEBUG", "CRASH PREVENTED: ${e.message}")
        }
    }
}