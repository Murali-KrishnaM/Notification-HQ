package com.bravo.notificationhq

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FullNotificationActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_full_notification)

        // ── Receive data from intent ───────────────────────────
        val title         = intent.getStringExtra("NOTIF_TITLE")    ?: ""
        val text          = intent.getStringExtra("NOTIF_TEXT")     ?: ""
        val source        = intent.getStringExtra("NOTIF_SOURCE")   ?: ""
        val packageSource = intent.getStringExtra("NOTIF_PACKAGE")  ?: ""
        val timestamp     = intent.getLongExtra("NOTIF_TIMESTAMP", 0L)

        // ── Views ──────────────────────────────────────────────
        val btnBack          = findViewById<TextView>(R.id.btnBack)
        val tvTitle          = findViewById<TextView>(R.id.tvFullTitle)
        val tvTimestamp      = findViewById<TextView>(R.id.tvFullTimestamp)
        val tvMessage        = findViewById<TextView>(R.id.tvFullMessage)
        val tvSource         = findViewById<TextView>(R.id.tvFullSource)
        val tvPriorityChip   = findViewById<TextView>(R.id.tvFullPriorityChip)
        val viewAccentBar    = findViewById<View>(R.id.viewFullAccentBar)
        val header           = findViewById<View>(R.id.headerFull)

        // ── Back button ────────────────────────────────────────
        btnBack.setOnClickListener { finish() }

        // ── Title ──────────────────────────────────────────────
        tvTitle.text = title

        // ── Timestamp ──────────────────────────────────────────
        tvTimestamp.text = if (timestamp > 0L) {
            SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
                .format(Date(timestamp))
        } else {
            "Unknown time"
        }

        // ── Clean message body (strip priority tags) ───────────
        val cleanText = text
            .replace("🔴 URGENT", "")
            .replace("🟡 DUE", "")
            .trim()
        tvMessage.text = cleanText

        // ── Source chip ────────────────────────────────────────
        val (sourceLabel, sourceColor) = when {
            packageSource.contains("whatsapp", ignoreCase = true) ->
                Pair("WhatsApp", Color.parseColor("#075E54"))
            packageSource.contains("gmail", ignoreCase = true) ->
                Pair("Gmail", Color.parseColor("#D93025"))
            packageSource.contains("classroom", ignoreCase = true) ->
                Pair("Classroom", Color.parseColor("#1A73E8"))
            else ->
                Pair(source.take(12), Color.parseColor("#424242"))
        }
        tvSource.text = sourceLabel
        tvSource.background.setTint(sourceColor)

        // ── Priority chip + accent bar + header color ──────────
        when {
            text.contains("🔴 URGENT") -> {
                tvPriorityChip.visibility = View.VISIBLE
                tvPriorityChip.text = "🔴 URGENT"
                tvPriorityChip.background.setTint(Color.parseColor("#D32F2F"))
                viewAccentBar.setBackgroundColor(Color.parseColor("#D32F2F"))
                header.setBackgroundColor(Color.parseColor("#D32F2F"))
            }
            text.contains("🟡 DUE") -> {
                tvPriorityChip.visibility = View.VISIBLE
                tvPriorityChip.text = "🟡 DUE"
                tvPriorityChip.background.setTint(Color.parseColor("#F57F17"))
                viewAccentBar.setBackgroundColor(Color.parseColor("#F57F17"))
                header.setBackgroundColor(Color.parseColor("#F57F17"))
            }
            else -> {
                tvPriorityChip.visibility = View.GONE
                viewAccentBar.setBackgroundColor(Color.parseColor("#4CAF50"))
                // header stays default green
            }
        }
    }
}