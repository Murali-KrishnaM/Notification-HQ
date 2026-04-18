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

        // Gmail academic keyword filter (PRIORITY 5 catch-all — generic terms only)
        private val GMAIL_ACADEMIC_KEYWORDS = listOf(
            "quiz", "notes", "resource", "submission", "exam",
            "project", "review", "lab", "viva", "internal",
            "closes on", "due date", "due", "class cancelled",
            "rescheduled", "postponed", "marks", "grade", "result",
            "timetable", "schedule", "assignment", "deadline"
        )

        // ── NPTEL keywords — STRICT, NPTEL-specific terms ONLY ──
        private val NPTEL_KEYWORDS = listOf(
            "nptel",
            "swayam",
            "nptel.iitm.ac.in",
            "onlinecourses.nptel.ac.in",
            "nptel online",
            "nptel course",
            "onlinecourses.nptel"
        )

        // ── Urgency keywords for WhatsApp display tagging ──────
        // NOTE: These are used ONLY for prefixing the display title.
        // Matching against group names is ALWAYS done on the clean stripped title.
        private val URGENT_KEYWORDS = listOf(
            "room change", "room no", "cancel", "cancelled",
            "rescheduled", "postponed", "urgent", "important",
            "moved to", "shifted to", "no class", "holiday", "come", "go"
        )

        private val ALLOWED_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.google.android.gm",
            "com.google.android.apps.classroom"
        )

        // Bucket names — used ONLY for unmatched fallback routing.
        // Matched placement channels are saved under channel.label directly.
        const val BUCKET_IMPORTANT_EMAILS  = "📧 Important Emails"
        const val BUCKET_GENERAL_CLASSROOM = "📘 General Classroom"
        const val BUCKET_NPTEL             = "📚 NPTEL"

        // ── Fuzzy thresholds ───────────────────────────────────
        // Academic groups: stricter (0.6) to avoid wrong course routing
        private const val FUZZY_MATCH_THRESHOLD_ACADEMIC   = 0.6f
        // Placement groups: more lenient (0.5) because names like
        // "2027 AIDS,AIML,IT,CSD" have many tokens and partial matches are valid
        private const val FUZZY_MATCH_THRESHOLD_PLACEMENTS = 0.5f

        // Ignore tokens shorter than this (filters "a", "of", "it" noise).
        // Set to 3 to also filter 2-letter noise like "it" that would otherwise
        // incorrectly match "IT" in group names like "2027 AIDS,AIML,IT,CSD".
        private const val MIN_TOKEN_LENGTH = 3
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENTRY POINT
    // ─────────────────────────────────────────────────────────────────────────
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

            // ── Echo Killer ────────────────────────────────────
            // WhatsApp fires two events per message:
            //   Event A — stdText = "Sender: message body"
            //   Event B — bigText = "message body (extra)"
            // Build fingerprint from the group title + last 80 chars of body
            // (stripped of sender prefix) so both events share the same print.
            val normalizedTitle = title
                .substringBefore(" (")   // strip "(5)" count suffix
                .lowercase()
                .trim()

            val bodyForFingerprint = displayBody
                .replace(Regex("^[^:]{1,40}:\\s*"), "")  // remove "Name: " prefix
                .lowercase()
                .trim()

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
    //
    // CRITICAL DESIGN RULE:
    //   The "strippedTitle" (raw WA group name, no count suffix) is used for ALL
    //   matching logic. The urgent emoji prefix is added AFTER matching, ONLY for
    //   the saved display title. This prevents "🚨 URGENT:" from polluting the
    //   normalized string and breaking both exact and fuzzy matching.
    //
    // PASS 1 — Exact substring match (fastest, most reliable):
    //   Checks if the stripped/normalized WA title contains the saved group name.
    //   Handles cases like "REC HABITAT" matching "REC HABITAT" exactly, or
    //   "2027 AIDS,AIML,IT,CSD" matching a saved group of same name.
    //
    // PASS 2 — Fuzzy token match:
    //   Handles minor variations in group naming (e.g. "NLPA Batch A" vs "NLPA Batch").
    //   Placements use a lower threshold (0.5) since their group names are longer
    //   and partial token overlap is still a strong signal.
    //
    // ROUTING TARGET:
    //   Placement channels  → saved under channel.label  (e.g. "Hostel", "Placement Cell")
    //   Academic courses    → saved under course.courseName
    //   Unmatched           → DROPPED (no fallback bucket for WhatsApp)
    // ─────────────────────────────────────────────────────────────────────────
    private suspend fun processWhatsApp(
        db: AppDatabase,
        rawTitle: String,
        displayBody: String,
        searchLower: String,
        savedCourses: List<CourseModel>,
        placementChannels: List<PlacementChannelModel>
    ) {
        // Step 1: Strip the "(X new messages)" count suffix WhatsApp appends.
        // This gives us the clean group name as it was saved by the user.
        val strippedTitle = rawTitle.substringBefore(" (").trim()

        // Step 2: Normalize the stripped title for matching.
        // IMPORTANT: This must happen BEFORE the urgent prefix is added,
        // so matching always works on the clean group name.
        val titleNormalized = normalizeForMatching(strippedTitle)

        // Step 3: Determine if this message is urgent (for display only).
        // The urgent check uses searchLower (full message body), not the title.
        val isUrgent = URGENT_KEYWORDS.any { keyword ->
            searchLower.contains(keyword.lowercase())
        }

        // Step 4: Build the final display title (with optional urgent prefix).
        // This is ONLY used when saving — never for matching.
        val finalTitle = if (isUrgent) "🚨 URGENT: $strippedTitle" else strippedTitle

        Log.d("NotifHQ", "WA | stripped='$strippedTitle' | normalized='$titleNormalized' | urgent=$isUrgent")

        // ═══════════════════════════════════════════════════════
        // PASS 1 — EXACT SUBSTRING MATCH
        // Check if the normalized WA title contains the saved group name.
        // Placements are checked first (higher priority than academic courses).
        // ═══════════════════════════════════════════════════════

        // Pass 1a: Placement channels
        for (channel in placementChannels) {
            val wGroup = channel.whatsappGroupName?.trim() ?: continue
            if (wGroup.isEmpty()) continue
            val wGroupNormalized = normalizeForMatching(wGroup)
            if (titleNormalized.contains(wGroupNormalized)) {
                // FIX: Route to channel.label, NOT a generic "🏢 Placements" bucket.
                // Each channel (Hostel, Hostel 2, Placement Cell, LinkedIn) has its
                // own detail screen that queries notifications by channel.label.
                saveNotification(db, finalTitle, displayBody, channel.label, "whatsapp")
                Log.d("NotifHQ", "WA exact → [${channel.label}]: '$strippedTitle'")
                return
            }
        }

        // Pass 1b: Academic courses
        for (course in savedCourses) {
            val wGroup = course.whatsappGroupName?.trim() ?: continue
            if (wGroup.isEmpty()) continue
            val wGroupNormalized = normalizeForMatching(wGroup)
            if (titleNormalized.contains(wGroupNormalized)) {
                saveNotification(db, finalTitle, displayBody, course.courseName, "whatsapp")
                Log.d("NotifHQ", "WA exact → [${course.courseName}]: '$strippedTitle'")
                return
            }
        }

        // ═══════════════════════════════════════════════════════
        // PASS 2 — FUZZY TOKEN MATCH
        // Computes token overlap between saved group name and WA notification title.
        // Both savedName and notifTitle must be pre-normalized.
        // Placements are evaluated first.
        // ═══════════════════════════════════════════════════════

        // Pass 2a: Placement channels (fuzzy)
        var bestPlacementMatch: PlacementChannelModel? = null
        var bestPlacementScore = 0f

        for (channel in placementChannels) {
            val wGroup = channel.whatsappGroupName?.trim() ?: continue
            if (wGroup.isEmpty()) continue
            val score = computeFuzzyScore(
                savedName  = normalizeForMatching(wGroup),
                notifTitle = titleNormalized  // FIX: use titleNormalized (clean), not finalTitle (has emoji prefix)
            )
            Log.d("NotifHQ", "WA fuzzy [Placement: ${channel.label}] score=$score")
            if (score > bestPlacementScore) {
                bestPlacementScore = score
                bestPlacementMatch = channel
            }
        }

        if (bestPlacementMatch != null && bestPlacementScore >= FUZZY_MATCH_THRESHOLD_PLACEMENTS) {
            // FIX: Route to channel.label, NOT a generic bucket.
            saveNotification(db, finalTitle, displayBody, bestPlacementMatch.label, "whatsapp")
            Log.d("NotifHQ", "WA fuzzy → [${bestPlacementMatch.label}] (score=$bestPlacementScore): '$strippedTitle'")
            return
        }

        // Pass 2b: Academic courses (fuzzy)
        var bestCourseMatch: CourseModel? = null
        var bestCourseScore = 0f

        for (course in savedCourses) {
            val wGroup = course.whatsappGroupName?.trim() ?: continue
            if (wGroup.isEmpty()) continue
            val score = computeFuzzyScore(
                savedName  = normalizeForMatching(wGroup),
                notifTitle = titleNormalized  // FIX: use titleNormalized, not finalTitle
            )
            Log.d("NotifHQ", "WA fuzzy [Course: ${course.courseName}] score=$score")
            if (score > bestCourseScore) {
                bestCourseScore = score
                bestCourseMatch = course
            }
        }

        if (bestCourseMatch != null && bestCourseScore >= FUZZY_MATCH_THRESHOLD_ACADEMIC) {
            saveNotification(db, finalTitle, displayBody, bestCourseMatch.courseName, "whatsapp")
            Log.d("NotifHQ", "WA fuzzy → [${bestCourseMatch.courseName}] (score=$bestCourseScore): '$strippedTitle'")
            return
        }

        // No match found — drop silently.
        Log.d("NotifHQ", "WA unmatched, dropped | bestCourse=$bestCourseScore bestPlacement=$bestPlacementScore | title='$strippedTitle'")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CLASSROOM PROCESSOR
    //
    // Tries to match the notification to a specific course by classroomName.
    // Falls back to the general classroom bucket if no course matches.
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
                Log.d("NotifHQ", "Classroom → [${course.courseName}]: '$rawTitle'")
                return
            }
        }

        saveNotification(db, finalTitle, displayBody, BUCKET_GENERAL_CLASSROOM, "classroom")
        Log.d("NotifHQ", "Classroom → General bucket: '$rawTitle'")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GMAIL PROCESSOR — strict priority pipeline
    //
    // PRIORITY ORDER:
    //   1. NPTEL DB channel sender match  → that NPTEL channel's label
    //   2. NPTEL content keywords (strict) → NPTEL bucket
    //   3. Placement sender / domain match → that placement channel's label
    //   4. Academic course match (courseId > symbol > name) → specific course
    //   5. Generic academic keyword fallback → Important Emails bucket
    //   6. Drop
    //
    // Gmail placement routing now saves to channel.label (same fix as WhatsApp).
    // The match finds which specific placement channel owns the sender email/domain,
    // so "Placement Cell" emails go to "Placement Cell", "LinkedIn" to "LinkedIn", etc.
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

        Log.d("NotifHQ", "Gmail | sender: '$senderLower' | title: '$rawTitle'")

        val nptelChannels = db.nptelChannelDao().getAllChannels()

        // Build set of all registered NPTEL sender emails
        val allNptelEmails = nptelChannels
            .flatMap { ch ->
                ch.emailAddresses
                    .split(",")
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }
            }
            .toSet()

        // ── PRIORITY 1: NPTEL DB channel sender match ─────────
        // STRICT: sender must be non-empty. Only check senderLower.contains(email),
        // never the reverse — the reverse would match empty senders against everything.
        val isNptelDbSender = senderLower.isNotEmpty() &&
                allNptelEmails.any { nptelEmail -> senderLower.contains(nptelEmail) }

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
        val isNptelContent = NPTEL_KEYWORDS.any { keyword -> searchLower.contains(keyword) }
        if (isNptelContent) {
            val finalTitle = buildDueTitle(rawTitle, fullCorpus) ?: "📚 $rawTitle"
            saveNotification(db, finalTitle, displayBody, BUCKET_NPTEL, "gmail")
            Log.d("NotifHQ", "Gmail → NPTEL keyword match")
            return
        }

        // ── PRIORITY 3: Placement sender / domain match ────────
        // For each placement channel, check its registered emails and domains.
        // If a match is found, route to THAT channel's label — not a generic bucket.
        // This ensures "Placement Cell" emails go to "Placement Cell",
        // "LinkedIn" emails go to "LinkedIn", etc.
        if (senderLower.isNotEmpty()) {
            for (channel in placementChannels) {
                val channelEmails = channel.emailAddresses
                    .split(",")
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }

                // Check exact email match
                val emailMatch = channelEmails.any { email -> senderLower.contains(email) }

                // Check domain match
                val domainMatch = channelEmails.any { email ->
                    val domain = email.substringAfter("@", "")
                    domain.isNotEmpty() && senderLower.contains(domain)
                }

                if (emailMatch || domainMatch) {
                    val finalTitle = buildDueTitle(rawTitle, fullCorpus) ?: "🏢 $rawTitle"
                    // FIX: Route to channel.label, not a generic bucket.
                    saveNotification(db, finalTitle, displayBody, channel.label, "gmail")
                    Log.d("NotifHQ", "Gmail → Placement [${channel.label}] (sender: $senderLower)")
                    return
                }
            }
        }

        // ── PRIORITY 4: Best academic course match ─────────────
        // Scores: courseId=+3, courseSymbol=+2, courseName=+1
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
        val isAcademic = GMAIL_ACADEMIC_KEYWORDS.any { keyword -> searchLower.contains(keyword) }
        if (isAcademic) {
            val finalTitle = buildDueTitle(rawTitle, fullCorpus) ?: "📧 $rawTitle"
            saveNotification(db, finalTitle, displayBody, BUCKET_IMPORTANT_EMAILS, "gmail")
            Log.d("NotifHQ", "Gmail → Important Emails (generic keyword)")
            return
        }

        // ── PRIORITY 6: Drop ───────────────────────────────────
        Log.d("NotifHQ", "Gmail dropped (no match) | sender: '$senderLower' | title: '$rawTitle'")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STRING NORMALIZER FOR MATCHING
    //
    // Converts special characters (commas, dots, slashes, etc.) to spaces,
    // then collapses multiple spaces. This ensures a group name like
    // "2027 AIDS,AIML,IT,CSD" normalizes to "2027 aids aiml it csd"
    // so tokens split cleanly for both exact and fuzzy matching.
    // ─────────────────────────────────────────────────────────────────────────
    private fun normalizeForMatching(input: String): String {
        return input
            .lowercase()
            .replace(Regex("[,./\\\\|;:!@#\$%^&*()+=<>?]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FUZZY SCORE HELPER
    //
    // Computes what fraction of the saved group name's tokens appear in the
    // notification title's tokens. Both inputs must be pre-normalized.
    //
    // Logic: For each token in the saved name, check if it appears (exact or
    // prefix match) in the title's token set. Score = matches / total saved tokens.
    //
    // Examples (after normalization):
    //   saved="2027 aids aiml csd"    title="2027 aids aiml it csd"  → 1.0 ✅
    //   saved="rec habitat"           title="rec habitat"             → 1.0 ✅
    //   saved="rec hostelerz"         title="rec hostelerz"           → 1.0 ✅
    //   saved="nlpa batch"            title="nlpa batch a"            → 1.0 ✅
    //   saved="cloud computing"       title="coding club"             → 0.0 ❌
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
    //   +3 courseId     (e.g. "AD23B32" — most specific identifier)
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
    // Scans stdText first (Gmail puts sender email there), then rawTitle,
    // then the full corpus as a last resort.
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
    // If the email body contains a due date signal, prefixes the display title
    // with "⏰ DUE <date> —" for visual priority in the notification feed.
    // ─────────────────────────────────────────────────────────────────────────
    private fun buildDueTitle(rawTitle: String, fullCorpus: String): String? {
        val corpusLower = fullCorpus.lowercase()
        val hasDueDate  = corpusLower.contains("closes on")  ||
                corpusLower.contains("due date")   ||
                corpusLower.contains("due on")     ||
                corpusLower.contains("last date")

        if (!hasDueDate) return null

        val datePart = fullCorpus
            .split(Regex("(?i)(closes on\\s*:?|due date\\s*:?|due on\\s*:?|last date\\s*:?deadline\\s*:?)"))
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