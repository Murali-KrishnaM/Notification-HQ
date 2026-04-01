package com.bravo.notificationhq

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationListener : NotificationListenerService() {

    companion object {
        // Echo Killer cache — stores the last 20 message fingerprints
        private val recentFingerprints = ArrayDeque<String>(20)

        // Gmail keyword filter — only save emails that mention academic content
        private val GMAIL_KEYWORDS = listOf(
            "quiz", "deadline", "assignment", "notes", "resource",
            "submission", "exam", "test", "project", "review",
            "lab", "viva", "internal", "closes on", "due date",
            "attendance", "class cancelled", "rescheduled", "postponed"
        )

        // Urgency keywords — triggers 🚨 URGENT tag
        private val URGENT_KEYWORDS = listOf(
            "room change", "room no", "cancel", "cancelled",
            "rescheduled", "postponed", "urgent", "important",
            "moved to", "shifted to", "no class", "holiday"
        )

        // Packages we listen to
        private val ALLOWED_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",             // WhatsApp Business
            "com.google.android.gm",
            "com.google.android.apps.classroom"
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            if (sbn == null) return
            val pkg = sbn.packageName ?: return
            if (pkg !in ALLOWED_PACKAGES) return

            val extras = sbn.notification?.extras ?: return

            // ── Raw extraction ────────────────────────────────────────────
            val title       = extras.getString("android.title")?.trim() ?: return
            val stdText     = extras.getCharSequence("android.text")?.toString()?.trim() ?: ""
            val bigText     = extras.getCharSequence("android.bigText")?.toString()?.trim() ?: ""
            val textLines   = extras.getCharSequenceArray("android.textLines")
                ?.joinToString("\n") { it.trim() } ?: ""

            // ── WhatsApp summary assassin ─────────────────────────────────
            // Kills "X new messages from Y groups" and "X new messages" summaries
            if (pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b") {
                if (stdText.matches(Regex("^\\d+ new messages?(( from \\d+ chats?)|(( in \\d+ chats?)))?$"))) return
                if (stdText.matches(Regex("^\\d+ new message$"))) return
            }

            // ── Build the search corpus ───────────────────────────────────
            // This is the full combined text we search for routing & tagging
            val fullCorpus = buildString {
                append(title).append(" ")
                append(stdText).append(" ")
                if (bigText.isNotEmpty()) append(bigText).append(" ")
                if (textLines.isNotEmpty()) append(textLines)
            }.trim()

            val searchLower = fullCorpus.lowercase()

            // ── Build the display body ────────────────────────────────────
            // What actually shows in the notification card
            val displayBody = when {
                bigText.isNotEmpty() && bigText != stdText -> bigText
                textLines.isNotEmpty()                     -> "$stdText\n$textLines".trim()
                stdText.isNotEmpty()                       -> stdText
                else                                       -> return // nothing to show
            }

            // ── Echo Killer ───────────────────────────────────────────────
            // Fingerprint = first 60 chars of the display body (case-folded)
            val fingerprint = displayBody.lowercase().take(60)
            if (recentFingerprints.any { it == fingerprint }) return
            recentFingerprints.addLast(fingerprint)
            if (recentFingerprints.size > 20) recentFingerprints.removeFirst()

            // ── Background processing ─────────────────────────────────────
            CoroutineScope(Dispatchers.IO).launch {
                processNotification(
                    pkg          = pkg,
                    rawTitle     = title,
                    displayBody  = displayBody,
                    searchLower  = searchLower,
                    fullCorpus   = fullCorpus
                )
            }

        } catch (e: Exception) {
            Log.e("NotifHQ", "Crash prevented in onNotificationPosted: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CORE PROCESSING — runs on IO thread
    // ─────────────────────────────────────────────────────────────────────────
    private suspend fun processNotification(
        pkg: String,
        rawTitle: String,
        displayBody: String,
        searchLower: String,
        fullCorpus: String
    ) {
        val db          = AppDatabase.getDatabase(applicationContext)
        val savedCourses = db.courseDao().getAllCourses()

        // ── STEP 1: Package-specific filtering & title formatting ─────────
        val packageLabel: String
        var finalTitle = rawTitle

        when (pkg) {
            "com.whatsapp", "com.whatsapp.w4b" -> {
                packageLabel = "whatsapp"
                // Strip "(X)" suffixes WhatsApp adds to group names
                finalTitle = rawTitle.substringBefore(" (").trim()

                // Tag urgent messages
                if (URGENT_KEYWORDS.any { searchLower.contains(it) }) {
                    finalTitle = "🚨 URGENT: $finalTitle"
                }
            }

            "com.google.android.apps.classroom" -> {
                packageLabel = "classroom"
                finalTitle = "📘 $rawTitle"
            }

            "com.google.android.gm" -> {
                packageLabel = "gmail"

                // Gmail filter — only save if it's academically relevant
                val isRelevant = GMAIL_KEYWORDS.any { searchLower.contains(it) }
                if (!isRelevant) return

                // Extract due date if present
                val hasDueDate = searchLower.contains("closes on") || searchLower.contains("due date")
                if (hasDueDate) {
                    val datePart = fullCorpus
                        .split(Regex("(?i)(closes on\\s*:|due date\\s*:)"))
                        .getOrNull(1)
                        ?.take(25)
                        ?.trim() ?: ""
                    finalTitle = if (datePart.isNotEmpty()) "⏰ DUE $datePart" else "⏰ $rawTitle"
                } else {
                    finalTitle = "📧 $rawTitle"
                }
            }

            else -> return
        }

        // ── STEP 2: Dynamic routing — match to a saved course ────────────
        // Priority order: WhatsApp group name → Classroom name → Course name/symbol/ID
        var routedCourseName = ""

        for (course in savedCourses) {
            val matched = when (pkg) {

                "com.whatsapp", "com.whatsapp.w4b" -> {
                    val wGroup = course.whatsappGroupName?.trim()?.lowercase() ?: ""
                    // Match on the stripped title (group name)
                    wGroup.isNotEmpty() && finalTitle.lowercase().contains(wGroup)
                }

                "com.google.android.apps.classroom" -> {
                    val cRoom = course.classroomName?.trim()?.lowercase() ?: ""
                    cRoom.isNotEmpty() && searchLower.contains(cRoom)
                }

                "com.google.android.gm" -> {
                    val cName   = course.courseName.trim().lowercase()
                    val cSymbol = course.courseSymbol.trim().lowercase()
                    val cId     = course.courseId.trim().lowercase()
                    // Match if corpus contains the course name, symbol, OR ID
                    (cName.isNotEmpty()   && searchLower.contains(cName))   ||
                    (cSymbol.isNotEmpty() && searchLower.contains(cSymbol)) ||
                    (cId.isNotEmpty()     && searchLower.contains(cId))
                }

                else -> false
            }

            if (matched) {
                routedCourseName = course.courseName
                break
            }
        }

        // ── STEP 3: Fallback buckets for unmatched notifications ──────────
        if (routedCourseName.isEmpty()) {
            routedCourseName = when (pkg) {
                "com.google.android.gm"                   -> "📧 Important Emails"
                "com.google.android.apps.classroom"       -> "📘 General Classroom"
                else                                       -> return // unmatched WhatsApp = ignore
            }
        }

        // ── STEP 4: Save to database ──────────────────────────────────────
        val notification = NotificationModel(
            title         = finalTitle,
            text          = displayBody,
            source        = routedCourseName,
            packageSource = packageLabel,
            timestamp     = System.currentTimeMillis()
        )
        db.notificationDao().insertNotification(notification)

        Log.d("NotifHQ", "Saved [$packageLabel → $routedCourseName]: $finalTitle")
    }
}
