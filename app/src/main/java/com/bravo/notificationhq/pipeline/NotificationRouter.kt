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
 *   1. Tag message with 🔴 URGENT if urgency keywords detected.
 *   2. Exact substring: WA group name ↔ Placement channel → Placements.
 *   3. Exact substring: WA group name ↔ Academic course   → that course.
 *   4. Fuzzy match (≥ 60% token overlap): same order as above.
 *   5. No match → DROP silently (unrelated group, not our concern).
 *
 * GMAIL — strict priority, no drops (every mail is stored somewhere):
 *   1. NPTEL keyword scan on full corpus (FIRST, unconditional).
 *      → If match: pick specific NPTEL channel label or generic 📚 NPTEL bucket.
 *      → HARD STOP — no further checks.
 *   2. Attendance keyword detected → tag 🔴 URGENT, jump to course match.
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
 *
 * Bug-fixes applied
 * ─────────────────
 * DATA RACE IN SCORE ACCUMULATION — the original fuzzy-match blocks for
 * Hostel, Placement, and Course used a captured `var bestScore` that was
 * mutated as a side-effect inside the `maxByOrNull` lambda via `.also {}`.
 * `maxByOrNull` makes no guarantee about invocation order of the selector
 * lambda across items, so `bestScore` could end up holding the score of a
 * non-winning candidate, making the threshold check unreliable and
 * potentially routing to the wrong channel.
 *
 * Fix: score every candidate purely (no captured mutable state inside the
 * lambda), collect results into a list of pairs, then derive the winner and
 * its score with two explicit, deterministic steps.
 *
 * The same pattern is applied to the Gmail courseMatchScore blocks
 * (PRIORITY 2, 4, 5) for consistency and safety.
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
        "moved to", "shifted to", "no class", "holiday",
        "venue", "come", "class", "go", "last", "final"
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
            finalTitle = "🔴 URGENT: $finalTitle"
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
        // BUG FIX: Score every candidate purely — no mutable side-effect var
        // inside the selector lambda.  Collect (channel, score) pairs first,
        // then find the best in two deterministic steps.
        val hostelScored = hostelChannels
            .filter { !it.whatsappGroupName.isNullOrBlank() }
            .map { ch -> ch to fuzzyScore(ch.whatsappGroupName!!.lowercase(), titleLower) }

        val bestHostelPair  = hostelScored.maxByOrNull { (_, score) -> score }
        val bestHostelScore = bestHostelPair?.second ?: 0f
        val bestHostelMatch = bestHostelPair?.first

        if (bestHostelMatch != null && bestHostelScore >= FUZZY_THRESHOLD) {
            save(db, finalTitle, n.displayBody, bestHostelMatch.label, "whatsapp")
            Log.d("NHQ-Router", "WA fuzzy → Hostel (score=$bestHostelScore): $finalTitle")
            return
        }

        // ── PASS 2: Fuzzy match — Placements ─────────────────────────────
        // BUG FIX: Same pure-scoring pattern — no captured mutable var.
        val placementScored = placementChannels
            .filter { !it.whatsappGroupName.isNullOrBlank() }
            .map { ch -> ch to fuzzyScore(ch.whatsappGroupName!!.lowercase(), titleLower) }

        val bestPlacementPair  = placementScored.maxByOrNull { (_, score) -> score }
        val bestPlacementScore = bestPlacementPair?.second ?: 0f
        val bestPlacementMatch = bestPlacementPair?.first

        if (bestPlacementMatch != null && bestPlacementScore >= FUZZY_THRESHOLD) {
            save(db, finalTitle, n.displayBody, bestPlacementMatch.label, "whatsapp")
            Log.d("NHQ-Router", "WA fuzzy → Placements (score=$bestPlacementScore): $finalTitle")
            return
        }

        // ── PASS 2: Fuzzy match — Academic courses ────────────────────────
        // BUG FIX: Same pure-scoring pattern — no captured mutable var.
        val courseScored = savedCourses
            .filter { !it.whatsappGroupName.isNullOrBlank() }
            .map { course -> course to fuzzyScore(course.whatsappGroupName!!.lowercase(), titleLower) }

        val bestCoursePair  = courseScored.maxByOrNull { (_, score) -> score }
        val bestCourseScore = bestCoursePair?.second ?: 0f
        val bestCourseMatch = bestCoursePair?.first

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

        val senderLower      = n.senderEmail.lowercase()
        val displayNameLower = n.senderDisplayName.lowercase()

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
            Log.d("NHQ-Router", "Gmail: attendance mail detected, applying 🔴 URGENT")

            // BUG FIX: Compute scores as a pure mapping, then pick best in two steps.
            val attendanceScored = savedCourses.map { course ->
                course to courseMatchScore(n.searchLower, course.courseName, course.courseSymbol, course.courseId)
            }
            val bestAttendancePair  = attendanceScored.maxByOrNull { (_, score) -> score }
            val bestAttendanceScore = bestAttendancePair?.second ?: 0
            val bestAttendanceCourse = bestAttendancePair?.first

            val destination = if (bestAttendanceCourse != null && bestAttendanceScore > 0) {
                bestAttendanceCourse.courseName
            } else {
                BUCKET_IMPORTANT_EMAILS
            }

            val urgentTitle = "🔴 URGENT: ${n.cleanTitle}"
            save(db, urgentTitle, n.displayBody, destination, "gmail")
            Log.d("NHQ-Router", "Gmail → Attendance URGENT → $destination")
            return
        }

        // ── PRIORITY 3: Placement sender check ───────────────────────────
        val allPlacementEmails = placementChannels
            .flatMap { it.emailAddresses.split(",").map { e -> e.trim().lowercase() } }
            .filter { it.isNotEmpty() }
            .toSet()

        val isPlacementBySenderEmail = senderLower.isNotEmpty() &&
                allPlacementEmails.any { placementEmail ->
                    senderLower.contains(placementEmail) || placementEmail.contains(senderLower)
                }

        val placementDomains = placementChannels
            .flatMap { it.emailAddresses.split(",") }
            .map { it.trim().lowercase() }
            .mapNotNull { it.substringAfter("@", "").ifEmpty { null } }
            .filter { it.isNotEmpty() }
            .toSet()

        val isPlacementByDomain = senderLower.isNotEmpty() &&
                placementDomains.any { domain -> senderLower.contains(domain) }

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
        val isClassroomOrigin = CLASSROOM_SENDER_PATTERNS.any { pattern ->
            senderLower.contains(pattern) || n.searchLower.contains(pattern)
        }

        if (isClassroomOrigin) {
            // BUG FIX: Pure scoring — no captured mutable var inside lambda.
            val classroomScored = savedCourses.map { course ->
                course to courseMatchScore(n.searchLower, course.courseName, course.courseSymbol, course.courseId)
            }
            val bestClassroomPair   = classroomScored.maxByOrNull { (_, score) -> score }
            val bestClassroomScore  = bestClassroomPair?.second ?: 0
            val bestClassroomCourse = bestClassroomPair?.first

            val destination = if (bestClassroomCourse != null && bestClassroomScore > 0) {
                bestClassroomCourse.courseName
            } else {
                BUCKET_IMPORTANT_EMAILS
            }

            val finalTitle = buildDueTitle(n.cleanTitle, n.fullCorpus) ?: "📘 ${n.cleanTitle}"
            save(db, finalTitle, n.displayBody, destination, "classroom")
            Log.d("NHQ-Router", "Gmail → Classroom origin → $destination (score=$bestClassroomScore)")
            return
        }

        // ── PRIORITY 5: Academic course score match ───────────────────────
        // BUG FIX: Pure scoring — no captured mutable var inside lambda.
        val academicScored = savedCourses.map { course ->
            course to courseMatchScore(n.searchLower, course.courseName, course.courseSymbol, course.courseId)
        }
        val bestAcademicPair   = academicScored.maxByOrNull { (_, score) -> score }
        val bestAcademicScore  = bestAcademicPair?.second ?: 0
        val bestAcademicCourse = bestAcademicPair?.first

        if (bestAcademicCourse != null && bestAcademicScore > 0) {
            val finalTitle = buildDueTitle(n.cleanTitle, n.fullCorpus) ?: "📧 ${n.cleanTitle}"
            save(db, finalTitle, n.displayBody, bestAcademicCourse.courseName, "gmail")
            Log.d("NHQ-Router", "Gmail → Academic [${bestAcademicCourse.courseName}] score=$bestAcademicScore")
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
        Log.d("NHQ-Router", "Gmail dropped (passed all 6 checks, no match): ${n.cleanTitle}")
    }

    // ─────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Token overlap score for WhatsApp fuzzy matching.
     * Returns fraction of [savedName] tokens found in [notifTitle] tokens.
     * Pure function — no external state mutated.
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
     * Pure function — no external state mutated.
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
     * If the corpus contains a due-date phrase, prepend a 🟡 DUE tag.
     * Returns null if no due-date phrase found (caller uses a default prefix).
     */
    private fun buildDueTitle(rawTitle: String, fullCorpus: String): String? {
        val lower = fullCorpus.lowercase()
        val hasDue = lower.contains("closes on")  ||
                lower.contains("due date")   ||
                lower.contains("due on")     ||
                lower.contains("last date")  ||
                lower.contains("submit by")  ||
                lower.contains("due")        ||
                lower.contains("ends on")    ||
                lower.contains("deadline")

        if (!hasDue) return null

        val datePart = fullCorpus
            .split(Regex("(?i)(closes on\\s*:?|due date\\s*:?|due on\\s*:?|last date\\s*:?|submit by\\s*:?|deadline\\s*:?)"))
            .getOrNull(1)
            ?.take(25)
            ?.trim() ?: ""

        return if (datePart.isNotEmpty()) "🟡 DUE $datePart — $rawTitle"
        else "🟡 $rawTitle"
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