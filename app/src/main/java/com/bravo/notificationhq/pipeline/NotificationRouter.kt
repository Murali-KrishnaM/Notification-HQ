package com.bravo.notificationhq.pipeline

import android.util.Log
import com.bravo.notificationhq.AppDatabase
import com.bravo.notificationhq.DashboardWidgetProvider
import com.bravo.notificationhq.NotificationModel
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Locale

object NotificationRouter {

    const val BUCKET_IMPORTANT_EMAILS  = "📧 Important Emails"
    const val BUCKET_NPTEL             = "📚 NPTEL"
    const val BUCKET_PLACEMENTS        = "🏢 Placements"
    const val BUCKET_HOSTEL            = "🏠 Hostel"

    private val NPTEL_KEYWORDS = listOf("nptel", "swayam", "nptel.iitm.ac.in", "onlinecourses.nptel.ac.in", "nptel online", "nptel course", "week deadline", "assignment deadline", "iit madras", "iitm online", "nptel assignment")
    private val ATTENDANCE_KEYWORDS = listOf("attendance", "absent", "absentee", "proxy", "attendance report", "attendance shortage", "attendance alert", "marked absent", "below 75", "detain", "detained", "shortage of attendance")
    private val URGENT_KEYWORDS = listOf("room change", "room no", "cancel", "cancelled", "rescheduled", "postponed", "urgent", "important", "moved to", "shifted to", "no class", "holiday", "venue", "come", "class", "go", "last", "final")
    private val ACADEMIC_KEYWORDS = listOf("quiz", "deadline", "assignment", "notes", "resource", "submission", "exam", "test", "project", "review", "lab", "viva", "internal", "closes on", "due date", "class cancelled", "marks", "grade", "result", "timetable", "schedule", "google classroom", "classroom.google", "syllabus", "lecture", "seminar", "workshop", "hall ticket", "admit card", "faculty", "hod", "principal", "fee", "tuition", "registration", "enrollment", "cgpa", "sgpa", "gpa", "transcript", "scholarship", "internship", "hackathon", "interview", "technical round", "hr round", "group discussion", "aptitude", "assessment", "placement", "drive", "shortlist")
    private val CLASSROOM_SENDER_PATTERNS = listOf("classroom.google.com", "classroom-noreply@google.com", "noreply-diffs@google.com", "googleclassroom")
    private const val FUZZY_THRESHOLD  = 0.6f
    private const val MIN_TOKEN_LENGTH = 2
    private const val DATE_WINDOW = 60

    // 🟢 ADDED CONTEXT REQUIREMENT HERE
    suspend fun route(context: android.content.Context, db: AppDatabase, denoised: DenoisedNotification) {
        when {
            denoised.packageName == "com.whatsapp" || denoised.packageName == "com.whatsapp.w4b" -> routeWhatsApp(context, db, denoised)
            denoised.packageName == "com.google.android.gm" -> routeGmail(context, db, denoised)
            else -> Log.d("NHQ-Router", "Unknown package dropped: ${denoised.packageName}")
        }
    }

    private suspend fun routeWhatsApp(context: android.content.Context, db: AppDatabase, n: DenoisedNotification) {
        val savedCourses      = db.courseDao().getAllCourses()
        val placementChannels = db.placementChannelDao().getAllChannels()
        val hostelChannels    = db.hostelChannelDao().getAllChannels()

        var finalTitle = n.cleanTitle
        if (URGENT_KEYWORDS.any { n.searchLower.contains(it) }) finalTitle = "🔴 URGENT: $finalTitle"
        val titleLower = finalTitle.lowercase()

        for (channel in hostelChannels) {
            val wGroup = channel.whatsappGroupName?.trim()?.lowercase() ?: continue
            if (wGroup.isNotEmpty() && titleLower.contains(wGroup)) return saveAndTag(context, db, finalTitle, n.displayBody, channel.label, "whatsapp", n.fullCorpus)
        }
        for (channel in placementChannels) {
            val wGroup = channel.whatsappGroupName?.trim()?.lowercase() ?: continue
            if (wGroup.isNotEmpty() && titleLower.contains(wGroup)) return saveAndTag(context, db, finalTitle, n.displayBody, channel.label, "whatsapp", n.fullCorpus)
        }
        for (course in savedCourses) {
            val wGroup = course.whatsappGroupName?.trim()?.lowercase() ?: continue
            if (wGroup.isNotEmpty() && titleLower.contains(wGroup)) return saveAndTag(context, db, finalTitle, n.displayBody, course.courseName, "whatsapp", n.fullCorpus)
        }

        val hostelScored = hostelChannels.filter { !it.whatsappGroupName.isNullOrBlank() }.map { ch -> ch to fuzzyScore(ch.whatsappGroupName!!.lowercase(), titleLower) }
        val bestHostelPair = hostelScored.maxByOrNull { (_, score) -> score }
        if (bestHostelPair != null && bestHostelPair.second >= FUZZY_THRESHOLD) return saveAndTag(context, db, finalTitle, n.displayBody, bestHostelPair.first.label, "whatsapp", n.fullCorpus)

        val placementScored = placementChannels.filter { !it.whatsappGroupName.isNullOrBlank() }.map { ch -> ch to fuzzyScore(ch.whatsappGroupName!!.lowercase(), titleLower) }
        val bestPlacementPair = placementScored.maxByOrNull { (_, score) -> score }
        if (bestPlacementPair != null && bestPlacementPair.second >= FUZZY_THRESHOLD) return saveAndTag(context, db, finalTitle, n.displayBody, bestPlacementPair.first.label, "whatsapp", n.fullCorpus)

        val courseScored = savedCourses.filter { !it.whatsappGroupName.isNullOrBlank() }.map { c -> c to fuzzyScore(c.whatsappGroupName!!.lowercase(), titleLower) }
        val bestCoursePair = courseScored.maxByOrNull { (_, score) -> score }
        if (bestCoursePair != null && bestCoursePair.second >= FUZZY_THRESHOLD) return saveAndTag(context, db, finalTitle, n.displayBody, bestCoursePair.first.courseName, "whatsapp", n.fullCorpus)

        Log.d("NHQ-Router", "WhatsApp dropped (no group match): ${n.cleanTitle}")
    }

    private suspend fun routeGmail(context: android.content.Context, db: AppDatabase, n: DenoisedNotification) {
        val savedCourses      = db.courseDao().getAllCourses()
        val placementChannels = db.placementChannelDao().getAllChannels()
        val nptelChannels     = db.nptelChannelDao().getAllChannels()

        val senderLower      = n.senderEmail?.lowercase() ?: ""
        val displayNameLower = n.senderDisplayName?.lowercase() ?: ""

        val isNptelText       = NPTEL_KEYWORDS.any { n.searchLower.contains(it) }
        val isAttendance      = ATTENDANCE_KEYWORDS.any { n.searchLower.contains(it) }
        val isUrgentText      = URGENT_KEYWORDS.any { n.searchLower.contains(it) }
        val isAcademicText    = ACADEMIC_KEYWORDS.any { n.searchLower.contains(it) }
        val isClassroomOrigin = CLASSROOM_SENDER_PATTERNS.any { senderLower.contains(it) || n.searchLower.contains(it) }
        val isUrgentOverall   = isAttendance || isUrgentText

        val matchedNptelChannel = nptelChannels.firstOrNull { ch -> ch.emailAddresses.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.any { savedEmail -> senderLower.isNotEmpty() && (senderLower.contains(savedEmail) || savedEmail.contains(senderLower)) } }
        val matchedPlacementChannel = placementChannels.firstOrNull { ch ->
            val channelEmails = ch.emailAddresses.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            val byEmail = senderLower.isNotEmpty() && channelEmails.any { senderLower.contains(it) || it.contains(senderLower) }
            val channelDomains = channelEmails.mapNotNull { it.substringAfter("@", "").ifEmpty { null } }
            val byDomain = senderLower.isNotEmpty() && channelDomains.any { senderLower.contains(it) }
            val labelLower = ch.label.lowercase()
            val byDisplayName = displayNameLower.isNotEmpty() && (displayNameLower.contains(labelLower) || labelLower.contains(displayNameLower))
            byEmail || byDomain || byDisplayName
        }

        val isRelevant = isNptelText || matchedNptelChannel != null || isUrgentOverall || isAcademicText || isClassroomOrigin || matchedPlacementChannel != null
        if (!isRelevant) {
            Log.d("NHQ-Router", "Gmail dropped: ${n.cleanTitle}")
            return
        }

        fun buildFinalTitle(baseEmoji: String): String {
            var t = buildDueTitle(n.cleanTitle, n.fullCorpus) ?: "$baseEmoji ${n.cleanTitle}"
            if (isUrgentOverall && !t.contains("🔴 URGENT")) t = "🔴 URGENT: $t"
            return t
        }

        if (isNptelText || matchedNptelChannel != null) {
            val dest = matchedNptelChannel?.label ?: BUCKET_NPTEL
            return saveAndTag(context, db, buildFinalTitle("📚"), n.displayBody, dest, "gmail", n.fullCorpus)
        }

        if (matchedPlacementChannel != null) return saveAndTag(context, db, buildFinalTitle("🏢"), n.displayBody, matchedPlacementChannel.label, "gmail", n.fullCorpus)

        val academicScored = savedCourses.map { course -> course to courseMatchScore(n.searchLower, course.courseName, course.courseSymbol, course.courseId) }
        val bestCoursePair = academicScored.maxByOrNull { (_, score) -> score }
        val bestCourseScore = bestCoursePair?.second ?: 0
        val bestCourse = bestCoursePair?.first

        if (isClassroomOrigin) {
            val dest = if (bestCourse != null && bestCourseScore > 0) bestCourse.courseName else BUCKET_IMPORTANT_EMAILS
            return saveAndTag(context, db, buildFinalTitle("📘"), n.displayBody, dest, "classroom", n.fullCorpus)
        }

        if (bestCourse != null && bestCourseScore > 0) return saveAndTag(context, db, buildFinalTitle("📧"), n.displayBody, bestCourse.courseName, "gmail", n.fullCorpus)
        saveAndTag(context, db, buildFinalTitle("📧"), n.displayBody, BUCKET_IMPORTANT_EMAILS, "gmail", n.fullCorpus)
    }

    private suspend fun saveAndTag(
        context: android.content.Context,
        db: AppDatabase,
        title: String,
        text: String,
        source: String,
        packageSource: String,
        fullCorpus: String
    ) {
        val model = NotificationModel(title = title, text = text, source = source, packageSource = packageSource, timestamp = System.currentTimeMillis())
        val insertedId = db.notificationDao().insertNotification(model)
        Log.d("NHQ-Router", "Saved [$packageSource → $source]: $title")

        // 🟢 TRIGGER #1: Update widget instantly on arrival
        DashboardWidgetProvider.triggerWidgetUpdate(context)

        val tagInput = "$title\n$fullCorpus"
        when (val result = GeminiTagger.tag(tagInput)) {
            is GeminiTagger.TagResult.Tagged -> {
                db.notificationDao().updateTagFields(insertedId.toInt(), result.isUrgent, result.dueDate)
                // 🟢 TRIGGER #2: Update widget again if Gemini found a new date!
                DashboardWidgetProvider.triggerWidgetUpdate(context)
            }
            is GeminiTagger.TagResult.NoTagNeeded -> {}
        }
    }

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

    private fun buildDueTitle(rawTitle: String, fullCorpus: String): String? {
        val lower = fullCorpus.lowercase()
        val isTomorrow = lower.contains("tomorrow") || lower.contains("tmrw")
        val isToday = lower.contains("today")
        val eventKeywords = listOf("interview", "technical round", "hr round", "group discussion", "aptitude", "exam", "test", "assessment", "deadline", "due", "submission", "scheduled")

        if (eventKeywords.any { lower.contains(it) } && (isTomorrow || isToday)) {
            val cal = Calendar.getInstance()
            if (isTomorrow) cal.add(Calendar.DAY_OF_YEAR, 1)
            val sdf = SimpleDateFormat("MMM dd", Locale.getDefault())
            return "🟡 DUE ${if (isTomorrow) "Tomorrow" else "Today"} (${sdf.format(cal.time)}) — $rawTitle"
        }

        val duePatterns = listOf("closes on", "due date", "due on", "last date", "submit by", "deadline", "ends on", "scheduled on", "scheduled for", "interview on", "assessment on", "held on", "exam on", "test on")
        if (!duePatterns.any { lower.contains(it) }) return null

        val regexStr = "(?i)(" + duePatterns.joinToString("|") { "$it\\s*:?" } + ")"
        val rawSegment = fullCorpus.split(Regex(regexStr)).getOrNull(1)?.trim() ?: ""
        if (rawSegment.isEmpty()) return "🟡 $rawTitle"

        val window = rawSegment.take(DATE_WINDOW)
        val boundaryIndex = listOf(window.indexOf(". "), window.indexOf("! "), window.indexOf("? "), window.indexOf("\n")).filter { it > 0 }.minOrNull()
        val datePart = if (boundaryIndex != null) window.substring(0, boundaryIndex + 1).trim() else {
            val trimmed = window.trim()
            val lastSpace = trimmed.lastIndexOf(' ')
            if (lastSpace > 0) trimmed.substring(0, lastSpace) else trimmed
        }
        return if (datePart.isNotEmpty()) "🟡 DUE $datePart — $rawTitle" else "🟡 $rawTitle"
    }
}