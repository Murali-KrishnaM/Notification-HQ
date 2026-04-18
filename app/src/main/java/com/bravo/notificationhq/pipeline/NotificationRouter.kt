package com.bravo.notificationhq.pipeline

import android.util.Log
import com.bravo.notificationhq.AppDatabase

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
 * ── ROUTING RULES (in strict priority order) ──────────────────────────────
 *
 * WHATSAPP:
 *   1. Tag urgent messages with 🚨 URGENT prefix.
 *   2. Exact substring match: WA group name ↔ Placement channel → Placements bucket.
 *   3. Exact substring match: WA group name ↔ Academic course  → that course.
 *   4. Fuzzy match (≥ 60% token overlap): same order as above.
 *   5. No match → DROP silently.
 *
 * GMAIL:
 *   1. NPTEL check FIRST — scan full corpus for NPTEL/SWAYAM keywords.
 *      → If hit: also check saved NPTEL channel emails to pick the right label.
 *      → Route to that NPTEL channel label (or generic 📚 NPTEL bucket).
 *      → STOP. No further checks.
 *   2. Placement sender check — compare senderEmail against all saved
 *      placement channel email addresses.
 *      → Route to 🏢 Placements bucket.
 *      → STOP.
 *   3. Academic course match — score corpus against courseName, courseSymbol,
 *      courseId for each saved course (highest score wins).
 *      → Route to that course if score > 0.
 *   4. Generic academic keyword fallback → 📧 Important Emails bucket.
 *   5. None matched → DROP.
 *
 * GOOGLE CLASSROOM:
 *   1. Match classroom name against saved courses → route to that course.
 *   2. Fallback → 📘 General Classroom bucket.
 *
 * ── DOES NOT touch UI. No Activity references. DB writes only. ────────────
 */
object NotificationRouter {

    // ── System bucket names (constants used by UI adapters too) ───────────
    const val BUCKET_IMPORTANT_EMAILS  = "📧 Important Emails"
    const val BUCKET_GENERAL_CLASSROOM = "📘 General Classroom"
    const val BUCKET_NPTEL             = "📚 NPTEL"
    const val BUCKET_PLACEMENTS        = "🏢 Placements"

    // ── NPTEL detection keywords (scanned in corpus, case-insensitive) ─────
    private val NPTEL_KEYWORDS = listOf(
        "nptel", "swayam", "nptel.iitm.ac.in", "onlinecourses.nptel.ac.in",
        "nptel online", "nptel course", "week deadline", "assignment deadline",
        "iit madras", "iitm online", "nptel assignment"
    )

    // ── Generic academic keywords (Gmail fallback) ────────────────────────
    private val GMAIL_ACADEMIC_KEYWORDS = listOf(
        "quiz", "deadline", "assignment", "notes", "resource",
        "submission", "exam", "test", "project", "review",
        "lab", "viva", "internal", "closes on", "due date",
        "attendance", "class cancelled", "rescheduled", "postponed",
        "marks", "grade", "result", "timetable", "schedule"
    )

    // ── Urgency keywords for WhatsApp messages ─────────────────────────────
    private val WA_URGENT_KEYWORDS = listOf(
        "room change", "room no", "cancel", "cancelled",
        "rescheduled", "postponed", "urgent", "important",
        "moved to", "shifted to", "no class", "holiday"
    )

    // ── Fuzzy match threshold: 60% of saved-name tokens must match ─────────
    private const val FUZZY_THRESHOLD  = 0.6f
    private const val MIN_TOKEN_LENGTH = 2

    // ─────────────────────────────────────────────────────────────────────
    // PUBLIC ENTRY POINT
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Route [denoised] to the correct destination and persist to [db].
     * This function is a suspend function and must be called from a coroutine
     * running on [kotlinx.coroutines.Dispatchers.IO].
     */
    suspend fun route(db: AppDatabase, denoised: DenoisedNotification) {
        when {
            denoised.packageName == "com.whatsapp" ||
            denoised.packageName == "com.whatsapp.w4b" ->
                routeWhatsApp(db, denoised)

            denoised.packageName == "com.google.android.apps.classroom" ->
                routeClassroom(db, denoised)

            denoised.packageName == "com.google.android.gm" ->
                routeGmail(db, denoised)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // WHATSAPP ROUTER
    // ─────────────────────────────────────────────────────────────────────

    private suspend fun routeWhatsApp(db: AppDatabase, n: DenoisedNotification) {
        val savedCourses      = db.courseDao().getAllCourses()
        val placementChannels = db.placementChannelDao().getAllChannels()

        // Apply URGENT tag to the title if message matches urgency keywords
        var finalTitle = n.cleanTitle
        if (WA_URGENT_KEYWORDS.any { n.searchLower.contains(it) }) {
            finalTitle = "🚨 URGENT: $finalTitle"
        }

        val titleLower = finalTitle.lowercase()

        // ── PASS 1: Exact match — Placements ──────────────────────────────
        for (channel in placementChannels) {
            val wGroup = channel.whatsappGroupName?.trim()?.lowercase() ?: continue
            if (wGroup.isNotEmpty() && titleLower.contains(wGroup)) {
                save(db, finalTitle, n.displayBody, BUCKET_PLACEMENTS, "whatsapp")
                Log.d("NHQ-Router", "WA exact → Placements: $finalTitle")
                return
            }
        }

        // ── PASS 1: Exact match — Academic courses ────────────────────────
        for (course in savedCourses) {
            val wGroup = course.whatsappGroupName?.trim()?.lowercase() ?: continue
            if (wGroup.isNotEmpty() && titleLower.contains(wGroup)) {
                save(db, finalTitle, n.displayBody, course.courseName, "whatsapp")
                Log.d("NHQ-Router", "WA exact → ${course.courseName}: $finalTitle")
                return
            }
        }

        // ── PASS 2: Fuzzy match — Placements ──────────────────────────────
        var bestPlacementScore = 0f
        var bestPlacementMatch = placementChannels
            .filter { !it.whatsappGroupName.isNullOrBlank() }
            .maxByOrNull { ch ->
                fuzzyScore(ch.whatsappGroupName!!.lowercase(), titleLower)
                    .also { score -> if (score > bestPlacementScore) bestPlacementScore = score }
            }

        if (bestPlacementMatch != null && bestPlacementScore >= FUZZY_THRESHOLD) {
            save(db, finalTitle, n.displayBody, BUCKET_PLACEMENTS, "whatsapp")
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

        // ── No match — drop silently ───────────────────────────────────────
        Log.d("NHQ-Router", "WA dropped (best score=$bestCourseScore): $finalTitle")
    }

    // ─────────────────────────────────────────────────────────────────────
    // GMAIL ROUTER  — strict priority order as documented above
    // ─────────────────────────────────────────────────────────────────────

    private suspend fun routeGmail(db: AppDatabase, n: DenoisedNotification) {
        val savedCourses      = db.courseDao().getAllCourses()
        val placementChannels = db.placementChannelDao().getAllChannels()
        val nptelChannels     = db.nptelChannelDao().getAllChannels()
        val senderLower       = n.senderEmail.lowercase()

        Log.d("NHQ-Router", "Gmail | sender='$senderLower' | title='${n.cleanTitle}'")

        // ── PRIORITY 1: NPTEL — keyword scan FIRST, no other conditions ───
        //    If ANY nptel keyword appears in the corpus, this is an NPTEL mail.
        val isNptelByKeyword = NPTEL_KEYWORDS.any { n.searchLower.contains(it) }

        // Also check if the sender matches a saved NPTEL channel email
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

        val isNptelBySender = matchedNptelChannel != null

        if (isNptelByKeyword || isNptelBySender) {
            // Choose the most specific label: matched channel > generic bucket
            val nptelDestination = matchedNptelChannel?.label ?: BUCKET_NPTEL
            val finalTitle = buildDueTitle(n.cleanTitle, n.fullCorpus) ?: "📚 ${n.cleanTitle}"
            save(db, finalTitle, n.displayBody, nptelDestination, "gmail")
            Log.d("NHQ-Router", "Gmail → NPTEL [$nptelDestination] keyword=$isNptelByKeyword sender=$isNptelBySender")
            return   // ← HARD STOP: no further checks
        }

        // ── PRIORITY 2: Placement sender check ───────────────────────────
        if (senderLower.isNotEmpty()) {
            val allPlacementEmails = placementChannels
                .flatMap { it.emailAddresses.split(",").map { e -> e.trim().lowercase() } }
                .filter { it.isNotEmpty() }
                .toSet()

            val isPlacementSender = allPlacementEmails.any { placementEmail ->
                senderLower.contains(placementEmail) || placementEmail.contains(senderLower)
            }

            // Also check domain-level match (e.g., anything @rajalakshmi.edu.in for placements)
            val placementDomains = placementChannels
                .flatMap { it.emailAddresses.split(",") }
                .map { it.trim().lowercase() }
                .mapNotNull { it.substringAfter("@", "").ifEmpty { null } }
                .toSet()

            val isPlacementDomain = placementDomains.any { domain ->
                domain.isNotEmpty() && senderLower.contains(domain)
            }

            if (isPlacementSender || isPlacementDomain) {
                val finalTitle = buildDueTitle(n.cleanTitle, n.fullCorpus) ?: "🏢 ${n.cleanTitle}"
                save(db, finalTitle, n.displayBody, BUCKET_PLACEMENTS, "gmail")
                Log.d("NHQ-Router", "Gmail → Placements (sender match)")
                return
            }
        }

        // ── PRIORITY 3: Academic course match (score-based) ───────────────
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

        // ── PRIORITY 4: Generic academic keyword fallback ─────────────────
        val isGenericAcademic = GMAIL_ACADEMIC_KEYWORDS.any { n.searchLower.contains(it) }
        if (isGenericAcademic) {
            val finalTitle = buildDueTitle(n.cleanTitle, n.fullCorpus) ?: "📧 ${n.cleanTitle}"
            save(db, finalTitle, n.displayBody, BUCKET_IMPORTANT_EMAILS, "gmail")
            Log.d("NHQ-Router", "Gmail → Important Emails (generic academic)")
            return
        }

        // ── PRIORITY 5: Drop ──────────────────────────────────────────────
        Log.d("NHQ-Router", "Gmail dropped (no match): ${n.cleanTitle}")
    }

    // ─────────────────────────────────────────────────────────────────────
    // CLASSROOM ROUTER
    // ─────────────────────────────────────────────────────────────────────

    private suspend fun routeClassroom(db: AppDatabase, n: DenoisedNotification) {
        val savedCourses = db.courseDao().getAllCourses()
        val finalTitle   = "📘 ${n.cleanTitle}"

        for (course in savedCourses) {
            val cRoom = course.classroomName?.trim()?.lowercase() ?: continue
            if (cRoom.isNotEmpty() && n.searchLower.contains(cRoom)) {
                save(db, finalTitle, n.displayBody, course.courseName, "classroom")
                Log.d("NHQ-Router", "Classroom → ${course.courseName}")
                return
            }
        }

        // Fallback bucket
        save(db, finalTitle, n.displayBody, BUCKET_GENERAL_CLASSROOM, "classroom")
        Log.d("NHQ-Router", "Classroom → General Classroom (no course match)")
    }

    // ─────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Token overlap score for WhatsApp fuzzy matching.
     *
     * Splits [savedName] and [notifTitle] into tokens (split on non-alphanumeric).
     * Returns the fraction of [savedName] tokens that appear in [notifTitle].
     *
     * Example:
     *   savedName="nlpa batch a"  notifTitle="nlpa - batch a (5)"  → 1.0  ✅
     *   savedName="nlpa batch a"  notifTitle="nlpa batch"           → 0.67 ✅
     *   savedName="cloud computing" notifTitle="coding club"        → 0.0  ❌
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
     * Score a Gmail against a saved course.
     *   +3 if courseId    found in corpus (most specific)
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
     * If the corpus contains a due-date phrase, prepend a ⏰ DUE tag to the title.
     * Returns null if no due-date phrase is found.
     */
    private fun buildDueTitle(rawTitle: String, fullCorpus: String): String? {
        val lower = fullCorpus.lowercase()
        val hasDue = lower.contains("closes on")  ||
                     lower.contains("due date")   ||
                     lower.contains("due on")     ||
                     lower.contains("last date")

        if (!hasDue) return null

        val datePart = fullCorpus
            .split(Regex("(?i)(closes on\\s*:?|due date\\s*:?|due on\\s*:?|last date\\s*:?)"))
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
        val model = com.bravo.notificationhq.NotificationModel(
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
