package com.bravo.notificationhq

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FullNotificationActivity : AppCompatActivity() {

    private val TAG = "NHQ-Summarize"

    private val GEMINI_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_full_notification)

        // ── Receive data from intent ───────────────────────────
        val notifId       = intent.getIntExtra("NOTIF_ID", -1)
        val title         = intent.getStringExtra("NOTIF_TITLE")    ?: ""
        val text          = intent.getStringExtra("NOTIF_TEXT")     ?: ""
        val source        = intent.getStringExtra("NOTIF_SOURCE")   ?: ""
        val packageSource = intent.getStringExtra("NOTIF_PACKAGE")  ?: ""
        val timestamp     = intent.getLongExtra("NOTIF_TIMESTAMP", 0L)

        // ── Views ──────────────────────────────────────────────
        val btnBack         = findViewById<TextView>(R.id.btnBack)
        val tvTitle         = findViewById<TextView>(R.id.tvFullTitle)
        val tvTimestamp     = findViewById<TextView>(R.id.tvFullTimestamp)
        val tvMessage       = findViewById<TextView>(R.id.tvFullMessage)
        val tvSource        = findViewById<TextView>(R.id.tvFullSource)
        val tvPriorityChip  = findViewById<TextView>(R.id.tvFullPriorityChip)
        val viewAccentBar   = findViewById<View>(R.id.viewFullAccentBar)
        val header          = findViewById<View>(R.id.headerFull)
        val btnSummarize    = findViewById<TextView>(R.id.btnSummarize)
        val cardSummary     = findViewById<View>(R.id.cardSummary)
        val progressSummary = findViewById<ProgressBar>(R.id.progressSummary)
        val tvSummaryText   = findViewById<TextView>(R.id.tvSummaryText)

        // ── Back button ────────────────────────────────────────
        btnBack.setOnClickListener { finish() }

        // ── Title ──────────────────────────────────────────────
        tvTitle.text = title

        // ── Timestamp ──────────────────────────────────────────
        tvTimestamp.text = if (timestamp > 0L) {
            SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(timestamp))
        } else "Unknown time"

        // ── Clean message body ─────────────────────────────────
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
            }
        }

        // ── Summarize Button ───────────────────────────────────
        btnSummarize.setOnClickListener {
            cardSummary.visibility = View.VISIBLE
            progressSummary.visibility = View.VISIBLE
            tvSummaryText.visibility = View.GONE
            btnSummarize.isEnabled = false
            btnSummarize.alpha = 0.5f

            lifecycleScope.launch {
                val summary = getSummary(notifId, title, cleanText)
                withContext(Dispatchers.Main) {
                    progressSummary.visibility = View.GONE
                    tvSummaryText.visibility = View.VISIBLE
                    tvSummaryText.text = summary
                    btnSummarize.isEnabled = true
                    btnSummarize.alpha = 1.0f
                    btnSummarize.text = "✦ Summary ↑"
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // SUMMARIZE LOGIC
    // ─────────────────────────────────────────────────────────────────────

    private suspend fun getSummary(notifId: Int, title: String, body: String): String =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "getSummary called — notifId=$notifId")

            // Check DB cache first
            if (notifId != -1) {
                try {
                    val db = AppDatabase.getDatabase(applicationContext)
                    val existing = db.notificationDao().getNotificationById(notifId)
                    if (!existing?.summaryText.isNullOrBlank()) {
                        Log.d(TAG, "Cache hit — returning stored summary.")
                        return@withContext existing!!.summaryText!!
                    }
                    Log.d(TAG, "Cache miss — no stored summary yet.")
                } catch (e: Exception) {
                    Log.e(TAG, "DB cache read failed: ${e.message}")
                }
            } else {
                Log.w(TAG, "notifId is -1 — NOTIF_ID extra was not passed in the intent. Caching will be skipped.")
            }

            val geminiResult = callGeminiSummary(title, body)

            if (geminiResult != null) {
                Log.d(TAG, "Gemini summary success.")
                if (notifId != -1) {
                    try {
                        val db = AppDatabase.getDatabase(applicationContext)
                        db.notificationDao().updateSummary(notifId, geminiResult)
                        Log.d(TAG, "Summary cached in DB for id=$notifId")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to cache summary: ${e.message}")
                    }
                }
                geminiResult
            } else {
                Log.e(TAG, "Gemini returned null — showing error message to user.")
                "Could not generate summary. Please try again when connected."
            }
        }

    private fun callGeminiSummary(title: String, body: String): String? {
        val apiKey = BuildConfig.GEMINI_API_KEY

        // ── Log 1: API key check ──────────────────────────────
        Log.d(TAG, "API key present: ${apiKey.isNotBlank()} | length: ${apiKey.length} | value preview: ${apiKey.take(8)}...")
        if (apiKey.isBlank() || apiKey == "null") {
            Log.e(TAG, "GEMINI_API_KEY is blank or null — check local.properties and BuildConfig.")
            return null
        }

        val prompt = "You are summarizing a notification for a college student.\n" +
                "Write a concise summary in 2-4 short bullet points.\n" +
                "Focus on: what needs to be done, when (if mentioned), and why it matters.\n" +
                "Use simple language. No fluff. Start directly with the bullet points.\n\n" +
                "Notification title: $title\n" +
                "Notification body: ${body.take(800)}"

        val requestBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", prompt) })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.3)
                put("maxOutputTokens", 800) // 🔴 INCREASED TOKEN LIMIT HERE
            })
        }

        Log.d(TAG, "Request body built. Connecting to Gemini...")

        return try {
            val url = URL("$GEMINI_URL?key=$apiKey")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 12000

            // ── Log 2: Sending request ────────────────────────
            Log.d(TAG, "Sending POST to Gemini...")
            OutputStreamWriter(conn.outputStream).use { it.write(requestBody.toString()) }

            val responseCode = conn.responseCode
            Log.d(TAG, "Gemini HTTP response code: $responseCode")

            if (responseCode != 200) {
                // ── Log 3: Read error body so we know WHY it failed ──
                val errorBody = try {
                    BufferedReader(InputStreamReader(conn.errorStream)).readText()
                } catch (e: Exception) {
                    "Could not read error stream: ${e.message}"
                }
                Log.e(TAG, "Gemini error response (HTTP $responseCode): $errorBody")
                return null
            }

            val responseText = conn.inputStream.bufferedReader().readText()
            Log.d(TAG, "Gemini raw response: $responseText")

            val root = JSONObject(responseText)
            val summary = root
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()

            Log.d(TAG, "Parsed summary: $summary")
            summary

        } catch (e: Exception) {
            // ── Log 4: Full exception with stack trace ────────
            Log.e(TAG, "Exception during Gemini call: ${e.javaClass.simpleName} — ${e.message}", e)
            null
        }
    }
}