package com.bravo.notificationhq.pipeline

import android.util.Log
import com.bravo.notificationhq.AppDatabase
import com.bravo.notificationhq.NotificationModel
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * ══════════════════════════════════════════════════════════════════════════
 * MODULE 3 — NOTIFICATION ROUTER
 * ══════════════════════════════════════════════════════════════════════════
 *
 * Responsibility:
 * - Decide exactly which bucket a [DenoisedNotification] belongs to.
 * - Persist the result via [AppDatabase].
 *
 * ── ROUTING RULES ─────────────────────────────────────────────────────────
 *
 * WHATSAPP:
 * 1. Tag message with 🔴 URGENT if urgency keywords detected.
 * 2. Exact substring match → Placements / Courses / Hostel.
 * 3. Fuzzy match (≥ 60% token overlap) → Placements / Courses / Hostel.
 * 4. No match → DROP silently.
 *
 * GMAIL — Strict academic filtering (Personal emails are DROPPED):
 * 0. GATEKEEPER: Drop if it lacks academic/urgent/NPTEL keywords AND
 * isn't from Classroom/Placements. (Throws away personal emails).
 * 1. NPTEL keyword/sender scan → 📚 NPTEL.
 * 2. Tag 🔴 URGENT if attendance or urgency keywords are found.
 * 3. Placement sender check → 🏢 Placements.
 * 4. Google Classroom origin → Best Course Match or 📘 Important Emails.
 * 5. Academic course score match → Best Course Match.
 * 6. Generic academic fallback → 📧 Important Emails.
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

    // ── Attendance keywords ───────────────────────────────────────────────
    private val ATTENDANCE_KEYWORDS = listOf(
        "attendance", "absent", "absentee", "proxy", "attendance report",
        "attendance shortage", "attendance alert", "marked absent",
        "below 75", "detain", "detained", "shortage of attendance"
    )

    // ── Urgency keywords (Applies to both WA and Gmail) ───────────────────
    private val URGENT_KEYWORDS = listOf(
        "room change", "room no", "cancel", "cancelled",
        "rescheduled", "postponed", "urgent", "important",
        "moved to", "shifted to", "no class", "holiday",
        "venue", "come", "class", "go", "last", "final"
    )

    // ── Generic academic keywords (Gmail fallback & gatekeeper) ───────────
    private val ACADEMIC_KEYWORDS = listOf(
        // Original
        "quiz", "deadline", "assignment", "notes", "resource",
        "submission", "exam", "test", "project", "review",
        "lab", "viva", "internal", "closes on", "due date",
        "class cancelled", "marks", "grade", "result",
        "timetable", "schedule", "google classroom", "classroom.google",
        "syllabus", "lecture", "seminar", "workshop", "hall ticket",
        "admit card", "faculty", "hod", "principal", "fee", "tuition",
        "registration", "enrollment", "cgpa", "sgpa", "gpa", "transcript",
        "scholarship", "internship", "hackathon",
        // REINFORCED PLACEMENT KEYWORDS
        "interview", "technical round", "hr round", "group discussion",
        "aptitude", "assessment", "placement", "drive", "shortlist"
    )

    // ── Google Classroom email origins ────────────────────────────────────
    private val CLASSROOM_SENDER_PATTERNS = listOf(
        "classroom.google.com",
        "classroom-noreply@google.com",
        "noreply-diffs@google.com",
        "googleclassroom"
    )

    // ── Fuzzy match threshold ─────────────────────────────────────────────
    private const val FUZZY_THRESHOLD  = 0.6f
    private const val MIN_TOKEN_LENGTH = 2

    // ── Date extraction window (chars) ────────────────────────────────────
    private const val DATE_WINDOW = 60

    // ─────────────────────────────────────────────────────────────────────
    // PUBLIC ENTRY POINT
    // ─────────────────────────────────────────────────────────────────────

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

        var finalTitle = n.cleanTitle
        if (URGENT_KEYWORDS.any { n.searchLower.contains(it) }) {
            finalTitle = "🔴 URGENT: $finalTitle"
        }

        val titleLower = finalTitle.lowercase()

        // ── PASS 1: Exact substring match ─────────────────────────────────
        for (channel in hostelChannels) {
            val wGroup = channel.whatsappGroupName?.trim()?.lowercase() ?: continue
            if (wGroup.isNotEmpty() && titleLower.contains(wGroup)) {
                save(db, finalTitle, n.displayBody, channel.label, "whatsapp")
                return
            }
        }
        for (channel in placementChannels) {
            val wGroup = channel.whatsappGroupName?.trim()?.lowercase() ?: continue
            if (wGroup.isNotEmpty() && titleLower.contains(wGroup)) {
                save(db, finalTitle, n.displayBody, channel.label, "whatsapp")
                return
            }
        }
        for (course in savedCourses) {
            val wGroup = course.whatsappGroupName?.trim()?.lowercase() ?: continue
            if (wGroup.isNotEmpty() && titleLower.contains(wGroup)) {
                save(db, finalTitle, n.displayBody, course.courseName, "whatsapp")
                return
            }
        }

        // ── PASS 2: Fuzzy match ───────────────────────────────────────────
        val hostelScored = hostelChannels
            .filter { !it.whatsappGroupName.isNullOrBlank() }
            .map { ch -> ch to fuzzyScore(ch.whatsappGroupName!!.lowercase(), titleLower) }
        val bestHostelPair = hostelScored.maxByOrNull { (_, score) -> score }
        if (bestHostelPair != null && bestHostelPair.second >= FUZZY_THRESHOLD) {
            save(db, finalTitle, n.displayBody, bestHostelPair.first.label, "whatsapp")
            return
        }

        val placementScored = placementChannels
            .filter { !it.whatsappGroupName.isNullOrBlank() }
            .map { ch -> ch to fuzzyScore(ch.whatsappGroupName!!.lowercase(), titleLower) }
        val bestPlacementPair = placementScored.maxByOrNull { (_, score) -> score }
        if (bestPlacementPair != null && bestPlacementPair.second >= FUZZY_THRESHOLD) {
            save(db, finalTitle, n.displayBody, bestPlacementPair.first.label, "whatsapp")
            return
        }

        val courseScored = savedCourses
            .filter { !it.whatsappGroupName.isNullOrBlank() }
            .map { course -> course to fuzzyScore(course.whatsappGroupName!!.lowercase(), titleLower) }
        val bestCoursePair = courseScored.maxByOrNull { (_, score) -> score }
        if (bestCoursePair != null && bestCoursePair.second >= FUZZY_THRESHOLD) {
            save(db, finalTitle, n.displayBody, bestCoursePair.first.courseName, "whatsapp")
            return
        }

        Log.d("NHQ-Router", "WA dropped (no match): $finalTitle")
    }

    // ─────────────────────────────────────────────────────────────────────
    // GMAIL ROUTER
    // ─────────────────────────────────────────────────────────────────────

    private suspend fun routeGmail(db: AppDatabase, n: DenoisedNotification) {
        val savedCourses      = db.courseDao().getAllCourses()
        val placementChannels = db.placementChannelDao().getAllChannels()
        val nptelChannels     = db.nptelChannelDao().getAllChannels()

        val senderLower      = n.senderEmail.lowercase()
        val displayNameLower = n.senderDisplayName.lowercase()

        // ── Check Triggers ────────────────────────────────────────────────
        val isNptelText       = NPTEL_KEYWORDS.any { n.searchLower.contains(it) }
        val isAttendance      = ATTENDANCE_KEYWORDS.any { n.searchLower.contains(it) }
        val isUrgentText      = URGENT_KEYWORDS.any { n.searchLower.contains(it) }
        val isAcademicText    = ACADEMIC_KEYWORDS.any { n.searchLower.contains(it) }
        val isClassroomOrigin = CLASSROOM_SENDER_PATTERNS.any { senderLower.contains(it) || n.searchLower.contains(it) }
        val isUrgentOverall   = isAttendance || isUrgentText

        // Sender match lookups
        val matchedNptelChannel = nptelChannels.firstOrNull { ch ->
            ch.emailAddresses.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                .any { savedEmail -> senderLower.isNotEmpty() && (senderLower.contains(savedEmail) || savedEmail.contains(senderLower)) }
        }

        val matchedPlacementChannel = placementChannels.firstOrNull { ch ->
            val channelEmails = ch.emailAddresses.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            val byEmail = senderLower.isNotEmpty() && channelEmails.any { senderLower.contains(it) || it.contains(senderLower) }
            val channelDomains = channelEmails.mapNotNull { it.substringAfter("@", "").ifEmpty { null } }
            val byDomain = senderLower.isNotEmpty() && channelDomains.any { senderLower.contains(it) }
            val labelLower = ch.label.lowercase()
            val byDisplayName = displayNameLower.isNotEmpty() && (displayNameLower.contains(labelLower) || labelLower.contains(displayNameLower))
            byEmail || byDomain || byDisplayName
        }

        // ── GATEKEEPER: Drop personal/irrelevant emails ───────────────────
        val isRelevant = isNptelText || matchedNptelChannel != null ||
                isUrgentOverall || isAcademicText || isClassroomOrigin ||
                matchedPlacementChannel != null

        if (!isRelevant) {
            Log.d("NHQ-Router", "Gmail dropped (personal/non-academic): ${n.cleanTitle}")
            return
        }

        // ── Helper: Format Title (Due & Urgent tags) ──────────────────────
        fun buildFinalTitle(baseEmoji: String): String {
            var t = buildDueTitle(n.cleanTitle, n.fullCorpus) ?: "$baseEmoji ${n.cleanTitle}"
            if (isUrgentOverall && !t.contains("🔴 URGENT")) {
                t = "🔴 URGENT: $t"
            }
            return t
        }

        // ── PRIORITY 1: NPTEL ─────────────────────────────────────────────
        if (isNptelText || matchedNptelChannel != null) {
            val dest = matchedNptelChannel?.label ?: BUCKET_NPTEL
            save(db, buildFinalTitle("📚"), n.displayBody, dest, "gmail")
            return
        }

        // ── PRIORITY 2: Placements ────────────────────────────────────────
        if (matchedPlacementChannel != null) {
            save(db, buildFinalTitle("🏢"), n.displayBody, matchedPlacementChannel.label, "gmail")
            return
        }

        // Prepare course score for remaining checks
        val academicScored = savedCourses.map { course ->
            course to courseMatchScore(n.searchLower, course.courseName, course.courseSymbol, course.courseId)
        }
        val bestCoursePair = academicScored.maxByOrNull { (_, score) -> score }
        val bestCourseScore = bestCoursePair?.second ?: 0
        val bestCourse = bestCoursePair?.first

        // ── PRIORITY 3: Google Classroom ──────────────────────────────────
        if (isClassroomOrigin) {
            val dest = if (bestCourse != null && bestCourseScore > 0) bestCourse.courseName else BUCKET_IMPORTANT_EMAILS
            save(db, buildFinalTitle("📘"), n.displayBody, dest, "classroom")
            return
        }

        // ── PRIORITY 4: General Academic/Urgent → Specific Course ─────────
        if (bestCourse != null && bestCourseScore > 0) {
            save(db, buildFinalTitle("📧"), n.displayBody, bestCourse.courseName, "gmail")
            return
        }

        // ── PRIORITY 5: Generic Fallback (Passed Gatekeeper, no course) ───
        save(db, buildFinalTitle("📧"), n.displayBody, BUCKET_IMPORTANT_EMAILS, "gmail")
    }

    // ─────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────

    private fun fuzzyScore(savedName: String, notifTitle: String): Float {
        val savedTokens = savedName.split(Regex("[^a-z0-9]+")).filter { it.length >= MIN_TOKEN_LENGTH }
        if (savedTokens.isEmpty()) return 0f
        val titleTokens = notifTitle.split(Regex("[^a-z0-9]+")).filter { it.length >= MIN_TOKEN_LENGTH }.toSet()
        val matched = savedTokens.count { saved -> titleTokens.any { title -> title == saved || title.startsWith(saved) } }
        return matched.toFloat() / savedTokens.size.toFloat()
    }

    private fun courseMatchScore(searchLower: String, courseName: String, courseSymbol: String, courseId: String): Int {
        var score = 0
        if (courseId.trim().lowercase().let { it.isNotEmpty() && searchLower.contains(it) }) score += 3
        if (courseSymbol.trim().lowercase().let { it.isNotEmpty() && searchLower.contains(it) }) score += 2
        if (courseName.trim().lowercase().let { it.isNotEmpty() && searchLower.contains(it) }) score += 1
        return score
    }

    /**
     * Extracts date information or resolves "tomorrow"/"today" dynamically.
     */
    private fun buildDueTitle(rawTitle: String, fullCorpus: String): String? {
        val lower = fullCorpus.lowercase()

        // 1. SMART CALENDAR RESOLVER FOR RELATIVE DATES ("Tomorrow" / "Today")
        val isTomorrow = lower.contains("tomorrow") || lower.contains("tmrw")
        val isToday = lower.contains("today")

        // Triggers that, if paired with "tomorrow", immediately constitute a DUE item
        val eventKeywords = listOf(
            "interview", "technical round", "hr round", "group discussion",
            "aptitude", "exam", "test", "assessment", "deadline", "due",
            "submission", "scheduled"
        )

        val hasRelativeDateEvent = eventKeywords.any { lower.contains(it) } && (isTomorrow || isToday)

        if (hasRelativeDateEvent) {
            val cal = Calendar.getInstance()
            if (isTomorrow) cal.add(Calendar.DAY_OF_YEAR, 1)

            val sdf = SimpleDateFormat("MMM dd", Locale.getDefault())
            val dayWord = if (isTomorrow) "Tomorrow" else "Today"

            // Output: 🟡 DUE Tomorrow (Apr 28) — Placements
            return "🟡 DUE $dayWord (${sdf.format(cal.time)}) — $rawTitle"
        }

        // 2. STANDARD CHECK FOR EXPLICIT DATES
        val duePatterns = listOf(
            "closes on", "due date", "due on", "last date", "submit by",
            "deadline", "ends on", "scheduled on", "scheduled for",
            "interview on", "assessment on", "held on", "exam on", "test on"
        )

        val hasDue = duePatterns.any { lower.contains(it) }
        if (!hasDue) return null

        // Build a regex to split exactly at the found keyword
        val regexStr = "(?i)(" + duePatterns.joinToString("|") { "$it\\s*:?" } + ")"
        val rawSegment = fullCorpus.split(Regex(regexStr)).getOrNull(1)?.trim() ?: ""

        if (rawSegment.isEmpty()) return "🟡 $rawTitle"

        val window = rawSegment.take(DATE_WINDOW)
        val boundaryIndex = listOf(
            window.indexOf(". "), window.indexOf("! "), window.indexOf("? "), window.indexOf("\n")
        ).filter { it > 0 }.minOrNull()

        val datePart: String = if (boundaryIndex != null) {
            window.substring(0, boundaryIndex + 1).trim()
        } else {
            val trimmed = window.trim()
            val lastSpace = trimmed.lastIndexOf(' ')
            if (lastSpace > 0) trimmed.substring(0, lastSpace) else trimmed
        }

        return if (datePart.isNotEmpty()) "🟡 DUE $datePart — $rawTitle" else "🟡 $rawTitle"
    }

    private suspend fun save(db: AppDatabase, title: String, text: String, source: String, packageSource: String) {
        val model = NotificationModel(
            title = title, text = text, source = source, packageSource = packageSource, timestamp = System.currentTimeMillis()
        )
        db.notificationDao().insertNotification(model)
        Log.d("NHQ-Router", "Saved [$packageSource → $source]: $title")
    }
}