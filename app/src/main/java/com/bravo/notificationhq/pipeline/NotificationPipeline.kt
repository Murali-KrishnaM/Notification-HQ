package com.bravo.notificationhq.pipeline

import android.content.Context
import android.util.Log
import com.bravo.notificationhq.AppDatabase
import com.bravo.notificationhq.pipeline.NotificationCapture
import com.bravo.notificationhq.pipeline.NotificationDenoiser
import com.bravo.notificationhq.pipeline.NotificationRouter
import android.service.notification.StatusBarNotification

/**
 * ══════════════════════════════════════════════════════════════════════════
 * NOTIFICATION PIPELINE — ORCHESTRATOR
 * ══════════════════════════════════════════════════════════════════════════
 *
 * This is the single entry point for the entire notification processing chain.
 * It chains the three pipeline modules in order:
 *
 *   [NotificationCapture]  →  [NotificationDenoiser]  →  [NotificationRouter]
 *        Gate                      Clean & Extract              Route & Save
 *
 * [NotificationListener] calls only this class — it has no direct knowledge
 * of routing, parsing, or database writes.
 *
 * Must be called from a coroutine on [kotlinx.coroutines.Dispatchers.IO].
 */
object NotificationPipeline {

    private const val TAG = "NHQ-Pipeline"

    /**
     * Process one [StatusBarNotification] through the full pipeline.
     *
     * @param sbn     The raw notification from the OS listener service.
     * @param context Application context for database access.
     */
    suspend fun process(sbn: StatusBarNotification, context: Context) {
        try {
            // ── STAGE 1: Capture ──────────────────────────────────────────
            // Decide if this notification is even worth processing.
            // Returns null if the package is not allowed or notification is a spam summary.
            val captured = NotificationCapture.capture(sbn)
            if (captured == null) {
                // Silent drop — package not in whitelist or WA summary spam
                return
            }

            Log.d(TAG, "Captured [${captured.packageName}] → '${captured.rawTitle}'")

            // ── STAGE 2: Denoise ──────────────────────────────────────────
            // Extract clean display body, build search corpus, extract sender.
            // Returns null if this is a duplicate (echo-killed).
            val denoised = NotificationDenoiser.denoise(captured)
            if (denoised == null) {
                Log.d(TAG, "Echo-killed duplicate, dropped: '${captured.rawTitle}'")
                return
            }

            Log.d(TAG, "Denoised → sender='${denoised.senderEmail}' | body length=${denoised.displayBody.length}")

            // ── STAGE 3: Route & Save ─────────────────────────────────────
            // Decide which bucket/course this belongs to and persist it.
            val db = AppDatabase.getDatabase(context)
            NotificationRouter.route(context, db, denoised)

        } catch (e: Exception) {
            Log.e(TAG, "Unhandled exception in pipeline: ${e.message}", e)
        }
    }
}
