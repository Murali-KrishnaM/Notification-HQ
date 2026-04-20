package com.bravo.notificationhq.pipeline

/**
 * ══════════════════════════════════════════════════════════════════════════
 * MODULE 2 — NOTIFICATION DENOISER & TEXT EXTRACTOR
 * ══════════════════════════════════════════════════════════════════════════
 *
 * Responsibility:
 *   - Take a [CapturedNotification] and produce a [DenoisedNotification].
 *   - Pick the best display body (bigText > textLines > stdText).
 *   - Build a single lowercase search corpus for the router to scan.
 *   - Extract the sender email address from the notification text.
 *     Handles three Gmail formats:
 *       (a) Plain email in body:   "someone@domain.com"
 *       (b) Angle-bracket format:  "Dr. Priya <priya@rec.edu.in>"
 *       (c) Display name only:     "Placement Cell" (no email present)
 *   - Extract the sender display name for router fallback when no email found.
 *   - Strip WhatsApp "(X unread)" group-name suffixes from the title.
 *   - Apply echo-killing (deduplication) to avoid storing the same message twice.
 *
 * This module has ZERO routing logic.
 */
object NotificationDenoiser {

    // ── Echo Killer: remembers the last 20 message fingerprints ───────────
    private val recentFingerprints = ArrayDeque<String>(20)

    // ── Email extraction patterns ──────────────────────────────────────────
    // Format (a) and (b): raw email or angle-bracket wrapped email
    private val EMAIL_REGEX         = Regex("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}")
    private val ANGLE_BRACKET_REGEX = Regex("<([a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,})>")

    /**
     * Denoise and structure a [CapturedNotification].
     *
     * @return A [DenoisedNotification] ready for routing, or null if this
     *         notification is a duplicate and should be dropped (echo-killed).
     */
    fun denoise(captured: CapturedNotification): DenoisedNotification? {

        // ── Step 1: Pick the best display body ────────────────────────────
        val displayBody = when {
            captured.bigText.isNotEmpty() && captured.bigText != captured.stdText -> captured.bigText
            captured.textLines.isNotEmpty() -> "${captured.stdText}\n${captured.textLines}".trim()
            captured.stdText.isNotEmpty()   -> captured.stdText
            else                            -> return null   // nothing to show
        }

        // ── Step 2: Echo kill ──────────────────────────────────────────────
        val fingerprint = displayBody.lowercase().take(60)
        if (recentFingerprints.any { it == fingerprint }) return null
        recentFingerprints.addLast(fingerprint)
        if (recentFingerprints.size > 20) recentFingerprints.removeFirst()

        // ── Step 3: Build the unified search corpus ────────────────────────
        // Lowercase concat of ALL text fields for keyword scanning in the router.
        val fullCorpus = buildString {
            append(captured.rawTitle).append(" ")
            append(captured.stdText).append(" ")
            if (captured.bigText.isNotEmpty())   append(captured.bigText).append(" ")
            if (captured.textLines.isNotEmpty()) append(captured.textLines)
        }.trim()

        val searchLower = fullCorpus.lowercase()

        // ── Step 4: Extract sender email ───────────────────────────────────
        // Priority: angle-bracket > plain email in stdText > plain email in title
        // > plain email anywhere in corpus
        val senderEmail = extractSenderEmail(
            rawTitle   = captured.rawTitle,
            stdText    = captured.stdText,
            fullCorpus = fullCorpus
        )

        // ── Step 5: Extract sender display name ───────────────────────────
        // Used as a fallback in the router when no email address is present.
        // Gmail notification titles look like:
        //   "Dr. Priya Sharma - Assignment Due"    → display name = "Dr. Priya Sharma"
        //   "Placement Cell <place@rec.edu.in>"    → display name = "Placement Cell"
        //   "no-reply@nptel.iitm.ac.in"            → display name = "" (email only, no name)
        val senderDisplayName = extractSenderDisplayName(captured.rawTitle, senderEmail)

        // ── Step 6: Clean WhatsApp title ──────────────────────────────────
        // WhatsApp appends " (3)" or " (12 unread)" to group names in notifications.
        val cleanTitle = when {
            captured.packageName.contains("whatsapp", ignoreCase = true) ->
                captured.rawTitle.substringBefore(" (").trim()
            else ->
                captured.rawTitle
        }

        return DenoisedNotification(
            packageName       = captured.packageName,
            cleanTitle        = cleanTitle,
            displayBody       = displayBody,
            fullCorpus        = fullCorpus,
            searchLower       = searchLower,
            senderEmail       = senderEmail,
            senderDisplayName = senderDisplayName,
            stdText           = captured.stdText
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // INTERNAL: Sender email extractor
    // ─────────────────────────────────────────────────────────────────────

    private fun extractSenderEmail(
        rawTitle: String,
        stdText: String,
        fullCorpus: String
    ): String {
        // Try angle-bracket format first — most reliable for Gmail
        // e.g. "Placement Cell <placement@rec.edu.in>"
        ANGLE_BRACKET_REGEX.find(rawTitle)?.groupValues?.getOrNull(1)?.let   { return it }
        ANGLE_BRACKET_REGEX.find(stdText)?.groupValues?.getOrNull(1)?.let    { return it }
        ANGLE_BRACKET_REGEX.find(fullCorpus)?.groupValues?.getOrNull(1)?.let { return it }

        // Fall back to plain email scan
        EMAIL_REGEX.find(stdText)?.value?.let    { return it }
        EMAIL_REGEX.find(rawTitle)?.value?.let   { return it }
        EMAIL_REGEX.find(fullCorpus)?.value?.let { return it }

        return ""
    }

    // ─────────────────────────────────────────────────────────────────────
    // INTERNAL: Sender display name extractor
    //
    // Gmail notification titles typically follow one of these patterns:
    //   "Sender Name - Email Subject"
    //   "Sender Name <email@domain>"
    //   "email@domain"  (no display name)
    //
    // We extract the part before " - " or " <" as the display name.
    // ─────────────────────────────────────────────────────────────────────

    private fun extractSenderDisplayName(rawTitle: String, senderEmail: String): String {
        // If the title is just an email address, there is no display name
        if (rawTitle.trim() == senderEmail) return ""

        // Strip angle-bracket section: "Placement Cell <email@...>" → "Placement Cell"
        val withoutAngleBracket = rawTitle.substringBefore("<").trim()

        // Strip subject portion after " - ": "Sender Name - Assignment Due" → "Sender Name"
        val nameCandidate = withoutAngleBracket.substringBefore(" - ").trim()

        // If what's left looks like an email address, there's no separate display name
        return if (EMAIL_REGEX.matches(nameCandidate)) "" else nameCandidate
    }
}

/**
 * Cleaned, structured notification data — ready for the router.
 */
data class DenoisedNotification(
    val packageName: String,        // "com.whatsapp" | "com.google.android.gm"
    val cleanTitle: String,         // WA group name (stripped), or Gmail sender line
    val displayBody: String,        // Best available body text to store and show
    val fullCorpus: String,         // All text concatenated (for keyword scanning)
    val searchLower: String,        // fullCorpus.lowercase() — pre-computed for router
    val senderEmail: String,        // Extracted sender email (may be empty)
    val senderDisplayName: String,  // Sender display name — fallback when no email
    val stdText: String             // Original stdText (for router edge cases)
)