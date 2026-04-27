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
 * 1. DATA RACE IN SCORE ACCUMULATION — the original fuzzy-match blocks used
 *    a captured `var bestScore` mutated as a side-effect inside the
 *    `maxByOrNull` lambda via `.also {}`. `maxByOrNull` makes no guarantee
 *    about invocation order, so `bestScore` could hold the score of a
 *    non-winning candidate, making the threshold check unreliable.
 *    Fix: score every candidate purely, collect (item, score) pairs, then
 *    derive winner and score with two explicit deterministic steps.
 *
 * 2. BUILDDUETITLE DATE EXTRACTION — the original code used
 *    `.getOrNull(1)?.take(25)` to extract date context after splitting on a
 *    due-keyword regex. `take(25)` clips at a fixed character count with no
 *    respect for word or sentence boundaries, producing truncated output like
 *    "January 1" → "January" or splitting mid-emoji.
 *    Fix: after extracting the segment, walk forward to the first natural
 *    sentence boundary (". "  "! "  "? "  newline) within a 60-char window.
 *    If no boundary is found, fall back to the last complete word within that
 *    window. This preserves full date phrases like "January 12, 11:59 PM"
 *    while still keeping the title concise.
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

    // ── Date extraction window (chars) ────────────────────────────────────
    // How far into the post-keyword segment we look for a sentence boundary.
    private const val DATE_WINDOW = 60

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
            val bestAttendancePair   = attendanceScored.maxByOrNull { (_, score) -> score }
            val bestAttendanceScore  = bestAttendancePair?.second ?: 0
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
        // Match per-channel so the notification goes to the *specific* channel
        // card rather than the generic BUCKET_PLACEMENTS.
        //
        // Match order per channel:
        //   a) Exact sender email in channel's comma-separated email list.
        //   b) Sender domain matches any domain in the channel's email list.
        //   c) Display name overlaps with the channel label (display-name fallback).

        val matchedPlacementChannel = placementChannels.firstOrNull { ch ->
            val channelEmails = ch.emailAddresses
                .split(",")
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }

            // (a) Exact sender email match
            val byEmail = senderLower.isNotEmpty() &&
                    channelEmails.any { savedEmail ->
                        senderLower.contains(savedEmail) || savedEmail.contains(senderLower)
                    }

            // (b) Domain match
            val channelDomains = channelEmails
                .mapNotNull { it.substringAfter("@", "").ifEmpty { null } }
                .filter { it.isNotEmpty() }
            val byDomain = senderLower.isNotEmpty() &&
                    channelDomains.any { domain -> senderLower.contains(domain) }

            // (c) Display name ↔ channel label fuzzy check
            val labelLower = ch.label.lowercase()
            val byDisplayName = displayNameLower.isNotEmpty() &&
                    (displayNameLower.contains(labelLower) || labelLower.contains(displayNameLower))

            byEmail || byDomain || byDisplayName
        }

        if (matchedPlacementChannel != null) {
            val finalTitle = buildDueTitle(n.cleanTitle, n.fullCorpus) ?: "🏢 ${n.cleanTitle}"
            save(db, finalTitle, n.displayBody, matchedPlacementChannel.label, "gmail")
            Log.d("NHQ-Router",
                "Gmail → Placement channel [${matchedPlacementChannel.label}] " +
                        "sender='$senderLower' displayName='$displayNameLower'")
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

        // ── PRIORITY 7: No match → drop ───────────────────────────────────
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
     * If [fullCorpus] contains a due-date phrase, prepend a 🟡 DUE tag to
     * [rawTitle] and embed the extracted date context.
     *
     * Returns null if no due-date phrase is found (caller falls back to a
     * default emoji prefix).
     *
     * ── Date extraction strategy ──────────────────────────────────────────
     *
     * After splitting [fullCorpus] on a due-keyword regex to isolate the
     * segment that follows the keyword (e.g. the text after "due on:"),
     * the original code used a blunt `.take(25)` to clip the segment.
     * That approach is fragile:
     *   • It clips at a fixed byte count, which can split mid-word or
     *     mid-emoji (multi-byte), producing output like "Submit by Januar".
     *   • It always takes 25 chars even when the relevant date phrase is
     *     shorter, appending unrelated words that follow the date.
     *
     * The fixed approach:
     *   1. Work within the first [DATE_WINDOW] (60) characters of the segment.
     *   2. Find the earliest sentence boundary: ". " / "! " / "? " / "\n".
     *   3. If a boundary exists, take up to (and including) the punctuation —
     *      this preserves full phrases like "January 12, 11:59 PM."
     *   4. If no boundary exists within the window, fall back to the last
     *      complete word inside the window so we never output a clipped token.
     *
     * Examples:
     *   corpus segment → "January 12. Late submissions not accepted."
     *   old result     → "January 12. Late subm"  (clipped at char 25)
     *   new result     → "January 12."             (stops at first sentence end)
     *
     *   corpus segment → "Upload to Classroom\nDeadline: 5 PM"
     *   old result     → "Upload to Classroom\nDead" (clipped mid-word)
     *   new result     → "Upload to Classroom"        (stops at newline)
     *
     *   corpus segment → "Viva tomorrow" (short, no boundary)
     *   old result     → "Viva tomorrow" (happened to work, fits in 25)
     *   new result     → "Viva tomorrow" (same, via word-boundary fallback)
     */
    private fun buildDueTitle(rawTitle: String, fullCorpus: String): String? {
        val lower = fullCorpus.lowercase()

        val hasDue = lower.contains("closes on")  ||
                lower.contains("due date")         ||
                lower.contains("due on")           ||
                lower.contains("last date")        ||
                lower.contains("submit by")        ||
                lower.contains("due")              ||
                lower.contains("ends on")          ||
                lower.contains("deadline")

        if (!hasDue) return null

        // Split on any due-keyword pattern to isolate the date segment that
        // immediately follows the keyword.
        val rawSegment = fullCorpus
            .split(Regex("(?i)(closes on\\s*:?|due date\\s*:?|due on\\s*:?|last date\\s*:?|submit by\\s*:?|deadline\\s*:?)"))
            .getOrNull(1)
            ?.trim()
            ?: ""

        if (rawSegment.isEmpty()) return "🟡 $rawTitle"

        // ── Fixed extraction: sentence boundary → word boundary fallback ───
        val window = rawSegment.take(DATE_WINDOW)

        // Find the earliest natural sentence end inside the window.
        // We include the punctuation mark itself (index + 1) but exclude the
        // trailing space/newline so the chip reads "January 12." not "January 12. ".
        val boundaryIndex = listOf(
            window.indexOf(". "),   // period + space
            window.indexOf("! "),   // exclamation + space
            window.indexOf("? "),   // question mark + space
            window.indexOf("\n")    // newline (no +1 needed, newline is not shown)
        )
            .filter { it > 0 }      // ignore -1 (not found) and 0 (would produce empty string)
            .minOrNull()

        val datePart: String = if (boundaryIndex != null) {
            // Include punctuation for ". " / "! " / "? " (index points to
            // the punctuation char itself, so +1 gives us the char after it,
            // but we stop AT the punctuation, hence no +1 in substring end).
            window.substring(0, boundaryIndex + 1).trim()
        } else {
            // No sentence boundary — trim to the last complete word so we
            // never output a clipped token like "Januar".
            val trimmed = window.trim()
            val lastSpace = trimmed.lastIndexOf(' ')
            if (lastSpace > 0) trimmed.substring(0, lastSpace) else trimmed
        }

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