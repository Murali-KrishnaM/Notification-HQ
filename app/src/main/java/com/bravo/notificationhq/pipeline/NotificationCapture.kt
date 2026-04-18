package com.bravo.notificationhq.pipeline

import android.service.notification.StatusBarNotification

/**
 * ══════════════════════════════════════════════════════════════════════════
 * MODULE 1 — NOTIFICATION CAPTURE GATE
 * ══════════════════════════════════════════════════════════════════════════
 *
 * Responsibility:
 *   - Accept ONLY notifications from the three allowed packages.
 *   - Reject WhatsApp "summary" spam (e.g., "12 new messages from 3 chats").
 *   - Extract the raw strings (title, stdText, bigText, textLines) from the
 *     notification bundle and assemble them into a [CapturedNotification].
 *   - Return null if the notification should be dropped at this stage.
 *
 * This module has NO routing logic. It only decides: "Is this worth passing
 * down the pipeline?" and then packages the raw data cleanly.
 */
object NotificationCapture {

    // ── Allowed source packages ────────────────────────────────────────────
    private val ALLOWED_PACKAGES = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "com.google.android.gm",
        "com.google.android.apps.classroom"
    )

    // ── WhatsApp summary noise pattern ────────────────────────────────────
    // e.g. "12 new messages from 3 chats" or "1 new message"
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

        // Gate 2: Must have a title
        val rawTitle = extras.getString("android.title")?.trim() ?: return null

        // Extract all text variants
        val stdText   = extras.getCharSequence("android.text")?.toString()?.trim() ?: ""
        val bigText   = extras.getCharSequence("android.bigText")?.toString()?.trim() ?: ""
        val textLines = extras.getCharSequenceArray("android.textLines")
            ?.joinToString("\n") { it.trim() } ?: ""

        // Gate 3: Drop WhatsApp group summary notifications
        if (pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b") {
            if (stdText.matches(WA_SUMMARY_REGEX))        return null
            if (stdText.matches(WA_SINGLE_SUMMARY_REGEX)) return null
        }

        // Gate 4: Must have some body text to be useful
        val hasBody = stdText.isNotEmpty() || bigText.isNotEmpty() || textLines.isNotEmpty()
        if (!hasBody) return null

        return CapturedNotification(
            packageName = pkg,
            rawTitle    = rawTitle,
            stdText     = stdText,
            bigText     = bigText,
            textLines   = textLines
        )
    }
}

/**
 * Plain data container produced by [NotificationCapture].
 * Holds the raw, unprocessed strings exactly as they came from the bundle.
 */
data class CapturedNotification(
    val packageName: String,
    val rawTitle: String,
    val stdText: String,
    val bigText: String,
    val textLines: String
)
