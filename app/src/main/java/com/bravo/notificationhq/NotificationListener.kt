package com.bravo.notificationhq

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationListener : NotificationListenerService() {

    companion object {
        // ── Echo Killer cache ──────────────────────────────────
        // Stores composite fingerprints: normalizedTitle + tail of body
        private val recentFingerprints = ArrayDeque<String>(30)

        // Gmail academic keyword filter (PRIORITY 4 catch-all — generic terms only)
        private val GMAIL_ACADEMIC_KEYWORDS = listOf(
            "quiz", "notes", "resource", "submission", "exam",
            "project", "review", "lab", "viva", "internal",
            "closes on", "due date", "attendance", "class cancelled",
            "rescheduled", "postponed", "marks", "grade", "result",
            "timetable", "schedule", "assignment", "deadline"
        )

        // ── NPTEL keywords — STRICT, NPTEL-specific terms ONLY ──
        // Removed: "week deadline", "assignment deadline" — too generic, caused false positives
        private val NPTEL_KEYWORDS = listOf(
            "nptel",
            "swayam",
            "nptel.iitm.ac.in",
            "onlinecourses.nptel.ac.in",
            "nptel online",
            "nptel course",
            "onlinecourses.nptel"
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

        // ── Fuzzy thresholds ───────────────────────────────────
        // Academic groups: stricter (0.6) to avoid wrong course routing
        private const val FUZZY_MATCH_THRESHOLD_ACADEMIC   = 0.6f
        // Placement groups: more lenient (0.5) because names like
        // "2027 AIDS,AIML,IT,CSD" have many tokens and partial matches are valid
        private const val FUZZY_MATCH_THRESHOLD_PLACEMENTS = 0.5f

        // Ignore tokens shorter than this (filters "a", "of", "it" noise)
        // NOTE: Raised to 3 to also filter common 2-letter noise like "it"
        // that would otherwise incorrectly match "IT" in group names like
        // "2027 AIDS,AIML,IT,CSD" against unrelated messages.
        private const val MIN_TOKEN_LENGTH = 3
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

            // ── WhatsApp summary notification killer ───────────
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

            // ── Choose the richest body to display ────────────
            val displayBody = when {
                bigText.isNotEmpty() && bigText != stdText -> bigText
                textLines.isNotEmpty()                     -> "$stdText\n$textLines".trim()
                stdText.isNotEmpty()                       -> stdText
                else                                       -> return
            }

            // ══════════════════════════════════════════════════
            // FIX 2: IMPROVED ECHO KILLER
            //
            // Problem: WhatsApp fires two events for one message:
            //   Event A — stdText = "Jeevapriya: 📄 NLP_file.pptx"
            //   Event B — bigText = "📄 NLP_file.pptx (12 slides)"
            // These produce different displayBody strings, so the old
            // take(60) fingerprint treated them as different messages.
            //
            // Fix: Build the fingerprint from:
            //   1. Normalized group title (strip WA count suffix, lowercase)
            //   2. Last 80 chars of the body (most unique / content-rich part)
            //      stripped of the sender-name prefix ("Jeevapriya: ...")
            //
            // This way both events A and B share the same content tail
            // and the second one is correctly killed as a duplicate.
            // ══════════════════════════════════════════════════
            val normalizedTitle = title
                .substringBefore(" (")   // strip "(5)" count suffix
                .lowercase()
                .trim()

            // Strip "SenderName: " prefix that WhatsApp adds to stdText
            val bodyForFingerprint = displayBody
                .replace(Regex("^[^:]{1,40}:\\s*"), "")  // remove "Name: " prefix up to 40 chars
                .lowercase()
                .trim()

            // Use last 80 chars — most unique part of any message
            val bodyTail = if (bodyForFingerprint.length > 80)
                bodyForFingerprint.takeLast(80)
            else
                bodyForFingerprint

            val fingerprint = "$normalizedTitle|$bodyTail"

            if (recentFingerprints.any { it == fingerprint }) {
                Log.d("NotifHQ", "Echo killed: $normalizedTitle")
                return
            }
            recentFingerprints.addLast(fingerprint)
            if (recentFingerprints.size > 30) recentFingerprints.removeFirst()

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
    // CORE PROCESSING — routes to the correct processor by package
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
    // WHATSAPP PROCESSOR
    // Pass 1: Exact substring match (fastest, most reliable)
    // Pass 2: Fuzzy token match (handles WhatsApp name variations)
    // FIX 3 is applied here: normalizeForMatching() strips commas, dots, etc.
    //        before tokenizing so "2027 AIDS,AIML,IT,CSD" tokenizes correctly.
    // ─────────────────────────────────────────────────────────────────────────
    private suspend fun processWhatsApp(
        db: AppDatabase,
        rawTitle: String,
        displayBody: String,
        searchLower: String,
        savedCourses: List<CourseModel>,
        placementChannels: List<PlacementChannelModel>
    ) {
        // Strip "(X)" count suffix WhatsApp appends to group names
        var finalTitle = rawTitle.substringBefore(" (").trim()

        // Tag urgent messages
        if (URGENT_KEYWORDS.any { searchLower.contains(it) }) {
            finalTitle = "🚨 URGENT: $finalTitle"
        }

        // Normalize the incoming title for matching
        val titleNormalized = normalizeForMatching(finalTitle)

        // ── PASS 1: Exact substring — Placements ──────────────
        for (channel in placementChannels) {
            val wGroup = channel.whatsappGroupName?.trim()?.lowercase() ?: continue
            if (wGroup.isNotEmpty() && titleNormalized.contains(normalizeForMatching(wGroup))) {
                saveNotification(db, finalTitle, displayBody, BUCKET_PLACEMENTS, "whatsapp")
                Log.d("NotifHQ", "WA exact → Placements: $finalTitle")
                return
            }
        }

        // ── PASS 1: Exact substring — Academic courses ─────────
        for (course in savedCourses) {
            val wGroup = course.whatsappGroupName?.trim()?.lowercase() ?: continue
            if (wGroup.isNotEmpty() && titleNormalized.contains(normalizeForMatching(wGroup))) {
                saveNotification(db, finalTitle, displayBody, course.courseName, "whatsapp")
                Log.d("NotifHQ", "WA exact → ${course.courseName}: $finalTitle")
                return
            }
        }

        // ── PASS 2: Fuzzy — Placements ────────────────────────
        var bestPlacementMatch: PlacementChannelModel? = null
        var bestPlacementScore = 0f

        for (channel in placementChannels) {
            val wGroup = channel.whatsappGroupName?.trim()?.lowercase() ?: continue
            if (wGroup.isEmpty()) continue
            val score = computeFuzzyScore(
                savedName  = normalizeForMatching(wGroup),
                notifTitle = titleNormalized
            )
            Log.d("NotifHQ", "WA fuzzy [Placement: ${channel.label}] score=$score title=$titleNormalized")
            if (score > bestPlacementScore) {
                bestPlacementScore = score
                bestPlacementMatch = channel
            }
        }

        if (bestPlacementMatch != null && bestPlacementScore >= FUZZY_MATCH_THRESHOLD_PLACEMENTS) {
            saveNotification(db, finalTitle, displayBody, BUCKET_PLACEMENTS, "whatsapp")
            Log.d("NotifHQ", "WA fuzzy → Placements (score=$bestPlacementScore): $finalTitle")
            return
        }

        // ── PASS 2: Fuzzy — Academic courses ──────────────────
        var bestCourseMatch: CourseModel? = null
        var bestCourseScore = 0f

        for (course in savedCourses) {
            val wGroup = course.whatsappGroupName?.trim()?.lowercase() ?: continue
            if (wGroup.isEmpty()) continue
            val score = computeFuzzyScore(
                savedName  = normalizeForMatching(wGroup),
                notifTitle = titleNormalized
            )
            Log.d("NotifHQ", "WA fuzzy [Course: ${course.courseName}] score=$score title=$titleNormalized")
            if (score > bestCourseScore) {
                bestCourseScore = score
                bestCourseMatch = course
            }
        }

        if (bestCourseMatch != null && bestCourseScore >= FUZZY_MATCH_THRESHOLD_ACADEMIC) {
            saveNotification(db, finalTitle, displayBody, bestCourseMatch.courseName, "whatsapp")
            Log.d("NotifHQ", "WA fuzzy → ${bestCourseMatch.courseName} (score=$bestCourseScore): $finalTitle")
            return
        }

        Log.d("NotifHQ", "WA unmatched, dropped (best course=$bestCourseScore, best placement=$bestPlacementScore): $finalTitle")
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
    // GMAIL PROCESSOR — strict priority pipeline
    //
    // PRIORITY ORDER:
    //   1. NPTEL sender (DB channels) → route to that NPTEL channel
    //   2. NPTEL content keywords (strict, NPTEL-specific only) → NPTEL bucket
    //   3. Placement sender/domain match → Placements bucket
    //   4. Academic course match (courseId > symbol > name) → specific course
    //   5. Generic academic keyword fallback → Important Emails bucket
    //   6. Drop
    //
    // FIX 1 is applied here: isNptelSender requires senderLower to be
    //   non-empty AND uses strict one-directional contains check only
    //   (senderLower.contains(email)), NOT the reverse email.contains(sender)
    //   which was matching empty/short senders against everything.
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
        val senderLower   = senderAddress.lowercase().trim()

        Log.d("NotifHQ", "Gmail | sender: '$senderLower' | title: $rawTitle")

        val nptelChannels = db.nptelChannelDao().getAllChannels()

        // Build a set of all registered NPTEL sender emails (from DB channels)
        val allNptelEmails = nptelChannels
            .flatMap { ch ->
                ch.emailAddresses
                    .split(",")
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }
            }
            .toSet()

        // ── PRIORITY 1: NPTEL DB channel sender match ─────────
        // STRICT: sender must be non-empty AND must contain a registered NPTEL email.
        // We do NOT check the reverse (email.contains(sender)) — that was the bug.
        val isNptelDbSender = senderLower.isNotEmpty() &&
                allNptelEmails.any { nptelEmail ->
                    senderLower.contains(nptelEmail)
                }

        if (isNptelDbSender) {
            val matchedNptelChannel = nptelChannels.firstOrNull { ch ->
                ch.emailAddresses
                    .split(",")
                    .map { it.trim().lowercase() }
                    .any { email -> senderLower.contains(email) }
            }
            val nptelSource = matchedNptelChannel?.label ?: BUCKET_NPTEL
            val finalTitle  = buildDueTitle(rawTitle, fullCorpus) ?: "📚 $rawTitle"
            saveNotification(db, finalTitle, displayBody, nptelSource, "gmail")
            Log.d("NotifHQ", "Gmail → NPTEL DB sender [$nptelSource]")
            return
        }

        // ── PRIORITY 2: NPTEL content keywords (strict) ───────
        // Only fires if the email body contains genuinely NPTEL-specific terms.
        // Generic terms like "assignment deadline" were removed from NPTEL_KEYWORDS
        // to prevent false positives.
        val isNptelContent = NPTEL_KEYWORDS.any { keyword ->
            searchLower.contains(keyword)
        }

        if (isNptelContent) {
            val finalTitle = buildDueTitle(rawTitle, fullCorpus) ?: "📚 $rawTitle"
            saveNotification(db, finalTitle, displayBody, BUCKET_NPTEL, "gmail")
            Log.d("NotifHQ", "Gmail → NPTEL keyword match")
            return
        }

        // ── PRIORITY 3: Placement sender check ────────────────
        // Build all registered placement email addresses
        val allPlacementEmails = placementChannels
            .flatMap { channel ->
                channel.emailAddresses
                    .split(",")
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }
            }
            .toSet()

        // STRICT: sender must be non-empty for email matching
        val isPlacementSender = senderLower.isNotEmpty() &&
                allPlacementEmails.any { placementEmail ->
                    senderLower.contains(placementEmail)
                }

        // Also check the sender's domain against registered placement domains
        val placementDomains = placementChannels
            .flatMap { it.emailAddresses.split(",") }
            .map { it.trim().lowercase() }
            .mapNotNull { email ->
                val domain = email.substringAfter("@", "")
                if (domain.isNotEmpty()) domain else null
            }
            .toSet()

        val isPlacementDomain = senderLower.isNotEmpty() &&
                placementDomains.any { domain ->
                    domain.isNotEmpty() && senderLower.contains(domain)
                }

        if (isPlacementSender || isPlacementDomain) {
            val finalTitle = buildDueTitle(rawTitle, fullCorpus) ?: "🏢 $rawTitle"
            saveNotification(db, finalTitle, displayBody, BUCKET_PLACEMENTS, "gmail")
            Log.d("NotifHQ", "Gmail → Placements (sender/domain match: $senderLower)")
            return
        }

        // ── PRIORITY 4: Best academic course match ─────────────
        // Scores: courseId=+3, courseSymbol=+2, courseName=+1
        // Minimum score of 1 required to route to a specific course.
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

        // ── PRIORITY 5: Generic academic keyword fallback ──────
        val isAcademic = GMAIL_ACADEMIC_KEYWORDS.any { keyword ->
            searchLower.contains(keyword)
        }
        if (isAcademic) {
            val finalTitle = buildDueTitle(rawTitle, fullCorpus) ?: "📧 $rawTitle"
            saveNotification(db, finalTitle, displayBody, BUCKET_IMPORTANT_EMAILS, "gmail")
            Log.d("NotifHQ", "Gmail → Important Emails (generic keyword)")
            return
        }

        // ── PRIORITY 6: Drop ───────────────────────────────────
        Log.d("NotifHQ", "Gmail dropped (no match): $rawTitle | sender: $senderLower")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STRING NORMALIZER FOR MATCHING
    //
    // FIX 3: Converts special characters (commas, dots, slashes, etc.)
    // to spaces before tokenizing. This ensures a group name like
    // "2027 AIDS,AIML,IT,CSD" is correctly tokenized into
    // ["2027", "aids", "aiml", "csd"] (after MIN_TOKEN_LENGTH filter removes "it")
    // instead of the original split failing on the comma-separated tokens.
    // ─────────────────────────────────────────────────────────────────────────
    private fun normalizeForMatching(input: String): String {
        return input
            .lowercase()
            .replace(Regex("[,./\\\\|;:!@#\$%^&*()+=<>?]"), " ")  // replace punctuation with space
            .replace(Regex("\\s+"), " ")                             // collapse multiple spaces
            .trim()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FUZZY SCORE HELPER
    //
    // Token overlap: what fraction of the saved name's tokens appear in
    // the notification title's tokens?
    //
    // Both inputs should be pre-normalized via normalizeForMatching().
    //
    // Examples (after normalization):
    //   saved="2027 aids aiml csd"  title="2027 aids aiml it csd"  → 1.0 ✅
    //   saved="nlpa batch"          title="nlpa batch a"            → 1.0 ✅
    //   saved="cloud computing"     title="coding club"             → 0.0 ❌
    // ─────────────────────────────────────────────────────────────────────────
    private fun computeFuzzyScore(savedName: String, notifTitle: String): Float {
        val savedTokens = savedName
            .split(Regex("\\s+"))
            .filter { it.length >= MIN_TOKEN_LENGTH }

        if (savedTokens.isEmpty()) return 0f

        val titleTokens = notifTitle
            .split(Regex("\\s+"))
            .filter { it.length >= MIN_TOKEN_LENGTH }
            .toSet()

        val matchedCount = savedTokens.count { savedToken ->
            titleTokens.any { titleToken ->
                titleToken == savedToken || titleToken.startsWith(savedToken)
            }
        }

        return matchedCount.toFloat() / savedTokens.size.toFloat()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // EMAIL COURSE MATCH SCORER
    //
    // Scoring weights (higher = more specific match):
    //   +3 courseId     (e.g. "AD23B32" — most specific)
    //   +2 courseSymbol (e.g. "NLPA")
    //   +1 courseName   (e.g. "Natural Language Processing Analytics")
    // ─────────────────────────────────────────────────────────────────────────
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
    // Tries stdText first (Gmail usually puts "from: sender@domain.com" there),
    // then rawTitle, then the full corpus as a last resort.
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
    // If the email body contains a due date signal, prefixes the title
    // with "⏰ DUE <date> —" for visual priority in the feed.
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