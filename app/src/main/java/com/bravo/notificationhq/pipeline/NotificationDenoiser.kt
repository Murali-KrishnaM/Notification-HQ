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
 *   - Extract any sender email address embedded in the notification text.
 *   - Strip WhatsApp "(X)" group-name suffixes.
 *   - Apply echo-killing (deduplication) to avoid storing the same message twice.
 *
 * This module has NO routing logic. It only cleans and structures the data.
 */
object NotificationDenoiser {

    // ── Echo Killer: remembers the last 20 message fingerprints ───────────
    private val recentFingerprints = ArrayDeque<String>(20)

    // ── Email extraction regex ─────────────────────────────────────────────
    private val EMAIL_REGEX = Regex("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}")

    /**
     * Denoise and structure a [CapturedNotification].
     *
     * @return A [DenoisedNotification] ready for routing, or null if this
     *         notification is a duplicate and should be dropped.
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
        // Lowercase concat of all text fields for keyword scanning in the router
        val fullCorpus = buildString {
            append(captured.rawTitle).append(" ")
            append(captured.stdText).append(" ")
            if (captured.bigText.isNotEmpty()) append(captured.bigText).append(" ")
            if (captured.textLines.isNotEmpty()) append(captured.textLines)
        }.trim()

        val searchLower = fullCorpus.lowercase()

        // ── Step 4: Extract sender email (Gmail-specific) ──────────────────
        val senderEmail = extractSenderEmail(
            rawTitle   = captured.rawTitle,
            stdText    = captured.stdText,
            fullCorpus = fullCorpus
        )

        // ── Step 5: Clean WhatsApp title (strip "(3 unread)" type suffixes) ─
        val cleanTitle = when {
            captured.packageName.contains("whatsapp", ignoreCase = true) ->
                captured.rawTitle.substringBefore(" (").trim()
            else ->
                captured.rawTitle
        }

        return DenoisedNotification(
            packageName  = captured.packageName,
            cleanTitle   = cleanTitle,
            displayBody  = displayBody,
            fullCorpus   = fullCorpus,
            searchLower  = searchLower,
            senderEmail  = senderEmail,
            stdText      = captured.stdText
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // INTERNAL: Email extractor
    // Tries stdText first (most reliable), then rawTitle, then full corpus.
    // ─────────────────────────────────────────────────────────────────────
    private fun extractSenderEmail(
        rawTitle: String,
        stdText: String,
        fullCorpus: String
    ): String {
        EMAIL_REGEX.find(stdText)?.value?.let    { return it }
        EMAIL_REGEX.find(rawTitle)?.value?.let   { return it }
        EMAIL_REGEX.find(fullCorpus)?.value?.let { return it }
        return ""
    }
}

/**
 * Cleaned, structured notification data ready for the router.
 */
data class DenoisedNotification(
    val packageName: String,   // "com.whatsapp" | "com.google.android.gm" | etc.
    val cleanTitle: String,    // WA group name (stripped), or Gmail sender line
    val displayBody: String,   // Best available body text to store and show
    val fullCorpus: String,    // All text concatenated (for keyword scanning)
    val searchLower: String,   // fullCorpus.lowercase() — pre-computed for router
    val senderEmail: String,   // Extracted sender email (may be empty)
    val stdText: String        // Original stdText (needed for router edge cases)
)
