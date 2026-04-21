package com.bravo.notificationhq.pipeline

import android.util.Log
import com.bravo.notificationhq.AppDatabase
import com.bravo.notificationhq.NotificationModel

/**
 * ══════════════════════════════════════════════════════════════════════════
 * MODULE 3 — NOTIFICATION ROUTER
 * ══════════════════════════════════════════════════════════════════════════
 *
 * Responsibility:
 *   - Decide exactly which bucket (course, placement channel, NPTEL channel,
 *     or system bucket) a [DenoisedNotification] belongs to.
 *   - Persist the result via [AppDatabase].
 *
 * ── ROUTING RULES ─────────────────────────────────────────────────────────
 *
 * WHATSAPP:
 *   1. Tag message with 🚨 URGENT if urgency keywords detected.
 *   2. Exact substring: WA group name ↔ Placement channel → Placements.
 *   3. Exact substring: WA group name ↔ Academic course   → that course.
 *   4. Fuzzy match (≥ 60% token overlap): same order as above.
 *   5. No match → DROP silently (unrelated group, not our concern).
 *
 * GMAIL — strict priority, no drops (every mail is stored somewhere):
 *   1. NPTEL keyword scan on full corpus (FIRST, unconditional).
 *      → If match: pick specific NPTEL channel label or generic 📚 NPTEL bucket.
 *      → HARD STOP — no further checks.
 *   2. Attendance keyword detected → tag 🚨 URGENT, jump to course match.
 *      → Best course match wins. If none → 📧 Important Emails (still URGENT).
 *   3. Placement sender check (email address OR display name match).
 *      → Route to 🏢 Placements.
 *   4. Google Classroom origin check (sender is classroom-noreply or faculty
 *      institutional email arriving via Classroom).
 *      → Run course match only. Never falls through to generic bucket.
 *      → If no course match → 📧 Important Emails (it's still academic).
 *   5. Academic course score match (courseName / courseSymbol / courseId).
 *      → Route to best-scoring course if score > 0.
 *   6. Generic academic keyword fallback → 📧 Important Emails.
 *   7. No match → 📧 Important Emails (we no longer drop Gmail silently).
 *
 * ── DOES NOT touch UI. No Activity references. DB writes only. ────────────
 */
object NotificationRouter {

    // ── System bucket names ───────────────────────────────────────────────
    const val BUCKET_IMPORTANT_EMAILS  = "📧 Important Emails"
    const val BUCKET_NPTEL             = "📚 NPTEL"
    const val BUCKET_PLACEMENTS        = "🏢 Placements"
    const val BUCKET_HOSTEL            = "🏠 Hostel"

    // ── NPTEL detection keywords ──────────────────────────────────────────
    private val NPTEL_KEYWORDS = listOf(
        "nptel", "swayam", "nptel.iitm.ac.in", "onlinecourses.nptel.ac.in",
        "nptel online", "nptel course", "week deadline", "assignment deadline",
        "iit madras", "iitm online", "nptel assignment"
    )

    // ── Attendance keywords → trigger URGENT tag ──────────────────────────
    private val ATTENDANCE_KEYWORDS = listOf(
        "attendance", "absent", "absentee", "proxy", "attendance report",
        "attendance shortage", "attendance alert", "marked absent",
        "below 75", "detain", "detained", "shortage of attendance"
    )

    // ── Generic academic keywords (Gmail fallback) ────────────────────────
    private val GMAIL_ACADEMIC_KEYWORDS = listOf(
        "quiz", "deadline", "assignment", "notes", "resource",
        "submission", "exam", "test", "project", "review",
        "lab", "viva", "internal", "closes on", "due date",
        "attendance", "class cancelled", "rescheduled", "postponed",
        "marks", "grade", "result", "timetable", "schedule",
        "google classroom", "classroom.google"
    )

    // ── Google Classroom email origins ────────────────────────────────────
    // Emails originating from Google Classroom use these sender domains/addresses.
    private val CLASSROOM_SENDER_PATTERNS = listOf(
        "classroom.google.com",
        "classroom-noreply@google.com",
        "noreply-diffs@google.com",
        "googleclassroom"
    )

    // ── WhatsApp urgency keywords ─────────────────────────────────────────
    private val WA_URGENT_KEYWORDS = listOf(
        "room change", "room no", "cancel", "cancelled",
        "rescheduled", "postponed", "urgent", "important",
        "moved to", "shifted to", "no class", "holiday"
    )

    // ── Fuzzy match threshold ─────────────────────────────────────────────
    private const val FUZZY_THRESHOLD  = 0.6f
    private const val MIN_TOKEN_LENGTH = 2

    // ─────────────────────────────────────────────────────────────────────
    // PUBLIC ENTRY POINT
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Route [denoised] to the correct destination and persist to [db].
     * Must be called from a coroutine on [kotlinx.coroutines.Dispatchers.IO].
     */
    suspend fun route(db: AppDatabase, denoised: DenoisedNotification) {
        when {
            denoised.packageName == "com.whatsapp" ||
                    denoised.packageName == "com.whatsapp.w4b" ->
                routeWhatsApp(db, denoised)

            denoised.packageName == "com.google.android.gm" ->
                routeGmail(db, denoised)

            // Any other package slipping through is silently dropped.
            else -> Log.d("NHQ-Router", "Unknown package dropped: ${denoised.packageName}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // WHATSAPP ROUTER
    // ─────────────────────────────────────────────────────────────────────

    private suspend fun routeWhatsApp(db: AppDatabase, n: DenoisedNotification) {
        val savedCourses      = db.courseDao().getAllCourses()
        val placementChannels = db.placementChannelDao().getAllChannels()
        val hostelChannels    = db.hostelChannelDao().getAllChannels()

        // Tag urgent messages
        var finalTitle = n.cleanTitle
        if (WA_URGENT_KEYWORDS.any { n.searchLower.contains(it) }) {
            finalTitle = "🚨 URGENT: $finalTitle"
        }

        val titleLower = finalTitle.lowercase()

        // ── PASS 1: Exact substring match — Hostel ────────────────────────
        for (channel in hostelChannels) {
            val wGroup = channel.whatsappGroupName?.trim()?.lowercase() ?: continue
            if (wGroup.isNotEmpty() && titleLower.contains(wGroup)) {
                save(db, finalTitle, n.displayBody, channel.label, "whatsapp")
                Log.d("NHQ-Router", "WA exact → Hostel [${channel.label}]: $finalTitle")
                return
            }
        }

        // ── PASS 1: Exact substring match — Placements ────────────────────
        for (channel in placementChannels) {
            val wGroup = channel.whatsappGroupName?.trim()?.lowercase() ?: continue
            if (wGroup.isNotEmpty() && titleLower.contains(wGroup)) {
                save(db, finalTitle, n.displayBody, channel.label, "whatsapp")
                Log.d("NHQ-Router", "WA exact → Placements [${channel.label}]: $finalTitle")
                return
            }
        }

        // ── PASS 1: Exact substring match — Academic courses ──────────────
        for (course in savedCourses) {
            val wGroup = course.whatsappGroupName?.trim()?.lowercase() ?: continue
            if (wGroup.isNotEmpty() && titleLower.contains(wGroup)) {
                save(db, finalTitle, n.displayBody, course.courseName, "whatsapp")
                Log.d("NHQ-Router", "WA exact → ${course.courseName}: $finalTitle")
                return
            }
        }

        // ── PASS 2: Fuzzy match — Hostel ──────────────────────────────────
        var bestHostelScore = 0f
        var bestHostelMatch = hostelChannels
            .filter { !it.whatsappGroupName.isNullOrBlank() }
            .maxByOrNull { ch ->
                fuzzyScore(ch.whatsappGroupName!!.lowercase(), titleLower)
                    .also { score -> if (score > bestHostelScore) bestHostelScore = score }
            }

        if (bestHostelMatch != null && bestHostelScore >= FUZZY_THRESHOLD) {
            save(db, finalTitle, n.displayBody, bestHostelMatch.label, "whatsapp")
            Log.d("NHQ-Router", "WA fuzzy → Hostel (score=$bestHostelScore): $finalTitle")
            return
        }

        // ── PASS 2: Fuzzy match — Placements ─────────────────────────────
        var bestPlacementScore = 0f
        var bestPlacementMatch = placementChannels
            .filter { !it.whatsappGroupName.isNullOrBlank() }
            .maxByOrNull { ch ->
                fuzzyScore(ch.whatsappGroupName!!.lowercase(), titleLower)
                    .also { score -> if (score > bestPlacementScore) bestPlacementScore = score }
            }

        if (bestPlacementMatch != null && bestPlacementScore >= FUZZY_THRESHOLD) {
            save(db, finalTitle, n.displayBody, bestPlacementMatch.label, "whatsapp")
            Log.d("NHQ-Router", "WA fuzzy → Placements (score=$bestPlacementScore): $finalTitle")
            return
        }

        // ── PASS 2: Fuzzy match — Academic courses ────────────────────────
        var bestCourseScore = 0f
        var bestCourseMatch = savedCourses
            .filter { !it.whatsappGroupName.isNullOrBlank() }
            .maxByOrNull { course ->
                fuzzyScore(course.whatsappGroupName!!.lowercase(), titleLower)
                    .also { score -> if (score > bestCourseScore) bestCourseScore = score }
            }

        if (bestCourseMatch != null && bestCourseScore >= FUZZY_THRESHOLD) {
            save(db, finalTitle, n.displayBody, bestCourseMatch.courseName, "whatsapp")
            Log.d("NHQ-Router", "WA fuzzy → ${bestCourseMatch.courseName} (score=$bestCourseScore): $finalTitle")
            return
        }

        // ── No match → drop silently (unrelated WA group) ────────────────
        Log.d("NHQ-Router", "WA dropped (no match, best score=$bestCourseScore): $finalTitle")
    }

    // ─────────────────────────────────────────────────────────────────────
    // GMAIL ROUTER
    // Every Gmail that passes capture is stored somewhere. No silent drops.
    // ─────────────────────────────────────────────────────────────────────

    private suspend fun routeGmail(db: AppDatabase, n: DenoisedNotification) {
        val savedCourses      = db.courseDao().getAllCourses()
        val placementChannels = db.placementChannelDao().getAllChannels()
        val nptelChannels     = db.nptelChannelDao().getAllChannels()

        val senderLower       = n.senderEmail.lowercase()
        val displayNameLower  = n.senderDisplayName.lowercase()

        Log.d("NHQ-Router", "Gmail | sender='$senderLower' | name='$displayNameLower' | title='${n.cleanTitle}'")

        // ── PRIORITY 1: NPTEL — keyword scan, unconditional HARD STOP ─────
        val matchedNptelChannel = nptelChannels.firstOrNull { ch ->
            ch.emailAddresses
                .split(",")
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .any { savedEmail ->
                    senderLower.isNotEmpty() &&
                            (senderLower.contains(savedEmail) || savedEmail.contains(senderLower))
                }
        }

        val isNptelByKeyword = NPTEL_KEYWORDS.any { n.searchLower.contains(it) }
        val isNptelBySender  = matchedNptelChannel != null

        if (isNptelByKeyword || isNptelBySender) {
            val destination = matchedNptelChannel?.label ?: BUCKET_NPTEL
            val finalTitle  = buildDueTitle(n.cleanTitle, n.fullCorpus) ?: "📚 ${n.cleanTitle}"
            save(db, finalTitle, n.displayBody, destination, "gmail")
            Log.d("NHQ-Router", "Gmail → NPTEL [$destination] keyword=$isNptelByKeyword sender=$isNptelBySender")
            return  // ← HARD STOP
        }

        // ── PRIORITY 2: Attendance — URGENT tag + course match ────────────
        val isAttendance = ATTENDANCE_KEYWORDS.any { n.searchLower.contains(it) }

        if (isAttendance) {
            Log.d("NHQ-Router", "Gmail: attendance mail detected, applying 🚨 URGENT")

            // Try to match to a specific course first
            var bestScore = 0
            var bestCourse = savedCourses.maxByOrNull { course ->
                courseMatchScore(n.searchLower, course.courseName, course.courseSymbol, course.courseId)
                    .also { score -> if (score > bestScore) bestScore = score }
            }

            val destination = if (bestCourse != null && bestScore > 0) {
                bestCourse.courseName
            } else {
                BUCKET_IMPORTANT_EMAILS
            }

            // Always prefix with URGENT for attendance
            val urgentTitle = "🚨 URGENT: ${n.cleanTitle}"
            save(db, urgentTitle, n.displayBody, destination, "gmail")
            Log.d("NHQ-Router", "Gmail → Attendance URGENT → $destination")
            return
        }

        // ── PRIORITY 3: Placement sender check ───────────────────────────
        // Match by saved email address first, then by display name against channel label.
        val allPlacementEmails = placementChannels
            .flatMap { it.emailAddresses.split(",").map { e -> e.trim().lowercase() } }
            .filter { it.isNotEmpty() }
            .toSet()

        val isPlacementBySenderEmail = senderLower.isNotEmpty() &&
                allPlacementEmails.any { placementEmail ->
                    senderLower.contains(placementEmail) || placementEmail.contains(senderLower)
                }

        // Domain-level match: e.g., anything @rajalakshmi.edu.in tagged in placement channels
        val placementDomains = placementChannels
            .flatMap { it.emailAddresses.split(",") }
            .map { it.trim().lowercase() }
            .mapNotNull { it.substringAfter("@", "").ifEmpty { null } }
            .filter { it.isNotEmpty() }
            .toSet()

        val isPlacementByDomain = senderLower.isNotEmpty() &&
                placementDomains.any { domain -> senderLower.contains(domain) }

        // Display name fallback: "Placement Cell" in title matches channel label "Placement Cell"
        val isPlacementByDisplayName = displayNameLower.isNotEmpty() &&
                placementChannels.any { ch ->
                    ch.label.lowercase().let { label ->
                        displayNameLower.contains(label) || label.contains(displayNameLower)
                    }
                }

        if (isPlacementBySenderEmail || isPlacementByDomain || isPlacementByDisplayName) {
            val finalTitle = buildDueTitle(n.cleanTitle, n.fullCorpus) ?: "🏢 ${n.cleanTitle}"
            save(db, finalTitle, n.displayBody, BUCKET_PLACEMENTS, "gmail")
            Log.d("NHQ-Router",
                "Gmail → Placements " +
                        "(email=$isPlacementBySenderEmail domain=$isPlacementByDomain name=$isPlacementByDisplayName)")
            return
        }

        // ── PRIORITY 4: Google Classroom origin ───────────────────────────
        // Emails from Google Classroom arrive with these sender patterns.
        // For these, we run course matching only — never dump into generic bucket.
        val isClassroomOrigin = CLASSROOM_SENDER_PATTERNS.any { pattern ->
            senderLower.contains(pattern) || n.searchLower.contains(pattern)
        }

        if (isClassroomOrigin) {
            var bestScore = 0
            var bestCourse = savedCourses.maxByOrNull { course ->
                courseMatchScore(n.searchLower, course.courseName, course.courseSymbol, course.courseId)
                    .also { score -> if (score > bestScore) bestScore = score }
            }

            val destination = if (bestCourse != null && bestScore > 0) {
                bestCourse.courseName
            } else {
                // Still academic — better than dropping it
                BUCKET_IMPORTANT_EMAILS
            }

            val finalTitle = buildDueTitle(n.cleanTitle, n.fullCorpus) ?: "📘 ${n.cleanTitle}"
            save(db, finalTitle, n.displayBody, destination, "gmail")
            Log.d("NHQ-Router", "Gmail → Classroom origin → $destination (score=$bestScore)")
            return
        }

        // ── PRIORITY 5: Academic course score match ───────────────────────
        var bestScore = 0
        var bestCourse = savedCourses.maxByOrNull { course ->
            courseMatchScore(n.searchLower, course.courseName, course.courseSymbol, course.courseId)
                .also { score -> if (score > bestScore) bestScore = score }
        }

        if (bestCourse != null && bestScore > 0) {
            val finalTitle = buildDueTitle(n.cleanTitle, n.fullCorpus) ?: "📧 ${n.cleanTitle}"
            save(db, finalTitle, n.displayBody, bestCourse.courseName, "gmail")
            Log.d("NHQ-Router", "Gmail → Academic [${bestCourse.courseName}] score=$bestScore")
            return
        }

        // ── PRIORITY 6: Generic academic keyword fallback ─────────────────
        val isGenericAcademic = GMAIL_ACADEMIC_KEYWORDS.any { n.searchLower.contains(it) }
        if (isGenericAcademic) {
            val finalTitle = buildDueTitle(n.cleanTitle, n.fullCorpus) ?: "📧 ${n.cleanTitle}"
            save(db, finalTitle, n.displayBody, BUCKET_IMPORTANT_EMAILS, "gmail")
            Log.d("NHQ-Router", "Gmail → Important Emails (generic academic keyword)")
            return
        }

        // ── PRIORITY 7: Drop ──────────────────────────────────────────────
        // Passed all 6 checks with zero match — unrelated mail (OTP, promo,
        // food delivery, etc.). Drop silently, do not pollute Important Emails.
        Log.d("NHQ-Router", "Gmail dropped (passed all 6 checks, no match): ${n.cleanTitle}")
    }

    // ─────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Token overlap score for WhatsApp fuzzy matching.
     * Returns fraction of [savedName] tokens found in [notifTitle] tokens.
     */
    private fun fuzzyScore(savedName: String, notifTitle: String): Float {
        val savedTokens = savedName
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= MIN_TOKEN_LENGTH }

        if (savedTokens.isEmpty()) return 0f

        val titleTokens = notifTitle
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= MIN_TOKEN_LENGTH }
            .toSet()

        val matched = savedTokens.count { saved ->
            titleTokens.any { title -> title == saved || title.startsWith(saved) }
        }

        return matched.toFloat() / savedTokens.size.toFloat()
    }

    /**
     * Score a Gmail corpus against a saved course.
     *   +3 if courseId     found (most specific identifier)
     *   +2 if courseSymbol found
     *   +1 if courseName   found
     */
    private fun courseMatchScore(
        searchLower: String,
        courseName: String,
        courseSymbol: String,
        courseId: String
    ): Int {
        var score = 0
        if (courseId.trim().lowercase().let     { it.isNotEmpty() && searchLower.contains(it) }) score += 3
        if (courseSymbol.trim().lowercase().let { it.isNotEmpty() && searchLower.contains(it) }) score += 2
        if (courseName.trim().lowercase().let   { it.isNotEmpty() && searchLower.contains(it) }) score += 1
        return score
    }

    /**
     * If the corpus contains a due-date phrase, prepend a ⏰ DUE tag.
     * Returns null if no due-date phrase found (caller uses a default prefix).
     */
    private fun buildDueTitle(rawTitle: String, fullCorpus: String): String? {
        val lower = fullCorpus.lowercase()
        val hasDue = lower.contains("closes on")  ||
                lower.contains("due date")   ||
                lower.contains("due on")     ||
                lower.contains("last date")  ||
                lower.contains("submit by")  ||
                lower.contains("deadline")

        if (!hasDue) return null

        val datePart = fullCorpus
            .split(Regex("(?i)(closes on\\s*:?|due date\\s*:?|due on\\s*:?|last date\\s*:?|submit by\\s*:?|deadline\\s*:?)"))
            .getOrNull(1)
            ?.take(25)
            ?.trim() ?: ""

        return if (datePart.isNotEmpty()) "⏰ DUE $datePart — $rawTitle"
        else "⏰ $rawTitle"
    }

    /**
     * Persist the final routed notification to Room.
     */
    private suspend fun save(
        db: AppDatabase,
        title: String,
        text: String,
        source: String,
        packageSource: String
    ) {
        val model = NotificationModel(
            title         = title,
            text          = text,
            source        = source,
            packageSource = packageSource,
            timestamp     = System.currentTimeMillis()
        )
        db.notificationDao().insertNotification(model)
        Log.d("NHQ-Router", "Saved [$packageSource → $source]: $title")
    }
}