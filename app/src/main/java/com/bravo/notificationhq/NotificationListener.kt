package com.bravo.notificationhq

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.bravo.notificationhq.pipeline.NotificationPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * ══════════════════════════════════════════════════════════════════════════
 * NOTIFICATION LISTENER — OS SERVICE ENTRY POINT
 * ══════════════════════════════════════════════════════════════════════════
 *
 * This class is now a thin wrapper around [NotificationPipeline].
 * It has exactly ONE responsibility: receive a notification from the Android OS
 * and hand it off to the pipeline on an IO coroutine.
 *
 * ALL routing, denoising, and capture logic lives in:
 *   pipeline/NotificationCapture.kt   ← Module 1: gate-keeping
 *   pipeline/NotificationDenoiser.kt  ← Module 2: text extraction & echo kill
 *   pipeline/NotificationRouter.kt    ← Module 3: routing & DB write
 *   pipeline/NotificationPipeline.kt  ← Orchestrator
 *
 * DO NOT add any logic here. Keep this class < 40 lines.
 */
class NotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        // Hand off immediately to the pipeline on a background thread.
        // All heavy work (DB reads, keyword scanning) happens off the main thread.
        CoroutineScope(Dispatchers.IO).launch {
            NotificationPipeline.process(sbn, applicationContext)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Not needed for this app — notifications are stored in our own DB.
    }
}
