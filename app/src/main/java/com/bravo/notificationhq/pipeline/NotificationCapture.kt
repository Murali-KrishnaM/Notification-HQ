package com.bravo.notificationhq.pipeline

import android.service.notification.StatusBarNotification

/**
 * ══════════════════════════════════════════════════════════════════════════
 * MODULE 1 — NOTIFICATION CAPTURE GATE
 * ══════════════════════════════════════════════════════════════════════════
 *
 * Responsibility:
 * - Accept ONLY notifications from the two allowed packages.
 * - Reject WhatsApp "summary" spam (e.g., "12 new messages from 3 chats").
 * - Extract the raw strings (title, stdText, bigText, textLines) from the
 * notification bundle and pack them into a [CapturedNotification].
 * - Return null if the notification should be dropped at this early stage.
 *
 * NOTE: Google Classroom (com.google.android.apps.classroom) is intentionally
 * NOT in the allowed list. Classroom posts assignment/attendance updates via
 * Gmail (com.google.android.gm). We capture those through Gmail so we get
 * the full email body, not just the thin Classroom push snippet.
 *
 * This module has ZERO routing logic.
 */
object NotificationCapture {

    // ── Allowed source packages ────────────────────────────────────────────
    // Only WhatsApp and Gmail. Classroom emails arrive via Gmail.
    private val ALLOWED_PACKAGES = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "com.google.android.gm"
    )

    // ── WhatsApp summary noise patterns ───────────────────────────────────
    // Drops bundle-level summaries like "12 new messages from 3 chats"
    private val WA_SUMMARY_REGEX =
        Regex("^\\d+ new messages?(( from \\d+ chats?)|(( in \\d+ chats?)))?$")
    private val WA_SINGLE_SUMMARY_REGEX =
        Regex("^\\d+ new message$")

    /**
     * Attempt to capture a notification from the given [StatusBarNotification].
     *
     * @return A [CapturedNotification] ready for denoising, or null if dropped.
     */
    fun capture(sbn: StatusBarNotification): CapturedNotification? {
        val pkg = sbn.packageName ?: return null

        // Gate 1: Only process allowed packages
        if (pkg !in ALLOWED_PACKAGES) return null

        val extras = sbn.notification?.extras ?: return null

        // 🚨 --- TEMPORARY DEBUG BLOCK START --- 🚨
        if (pkg == "com.google.android.gm") {
            android.util.Log.e("Gmail-Extras-Dump", "========== START GMAIL BUNDLE DUMP ==========")
            for (key in extras.keySet()) {
                val value = extras.get(key)

                // Safely convert arrays to readable strings instead of memory addresses
                val displayValue = when (value) {
                    is Array<*> -> value.contentDeepToString()
                    is CharArray -> java.util.Arrays.toString(value)
                    is IntArray -> java.util.Arrays.toString(value)
                    is android.os.Bundle -> "Bundle[keys: ${value.keySet().joinToString()}]"
                    else -> value.toString()
                }

                android.util.Log.e(
                    "Gmail-Extras-Dump",
                    "Key: $key | Value: $displayValue | Type: ${value?.javaClass?.simpleName}"
                )
            }
            android.util.Log.e("Gmail-Extras-Dump", "========== END GMAIL BUNDLE DUMP ==========")
        }
        // 🚨 --- TEMPORARY DEBUG BLOCK END --- 🚨

        // Gate 2: Must have a title
        val rawTitle = extras.getString("android.title")?.trim() ?: return null

        // Extract all text variants from the notification bundle
        val stdText   = extras.getCharSequence("android.text")?.toString()?.trim()  ?: ""
        val bigText   = extras.getCharSequence("android.bigText")?.toString()?.trim() ?: ""
        val textLines = extras.getCharSequenceArray("android.textLines")
            ?.joinToString("\n") { it.trim() } ?: ""

        // Gate 3: Drop WhatsApp group summary notifications
        if (pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b") {
            if (stdText.matches(WA_SUMMARY_REGEX))        return null
            if (stdText.matches(WA_SINGLE_SUMMARY_REGEX)) return null
        }

        // Gate 4: Must have some body text to be meaningful
        val hasBody = stdText.isNotEmpty() || bigText.isNotEmpty() || textLines.isNotEmpty()
        if (!hasBody) return null

        return CapturedNotification(
            packageName   = pkg,
            rawTitle      = rawTitle,
            stdText       = stdText,
            bigText       = bigText,
            textLines     = textLines,
            senderEmail   = extractGmailSenderEmail(pkg, extras, stdText, bigText, textLines)
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GMAIL SENDER EMAIL EXTRACTION
    // ─────────────────────────────────────────────────────────────────────────
    //
    // Gmail notification extras do NOT expose the sender's raw email in a
    // dedicated key. We have to mine it from the available text fields:
    //
    //   • android.subText   — sometimes holds "someone@example.com" for
    //                         account-level summary notifications.
    //   • android.text      — for single-message notifications the format is
    //                         often "Sender Name <email@domain.com>: subject"
    //                         or just "email@domain.com: subject".
    //   • android.bigText   — expanded view; same patterns as android.text.
    //   • android.textLines — multi-message expanded list; each line follows
    //                         the same "Name <email>: subject" pattern.
    //
    // We scan all of these with a liberal email regex and return the FIRST
    // hit. For WhatsApp packages this always returns an empty string.

    private val EMAIL_REGEX = Regex(
        """[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}"""
    )

    private fun extractGmailSenderEmail(
        pkg: String,
        extras: android.os.Bundle,
        stdText: String,
        bigText: String,
        textLines: String
    ): String {
        if (pkg != "com.google.android.gm") return ""

        // Check android.subText first (account label / summary sender in some Gmail builds)
        val subText = extras.getCharSequence("android.subText")?.toString()?.trim() ?: ""

        // Scan candidates in priority order
        val candidates = listOf(subText, stdText, bigText, textLines)
        for (candidate in candidates) {
            if (candidate.isBlank()) continue
            val match = EMAIL_REGEX.find(candidate)
            if (match != null) return match.value.lowercase()
        }

        return ""
    }
}

/**
 * Plain data container produced by [NotificationCapture].
 * Holds the raw, unprocessed strings exactly as they came from the bundle.
 *
 * [senderEmail] — raw sender email mined from Gmail extras; empty string for
 * WhatsApp. Populated by extractGmailSenderEmail().
 */
data class CapturedNotification(
    val packageName: String,
    val rawTitle: String,
    val stdText: String,
    val bigText: String,
    val textLines: String,
    val senderEmail: String = ""
)