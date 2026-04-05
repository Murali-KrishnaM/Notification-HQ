package com.bravo.notificationhq

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationListener : NotificationListenerService() {

    companion object {
        // Echo Killer cache
        private val recentFingerprints = ArrayDeque<String>(20)

        // Gmail academic keyword filter
        private val GMAIL_ACADEMIC_KEYWORDS = listOf(
            "quiz", "deadline", "assignment", "notes", "resource",
            "submission", "exam", "test", "project", "review",
            "lab", "viva", "internal", "closes on", "due date",
            "attendance", "class cancelled", "rescheduled", "postponed",
            "marks", "grade", "result", "timetable", "schedule"
        )

        // NPTEL detection keywords
        private val NPTEL_KEYWORDS = listOf(
            "nptel", "swayam", "nptel.iitm.ac.in", "onlinecourses.nptel.ac.in",
            "nptel online", "nptel course", "week deadline", "assignment deadline"
        )

        // NPTEL sender domains/addresses
        private val NPTEL_SENDER_PATTERNS = listOf(
            "nptel.iitm.ac.in",
            "swayam.gov.in",
            "onlinecourses.nptel.ac.in",
            "no-reply@nptel",
            "noreply@nptel"
        )

        // Urgency keywords for WhatsApp
        private val URGENT_KEYWORDS = listOf(
            "room change", "room no", "cancel", "cancelled",
            "rescheduled", "postponed", "urgent", "important",
            "moved to", "shifted to", "no class", "holiday"
        )

        private val ALLOWED_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.google.android.gm",
            "com.google.android.apps.classroom"
        )

        // Bucket names — used as the `source` field in NotificationModel
        const val BUCKET_IMPORTANT_EMAILS = "📧 Important Emails"
        const val BUCKET_GENERAL_CLASSROOM = "📘 General Classroom"
        const val BUCKET_NPTEL = "📚 NPTEL"
        const val BUCKET_PLACEMENTS = "🏢 Placements"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            if (sbn == null) return
            val pkg = sbn.packageName ?: return
            if (pkg !in ALLOWED_PACKAGES) return

            val extras = sbn.notification?.extras ?: return

            val title     = extras.getString("android.title")?.trim() ?: return
            val stdText   = extras.getCharSequence("android.text")?.toString()?.trim() ?: ""
            val bigText   = extras.getCharSequence("android.bigText")?.toString()?.trim() ?: ""
            val textLines = extras.getCharSequenceArray("android.textLines")
                ?.joinToString("\n") { it.trim() } ?: ""

            // WhatsApp summary killer
            if (pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b") {
                if (stdText.matches(Regex("^\\d+ new messages?(( from \\d+ chats?)|(( in \\d+ chats?)))?$"))) return
                if (stdText.matches(Regex("^\\d+ new message$"))) return
            }

            val fullCorpus = buildString {
                append(title).append(" ")
                append(stdText).append(" ")
                if (bigText.isNotEmpty()) append(bigText).append(" ")
                if (textLines.isNotEmpty()) append(textLines)
            }.trim()

            val searchLower = fullCorpus.lowercase()

            val displayBody = when {
                bigText.isNotEmpty() && bigText != stdText -> bigText
                textLines.isNotEmpty()                     -> "$stdText\n$textLines".trim()
                stdText.isNotEmpty()                       -> stdText
                else                                       -> return
            }

            // Echo Killer
            val fingerprint = displayBody.lowercase().take(60)
            if (recentFingerprints.any { it == fingerprint }) return
            recentFingerprints.addLast(fingerprint)
            if (recentFingerprints.size > 20) recentFingerprints.removeFirst()

            CoroutineScope(Dispatchers.IO).launch {
                processNotification(
                    pkg         = pkg,
                    rawTitle    = title,
                    displayBody = displayBody,
                    searchLower = searchLower,
                    fullCorpus  = fullCorpus,
                    stdText     = stdText
                )
            }

        } catch (e: Exception) {
            Log.e("NotifHQ", "Crash in onNotificationPosted: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CORE PROCESSING
    // ─────────────────────────────────────────────────────────────────────────
    private suspend fun processNotification(
        pkg: String,
        rawTitle: String,
        displayBody: String,
        searchLower: String,
        fullCorpus: String,
        stdText: String
    ) {
        val db               = AppDatabase.getDatabase(applicationContext)
        val savedCourses     = db.courseDao().getAllCourses()
        val placementChannels = db.placementChannelDao().getAllChannels()

        when (pkg) {

            // ── WHATSAPP ──────────────────────────────────────────────────────
            "com.whatsapp", "com.whatsapp.w4b" -> {
                processWhatsApp(
                    db                = db,
                    rawTitle          = rawTitle,
                    displayBody       = displayBody,
                    searchLower       = searchLower,
                    savedCourses      = savedCourses,
                    placementChannels = placementChannels
                )
            }

            // ── GOOGLE CLASSROOM ──────────────────────────────────────────────
            "com.google.android.apps.classroom" -> {
                processClassroom(
                    db           = db,
                    rawTitle     = rawTitle,
                    displayBody  = displayBody,
                    searchLower  = searchLower,
                    savedCourses = savedCourses
                )
            }

            // ── GMAIL ─────────────────────────────────────────────────────────
            "com.google.android.gm" -> {
                processGmail(
                    db                = db,
                    rawTitle          = rawTitle,
                    displayBody       = displayBody,
                    searchLower       = searchLower,
                    fullCorpus        = fullCorpus,
                    stdText           = stdText,
                    savedCourses      = savedCourses,
                    placementChannels = placementChannels
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WHATSAPP PROCESSOR
    // ─────────────────────────────────────────────────────────────────────────
    private suspend fun processWhatsApp(
        db: AppDatabase,
        rawTitle: String,
        displayBody: String,
        searchLower: String,
        savedCourses: List<CourseModel>,
        placementChannels: List<PlacementChannelModel>
    ) {
        // Strip "(X)" suffixes WhatsApp adds
        var finalTitle = rawTitle.substringBefore(" (").trim()

        // Tag urgent messages
        if (URGENT_KEYWORDS.any { searchLower.contains(it) }) {
            finalTitle = "🚨 URGENT: $finalTitle"
        }

        val titleLower = finalTitle.lowercase()

        // PRIORITY 1 — check placement channels first
        for (channel in placementChannels) {
            val wGroup = channel.whatsappGroupName?.trim()?.lowercase() ?: continue
            if (wGroup.isNotEmpty() && titleLower.contains(wGroup)) {
                saveNotification(db, finalTitle, displayBody, BUCKET_PLACEMENTS, "whatsapp")
                return
            }
        }

        // PRIORITY 2 — match academic courses
        for (course in savedCourses) {
            val wGroup = course.whatsappGroupName?.trim()?.lowercase() ?: continue
            if (wGroup.isNotEmpty() && titleLower.contains(wGroup)) {
                saveNotification(db, finalTitle, displayBody, course.courseName, "whatsapp")
                return
            }
        }

        // Unmatched WhatsApp — drop silently
        Log.d("NotifHQ", "WhatsApp unmatched, dropped: $finalTitle")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CLASSROOM PROCESSOR
    // ─────────────────────────────────────────────────────────────────────────
    private suspend fun processClassroom(
        db: AppDatabase,
        rawTitle: String,
        displayBody: String,
        searchLower: String,
        savedCourses: List<CourseModel>
    ) {
        val finalTitle = "📘 $rawTitle"

        for (course in savedCourses) {
            val cRoom = course.classroomName?.trim()?.lowercase() ?: continue
            if (cRoom.isNotEmpty() && searchLower.contains(cRoom)) {
                saveNotification(db, finalTitle, displayBody, course.courseName, "classroom")
                return
            }
        }

        // Fallback
        saveNotification(db, finalTitle, displayBody, BUCKET_GENERAL_CLASSROOM, "classroom")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GMAIL PROCESSOR — strict priority routing
    // ─────────────────────────────────────────────────────────────────────────
    private suspend fun processGmail(
        db: AppDatabase,
        rawTitle: String,
        displayBody: String,
        searchLower: String,
        fullCorpus: String,
        stdText: String,
        savedCourses: List<CourseModel>,
        placementChannels: List<PlacementChannelModel>
    ) {
        // ── Extract sender address from the notification ───────────────────
        // Gmail notifications typically show "Sender Name <email>" or
        // just "email@domain.com" in the stdText or title fields
        val senderAddress = extractSenderEmail(rawTitle, stdText, fullCorpus)
        val senderLower   = senderAddress.lowercase()

        Log.d("NotifHQ", "Gmail received | sender: $senderLower | title: $rawTitle")

        // ── PRIORITY 1: NPTEL sender check ────────────────────────────────
        val isNptelSender = NPTEL_SENDER_PATTERNS.any { senderLower.contains(it) }
        val isNptelContent = NPTEL_KEYWORDS.any { searchLower.contains(it) }

        if (isNptelSender || isNptelContent) {
            val finalTitle = buildDueTitle(rawTitle, fullCorpus) ?: "📚 $rawTitle"
            saveNotification(db, finalTitle, displayBody, BUCKET_NPTEL, "gmail")
            Log.d("NotifHQ", "Gmail → NPTEL bucket")
            return
        }

        // ── PRIORITY 2: Placement channel sender check ────────────────────
        // Collect ALL email addresses saved across placement channels
        val allPlacementEmails = placementChannels
            .flatMap { channel ->
                channel.emailAddresses
                    .split(",")
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }
            }
            .toSet()

        val isPlacementSender = allPlacementEmails.any { placementEmail ->
            senderLower.contains(placementEmail) || placementEmail.contains(senderLower)
        }

        // Also check if corpus contains placement-related keywords
        // AND sender matches a known placement domain
        val placementDomains = placementChannels
            .flatMap { it.emailAddresses.split(",") }
            .map { it.trim().lowercase() }
            .mapNotNull { email -> email.substringAfter("@", "").ifEmpty { null } }
            .toSet()

        val isPlacementDomain = placementDomains.any { domain ->
            domain.isNotEmpty() && senderLower.contains(domain)
        }

        if (isPlacementSender || isPlacementDomain) {
            val finalTitle = buildDueTitle(rawTitle, fullCorpus) ?: "🏢 $rawTitle"
            saveNotification(db, finalTitle, displayBody, BUCKET_PLACEMENTS, "gmail")
            Log.d("NotifHQ", "Gmail → Placements bucket (sender match)")
            return
        }

        // ── PRIORITY 3: Strict academic course matching ───────────────────
        // Check EVERY course — pick the BEST match (most tokens matched)
        // This prevents Course A from stealing Course B's emails
        var bestMatch: CourseModel? = null
        var bestMatchScore = 0

        for (course in savedCourses) {
            val score = computeCourseMatchScore(
                searchLower = searchLower,
                courseName   = course.courseName,
                courseSymbol = course.courseSymbol,
                courseId     = course.courseId
            )
            if (score > bestMatchScore) {
                bestMatchScore = score
                bestMatch = course
            }
        }

        // A score > 0 means at least one field matched
        if (bestMatch != null && bestMatchScore > 0) {
            val finalTitle = buildDueTitle(rawTitle, fullCorpus) ?: "📧 $rawTitle"
            saveNotification(db, finalTitle, displayBody, bestMatch.courseName, "gmail")
            Log.d("NotifHQ", "Gmail → Academic [${bestMatch.courseName}] score=$bestMatchScore")
            return
        }

        // ── PRIORITY 4: Generic academic keyword fallback ─────────────────
        val isAcademic = GMAIL_ACADEMIC_KEYWORDS.any { searchLower.contains(it) }
        if (isAcademic) {
            val finalTitle = buildDueTitle(rawTitle, fullCorpus) ?: "📧 $rawTitle"
            saveNotification(db, finalTitle, displayBody, BUCKET_IMPORTANT_EMAILS, "gmail")
            Log.d("NotifHQ", "Gmail → Important Emails (generic academic)")
            return
        }

        // ── PRIORITY 5: Drop — not relevant ──────────────────────────────
        Log.d("NotifHQ", "Gmail dropped (no match): $rawTitle")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Computes a match score between an email's corpus and a course.
     * Higher score = better match.
     * Scoring:
     *   +3 if courseId found in corpus    (most specific — e.g. "AD23B32")
     *   +2 if courseSymbol found          (e.g. "NLPA")
     *   +1 if courseName found            (e.g. "Natural Language Processing")
     */
    private fun computeCourseMatchScore(
        searchLower: String,
        courseName: String,
        courseSymbol: String,
        courseId: String
    ): Int {
        var score = 0

        val nameL   = courseName.trim().lowercase()
        val symbolL = courseSymbol.trim().lowercase()
        val idL     = courseId.trim().lowercase()

        if (idL.isNotEmpty()     && searchLower.contains(idL))     score += 3
        if (symbolL.isNotEmpty() && searchLower.contains(symbolL)) score += 2
        if (nameL.isNotEmpty()   && searchLower.contains(nameL))   score += 1

        return score
    }

    /**
     * Tries to extract a sender email address from notification fields.
     * Gmail notifications embed the sender in the title or stdText
     * in formats like:
     *   "John Doe" (title) + "john@example.com  message..." (stdText)
     *   or the title itself is the email address
     */
    private fun extractSenderEmail(
        rawTitle: String,
        stdText: String,
        fullCorpus: String
    ): String {
        val emailRegex = Regex("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}")

        // Try stdText first (Gmail often puts sender email here)
        emailRegex.find(stdText)?.value?.let { return it }

        // Try the title
        emailRegex.find(rawTitle)?.value?.let { return it }

        // Try the full corpus as last resort
        emailRegex.find(fullCorpus)?.value?.let { return it }

        return ""   // Could not extract — will rely on content matching only
    }

    /**
     * Builds a due-date prefixed title if the corpus contains a due date.
     * Returns null if no due date found (caller should use a default).
     */
    private fun buildDueTitle(rawTitle: String, fullCorpus: String): String? {
        val corpusLower = fullCorpus.lowercase()
        val hasDueDate  = corpusLower.contains("closes on") || corpusLower.contains("due date") ||
                corpusLower.contains("due on") || corpusLower.contains("last date")

        if (!hasDueDate) return null

        val datePart = fullCorpus
            .split(Regex("(?i)(closes on\\s*:?|due date\\s*:?|due on\\s*:?|last date\\s*:?)"))
            .getOrNull(1)
            ?.take(25)
            ?.trim() ?: ""

        return if (datePart.isNotEmpty()) "⏰ DUE $datePart — $rawTitle"
        else "⏰ $rawTitle"
    }

    /**
     * Single save point — all processors call this.
     */
    private suspend fun saveNotification(
        db: AppDatabase,
        title: String,
        text: String,
        source: String,
        packageSource: String
    ) {
        val notification = NotificationModel(
            title         = title,
            text          = text,
            source        = source,
            packageSource = packageSource,
            timestamp     = System.currentTimeMillis()
        )
        db.notificationDao().insertNotification(notification)
        Log.d("NotifHQ", "Saved [$packageSource → $source]: $title")
    }
}