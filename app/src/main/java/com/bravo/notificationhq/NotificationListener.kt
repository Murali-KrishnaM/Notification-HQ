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

        // NPTEL sender domains
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

        // Bucket names
        const val BUCKET_IMPORTANT_EMAILS  = "📧 Important Emails"
        const val BUCKET_GENERAL_CLASSROOM = "📘 General Classroom"
        const val BUCKET_NPTEL             = "📚 NPTEL"
        const val BUCKET_PLACEMENTS        = "🏢 Placements"

        // ── Fuzzy matching threshold ───────────────────────────
        // 0.6 = 60% of the saved group name's tokens must appear
        // in the notification title for a fuzzy match to succeed.
        // Raise this (e.g. 0.75) to be stricter,
        // lower it (e.g. 0.5) to be more lenient.
        private const val FUZZY_MATCH_THRESHOLD = 0.6f

        // Minimum token length to consider — ignore short noise words
        // like "a", "of", "the" when scoring
        private const val MIN_TOKEN_LENGTH = 2
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
        val db                = AppDatabase.getDatabase(applicationContext)
        val savedCourses      = db.courseDao().getAllCourses()
        val placementChannels = db.placementChannelDao().getAllChannels()

        when (pkg) {
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
            "com.google.android.apps.classroom" -> {
                processClassroom(
                    db           = db,
                    rawTitle     = rawTitle,
                    displayBody  = displayBody,
                    searchLower  = searchLower,
                    savedCourses = savedCourses
                )
            }
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
    // WHATSAPP PROCESSOR — exact match first, fuzzy fallback
    // ─────────────────────────────────────────────────────────────────────────
    private suspend fun processWhatsApp(
        db: AppDatabase,
        rawTitle: String,
        displayBody: String,
        searchLower: String,
        savedCourses: List<CourseModel>,
        placementChannels: List<PlacementChannelModel>
    ) {
        // Strip "(X)" suffixes WhatsApp adds to group names
        var finalTitle = rawTitle.substringBefore(" (").trim()

        // Tag urgent messages
        if (URGENT_KEYWORDS.any { searchLower.contains(it) }) {
            finalTitle = "🚨 URGENT: $finalTitle"
        }

        val titleLower = finalTitle.lowercase()

        // ── PASS 1: Exact substring match — Placements ─────────
        for (channel in placementChannels) {
            val wGroup = channel.whatsappGroupName?.trim()?.lowercase() ?: continue
            if (wGroup.isNotEmpty() && titleLower.contains(wGroup)) {
                saveNotification(db, finalTitle, displayBody, BUCKET_PLACEMENTS, "whatsapp")
                Log.d("NotifHQ", "WA exact → Placements: $finalTitle")
                return
            }
        }

        // ── PASS 1: Exact substring match — Academic courses ───
        for (course in savedCourses) {
            val wGroup = course.whatsappGroupName?.trim()?.lowercase() ?: continue
            if (wGroup.isNotEmpty() && titleLower.contains(wGroup)) {
                saveNotification(db, finalTitle, displayBody, course.courseName, "whatsapp")
                Log.d("NotifHQ", "WA exact → ${course.courseName}: $finalTitle")
                return
            }
        }

        // ── PASS 2: Fuzzy match — Placements ──────────────────
        var bestPlacementMatch: PlacementChannelModel? = null
        var bestPlacementScore = 0f

        for (channel in placementChannels) {
            val wGroup = channel.whatsappGroupName?.trim()?.lowercase() ?: continue
            if (wGroup.isEmpty()) continue
            val score = computeFuzzyScore(savedName = wGroup, notifTitle = titleLower)
            if (score > bestPlacementScore) {
                bestPlacementScore = score
                bestPlacementMatch = channel
            }
        }

        if (bestPlacementMatch != null && bestPlacementScore >= FUZZY_MATCH_THRESHOLD) {
            saveNotification(db, finalTitle, displayBody, BUCKET_PLACEMENTS, "whatsapp")
            Log.d("NotifHQ", "WA fuzzy → Placements (score=$bestPlacementScore): $finalTitle")
            return
        }

        // ── PASS 2: Fuzzy match — Academic courses ─────────────
        var bestCourseMatch: CourseModel? = null
        var bestCourseScore = 0f

        for (course in savedCourses) {
            val wGroup = course.whatsappGroupName?.trim()?.lowercase() ?: continue
            if (wGroup.isEmpty()) continue
            val score = computeFuzzyScore(savedName = wGroup, notifTitle = titleLower)
            if (score > bestCourseScore) {
                bestCourseScore = score
                bestCourseMatch = course
            }
        }

        if (bestCourseMatch != null && bestCourseScore >= FUZZY_MATCH_THRESHOLD) {
            saveNotification(db, finalTitle, displayBody, bestCourseMatch.courseName, "whatsapp")
            Log.d("NotifHQ", "WA fuzzy → ${bestCourseMatch.courseName} (score=$bestCourseScore): $finalTitle")
            return
        }

        // ── No match — drop silently ───────────────────────────
        Log.d("NotifHQ", "WA unmatched, dropped (best score=$bestCourseScore): $finalTitle")
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
        val senderAddress = extractSenderEmail(rawTitle, stdText, fullCorpus)
        val senderLower   = senderAddress.lowercase()

        Log.d("NotifHQ", "Gmail | sender: $senderLower | title: $rawTitle")

        // ── PRIORITY 1: NPTEL — DB channels first, keyword fallback ──
        val nptelChannels = db.nptelChannelDao().getAllChannels()

        val allNptelEmails = nptelChannels
            .flatMap { ch ->
                ch.emailAddresses
                    .split(",")
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }
            }
            .toSet()

        val isNptelSender  = allNptelEmails.any { nptelEmail ->
            senderLower.contains(nptelEmail) || nptelEmail.contains(senderLower)
        }
        val isNptelContent = NPTEL_KEYWORDS.any { searchLower.contains(it) }

        if (isNptelSender || isNptelContent) {
            val matchedNptelChannel = nptelChannels.firstOrNull { ch ->
                ch.emailAddresses
                    .split(",")
                    .map { it.trim().lowercase() }
                    .any { email ->
                        senderLower.contains(email) || email.contains(senderLower)
                    }
            }
            val nptelSource = matchedNptelChannel?.label ?: BUCKET_NPTEL
            val finalTitle  = buildDueTitle(rawTitle, fullCorpus) ?: "📚 $rawTitle"
            saveNotification(db, finalTitle, displayBody, nptelSource, "gmail")
            Log.d("NotifHQ", "Gmail → NPTEL [$nptelSource]")
            return
        }

        // ── PRIORITY 2: Placement sender check ────────────────
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
            Log.d("NotifHQ", "Gmail → Placements (sender match)")
            return
        }

        // ── PRIORITY 3: Best academic course match ─────────────
        var bestMatch: CourseModel? = null
        var bestMatchScore = 0

        for (course in savedCourses) {
            val score = computeCourseMatchScore(
                searchLower  = searchLower,
                courseName   = course.courseName,
                courseSymbol = course.courseSymbol,
                courseId     = course.courseId
            )
            if (score > bestMatchScore) {
                bestMatchScore = score
                bestMatch = course
            }
        }

        if (bestMatch != null && bestMatchScore > 0) {
            val finalTitle = buildDueTitle(rawTitle, fullCorpus) ?: "📧 $rawTitle"
            saveNotification(db, finalTitle, displayBody, bestMatch.courseName, "gmail")
            Log.d("NotifHQ", "Gmail → Academic [${bestMatch.courseName}] score=$bestMatchScore")
            return
        }

        // ── PRIORITY 4: Generic academic keyword fallback ──────
        val isAcademic = GMAIL_ACADEMIC_KEYWORDS.any { searchLower.contains(it) }
        if (isAcademic) {
            val finalTitle = buildDueTitle(rawTitle, fullCorpus) ?: "📧 $rawTitle"
            saveNotification(db, finalTitle, displayBody, BUCKET_IMPORTANT_EMAILS, "gmail")
            Log.d("NotifHQ", "Gmail → Important Emails (generic)")
            return
        }

        // ── PRIORITY 5: Drop ───────────────────────────────────
        Log.d("NotifHQ", "Gmail dropped: $rawTitle")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FUZZY MATCH HELPER
    // ─────────────────────────────────────────────────────────────────────────
    /**
     * Computes a token overlap score between the saved WhatsApp group name
     * and the incoming notification title.
     *
     * How it works:
     * 1. Tokenize both strings by splitting on spaces and punctuation
     * 2. Filter out tokens shorter than MIN_TOKEN_LENGTH (noise words)
     * 3. Count how many of the saved name's tokens appear in the title tokens
     * 4. Return score = matchedTokens / totalSavedTokens
     *
     * Examples:
     *   saved="nlpa batch a"  title="nlpa - batch a (5)"  → score=1.0  ✅ match
     *   saved="nlpa batch a"  title="nlpa batch"           → score=0.67 ✅ match (≥0.6)
     *   saved="cloud computing" title="coding club"        → score=0.0  ❌ no match
     *   saved="ad dept"       title="cse dept notice"      → score=0.5  ❌ below threshold
     */
    private fun computeFuzzyScore(savedName: String, notifTitle: String): Float {
        // Tokenize — split on any non-alphanumeric character
        val savedTokens = savedName
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= MIN_TOKEN_LENGTH }

        if (savedTokens.isEmpty()) return 0f

        val titleTokens = notifTitle
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= MIN_TOKEN_LENGTH }
            .toSet()

        // Count how many saved tokens appear in the title token set
        val matchedCount = savedTokens.count { savedToken ->
            // Exact token match OR title token starts with saved token
            // (handles abbreviations like "nlpa" matching "nlpa2024")
            titleTokens.any { titleToken ->
                titleToken == savedToken || titleToken.startsWith(savedToken)
            }
        }

        return matchedCount.toFloat() / savedTokens.size.toFloat()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // EMAIL COURSE MATCH SCORER
    // ─────────────────────────────────────────────────────────────────────────
    /**
     * +3 if courseId found   (most specific — e.g. "AD23B32")
     * +2 if courseSymbol found (e.g. "NLPA")
     * +1 if courseName found  (e.g. "Natural Language Processing")
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

    // ─────────────────────────────────────────────────────────────────────────
    // SENDER EMAIL EXTRACTOR
    // ─────────────────────────────────────────────────────────────────────────
    private fun extractSenderEmail(
        rawTitle: String,
        stdText: String,
        fullCorpus: String
    ): String {
        val emailRegex = Regex("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}")
        emailRegex.find(stdText)?.value?.let    { return it }
        emailRegex.find(rawTitle)?.value?.let   { return it }
        emailRegex.find(fullCorpus)?.value?.let { return it }
        return ""
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DUE TITLE BUILDER
    // ─────────────────────────────────────────────────────────────────────────
    private fun buildDueTitle(rawTitle: String, fullCorpus: String): String? {
        val corpusLower = fullCorpus.lowercase()
        val hasDueDate  = corpusLower.contains("closes on")  ||
                corpusLower.contains("due date")   ||
                corpusLower.contains("due on")     ||
                corpusLower.contains("last date")

        if (!hasDueDate) return null

        val datePart = fullCorpus
            .split(Regex("(?i)(closes on\\s*:?|due date\\s*:?|due on\\s*:?|last date\\s*:?)"))
            .getOrNull(1)
            ?.take(25)
            ?.trim() ?: ""

        return if (datePart.isNotEmpty()) "⏰ DUE $datePart — $rawTitle"
        else "⏰ $rawTitle"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SAVE NOTIFICATION
    // ─────────────────────────────────────────────────────────────────────────
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