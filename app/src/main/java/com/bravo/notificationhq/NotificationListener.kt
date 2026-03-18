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

            // 1. THE ALLOW LIST
            val allowedPackages = listOf(
                "com.whatsapp",
                "com.google.android.gm",
                "com.google.android.apps.classroom"
            )
            if (packageName !in allowedPackages) return

            val notification = sbn.notification ?: return
            val extras = notification.extras ?: return

            // From your Logcat: 'title' is the Sender Name, 'text' is the Subject, 'bigText' is the Body
            val senderName = extras.getString("android.title") ?: ""
            val standardText = extras.getCharSequence("android.text")?.toString() ?: ""
            val bigText = extras.getCharSequence("android.bigText")?.toString() ?: ""

            // ---------------------------------------------------------
            // THE FIX: The Brute-Force String Smasher
            // No clever IF statements. Just combine it all so nothing is lost.
            // ---------------------------------------------------------
            val fullText = "$senderName \n $standardText \n $bigText".trim()

            if (fullText.isEmpty()) return

            // 3. THE IRONCLAD ECHO KILLER
            val isDuplicate = recentMessages.any {
                it == fullText ||
                        (it.contains(fullText, ignoreCase = true) && fullText.length > 5) ||
                        (fullText.contains(it, ignoreCase = true) && it.length > 5)
            }

            if (isDuplicate) {
                Log.d("NOTIF_DEBUG", "❌ ECHO KILLED")
                return
            }

            recentMessages.add(fullText)
            if (recentMessages.size > 10) recentMessages.removeAt(0)

            // 4. LAUNCH BACKGROUND THREAD FOR DB ROUTING
            CoroutineScope(Dispatchers.IO).launch {
                val db = AppDatabase.getDatabase(applicationContext)
                val savedCourses = db.courseDao().getAllCourses()

                // We will use standardText (Subject) for the UI Card Title
                var finalTitle = standardText.ifEmpty { "New Update" }
                var routeToSource = ""

                val lowerText = fullText.lowercase()

                // ==========================================================
                // STEP A: DATA EXTRACTION & FORMATTING
                // ==========================================================
                when (packageName) {
                    "com.whatsapp" -> {
                        if (fullText.matches(Regex("^\\d+ new messages$"))) return@launch
                        if (lowerText.contains("room") || lowerText.contains("cancel") || lowerText.contains("rescheduled")) {
                            finalTitle = "🚨 URGENT: $finalTitle"
                        }
                    }
                    "com.google.android.apps.classroom" -> {
                        finalTitle = "📘 $finalTitle"
                    }
                    "com.google.android.gm" -> {
                        // The Garbage Filter
                        val validKeywords = listOf("quiz", "deadline", "assignment", "notes", "resource", "deadline changed")
                        val isValidEmail = validKeywords.any { lowerText.contains(it) }

                        if (!isValidEmail) {
                            Log.d("NOTIF_DEBUG", "🗑️ TRASHED (Garbage Mail): $finalTitle")
                            return@launch
                        }

                        // The Date Extractor
                        if (lowerText.contains("closes on :")) {
                            val datePart = fullText.split(Regex("(?i)Closes on :")).getOrNull(1)?.take(22)?.trim() ?: ""
                            if (datePart.isNotEmpty()) {
                                finalTitle = "⏰ DUE $datePart"
                            } else {
                                finalTitle = "📧 $finalTitle"
                            }
                        } else {
                            finalTitle = "📧 $finalTitle"
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
                    if (packageName == "com.whatsapp" && wGroup.isNotEmpty() && lowerText.contains(wGroup)) {
                        routeToSource = course.courseName
                        break
                    }

                    // Match Classroom
                    if (packageName == "com.google.android.apps.classroom" && cRoom.isNotEmpty() && lowerText.contains(cRoom)) {
                        routeToSource = course.courseName
                        break
                    }

                    // Match Gmail: Since fullText contains everything, we just check lowerText once
                    if (packageName == "com.google.android.gm") {
                        val isMatch = (cName.isNotEmpty() && lowerText.contains(cName)) ||
                                (cSymbol.isNotEmpty() && lowerText.contains(cSymbol)) ||
                                (cId.isNotEmpty() && lowerText.contains(cId))

                        if (isMatch) {
                            routeToSource = course.courseName
                            break
                        }
                    }
                }

                // Fallback
                if (routeToSource.isEmpty()) {
                    if (packageName == "com.google.android.gm") routeToSource = "Important Emails"
                    if (packageName == "com.google.android.apps.classroom") routeToSource = "Classroom"
                }

                // ==========================================================
                // STEP C: SAVE TO DATABASE
                // ==========================================================
                if (routeToSource.isNotEmpty()) {
                    // Save standardText + bigText as the body for the UI
                    val cleanBody = "$standardText\n$bigText".trim()
                    val newNotification = NotificationModel(title = finalTitle, text = cleanBody, source = routeToSource)
                    db.notificationDao().insertNotification(newNotification)
                    Log.d("NOTIF_DEBUG", "✅ ROUTED TO [$routeToSource]: $finalTitle")
                }
            }

        } catch (e: Exception) {
            Log.e("NOTIF_DEBUG", "CRASH PREVENTED: ${e.message}")
        }
    }
}