package com.bravo.notificationhq.pipeline

import android.util.Log
import com.bravo.notificationhq.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Analyses a notification body and returns tagging data (isUrgent, isDue, dueDate).
 *
 * FLOW:
 * 1. Gate check: does text have a date/day/keyword signal? If NO -> skip, save quota.
 * 2. Call Gemini Flash -> parse JSON response.
 * 3. If quota hit (429) or any error -> regex fallback.
 */
object GeminiTagger {

    private const val TAG = "NHQ-GeminiTagger"

    private const val GEMINI_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"

    // ── Gate keyword lists ────────────────────────────────────────────────

    private val DAY_NAMES = listOf(
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "mon", "tue", "wed", "thu", "fri", "sat", "sun"
    )

    private val RELATIVE_WORDS = listOf(
        "tomorrow", "tmrw", "today", "tonight", "this week", "next week"
    )

    private val MONTH_NAMES = listOf(
        "january", "february", "march", "april", "may", "june",
        "july", "august", "september", "october", "november", "december",
        "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "oct", "nov", "dec"
    )

    private val DATE_TRIGGER_KEYWORDS = listOf(
        "interview", "technical round", "hr round", "assessment", "aptitude",
        "exam", "test", "deadline", "submission", "due", "scheduled", "held on",
        "report", "last date", "closes", "ends on", "viva", "project review",
        "orientation", "meeting", "session", "placement drive"
    )

    // Catches: "28.04.26", "28/04/2026", "28-04-26", "04/28/26"
    private val DATE_PATTERN = Regex("""\b\d{1,2}[./-]\d{1,2}[./-]\d{2,4}\b""")

    // ── Result sealed class ───────────────────────────────────────────────

    sealed class TagResult {
        data class Tagged(
            val isUrgent: Boolean,
            val isDue: Boolean,
            val dueDate: String?        // ISO "yyyy-MM-dd" or null
        ) : TagResult()

        object NoTagNeeded : TagResult()
    }

    // ─────────────────────────────────────────────────────────────────────
    // PUBLIC ENTRY
    // ─────────────────────────────────────────────────────────────────────

    suspend fun tag(text: String): TagResult = withContext(Dispatchers.IO) {
        val lower = text.lowercase()

        // Gate: skip Gemini if no date signal at all
        val hasDateSignal = DATE_PATTERN.containsMatchIn(text)
                || DAY_NAMES.any { lower.contains(it) }
                || RELATIVE_WORDS.any { lower.contains(it) }
                || MONTH_NAMES.any { lower.contains(it) }
                || DATE_TRIGGER_KEYWORDS.any { lower.contains(it) }

        if (!hasDateSignal) {
            Log.d(TAG, "Gate: no date signal, skipping Gemini.")
            return@withContext TagResult.NoTagNeeded
        }

        Log.d(TAG, "Gate passed — calling Gemini Flash...")

        try {
            val result = callGemini(text)
            if (result != null) return@withContext result
        } catch (e: Exception) {
            Log.w(TAG, "Gemini call failed (${e.message}) — falling back to regex.")
        }

        Log.d(TAG, "Using regex fallback.")
        return@withContext regexFallback(lower)
    }

    // ─────────────────────────────────────────────────────────────────────
    // GEMINI API CALL
    // ─────────────────────────────────────────────────────────────────────

    private fun callGemini(notifText: String): TagResult.Tagged? {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey == "null") {
            Log.w(TAG, "Gemini API key not configured.")
            return null
        }

        val todayStr = SimpleDateFormat("yyyy-MM-dd (EEEE, dd MMMM yyyy)", Locale.getDefault())
            .format(Date())

        val prompt = buildPrompt(notifText, todayStr)

        val requestBody = JSONObject().apply {
            put("contents", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", org.json.JSONArray().apply {
                        put(JSONObject().apply { put("text", prompt) })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.0)
                put("maxOutputTokens", 800) // 🔴 INCREASED TOKEN LIMIT HERE
                put("responseMimeType", "application/json")
            })
        }

        val url = URL("$GEMINI_URL?key=$apiKey")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 8000
        conn.readTimeout = 10000

        OutputStreamWriter(conn.outputStream).use { it.write(requestBody.toString()) }

        val responseCode = conn.responseCode

        if (responseCode == 429) {
            Log.w(TAG, "Gemini quota hit (429) — falling back to regex.")
            return null
        }
        if (responseCode != 200) {
            Log.w(TAG, "Gemini HTTP $responseCode — falling back to regex.")
            return null
        }

        val responseText = conn.inputStream.bufferedReader().readText()
        return parseGeminiResponse(responseText)
    }

    /**
     * Uses string concatenation deliberately — avoids triple-quote conflicts
     * that would break Kotlin compilation when the prompt contains JSON examples.
     */
    private fun buildPrompt(notifText: String, todayStr: String): String {
        return "Today's date is: $todayStr\n\n" +
                "You are a notification classifier for a student's academic notification app.\n" +
                "Analyse the notification text below and reply ONLY with a valid JSON object.\n" +
                "No markdown, no backticks, no explanation, no extra keys.\n\n" +
                "Rules:\n" +
                "- isUrgent: true if the message is time-sensitive or mentions cancellations, " +
                "changes, alerts, or requires immediate action.\n" +
                "- isDue: true if there is a deadline, submission, interview, exam, assessment, " +
                "or any scheduled event with a specific date or time.\n" +
                "- dueDate: if isDue is true, extract the date and format it as yyyy-MM-dd. " +
                "Resolve relative dates like tomorrow or next Monday using today's date shown above. " +
                "If no specific date can be determined, set to null.\n\n" +
                "Notification text:\n" +
                notifText.take(800) + "\n\n" +
                "Respond ONLY with one of these two JSON formats, nothing else:\n" +
                "{\"isUrgent\": true, \"isDue\": true, \"dueDate\": \"2026-04-28\"}\n" +
                "{\"isUrgent\": false, \"isDue\": false, \"dueDate\": null}"
    }

    private fun parseGeminiResponse(rawResponse: String): TagResult.Tagged? {
        return try {
            val root = JSONObject(rawResponse)
            val contentText = root
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()

            val json = JSONObject(contentText)
            val isUrgent = json.optBoolean("isUrgent", false)
            val isDue    = json.optBoolean("isDue", false)
            val dueDate  = json.optString("dueDate", "null").let {
                if (it == "null" || it.isBlank()) null else it
            }

            Log.d(TAG, "Gemini -> isUrgent=$isUrgent | isDue=$isDue | dueDate=$dueDate")
            TagResult.Tagged(isUrgent = isUrgent, isDue = isDue, dueDate = dueDate)
        } catch (e: Exception) {
            Log.e(TAG, "Gemini parse failed: ${e.message}")
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // REGEX FALLBACK
    // ─────────────────────────────────────────────────────────────────────

    private val URGENT_FALLBACK = listOf(
        "urgent", "important", "cancelled", "cancel", "postponed", "rescheduled",
        "room change", "venue change", "no class", "holiday", "attendance", "absent"
    )

    private val DUE_FALLBACK = listOf(
        "deadline", "due date", "submit by", "closes on", "last date",
        "interview on", "exam on", "assessment on", "scheduled on",
        "technical round", "hr round", "placement drive", "project review"
    )

    private fun regexFallback(lower: String): TagResult.Tagged {
        val isUrgent = URGENT_FALLBACK.any { lower.contains(it) }
        val isDue    = DUE_FALLBACK.any { lower.contains(it) }
        val dueDate: String? = if (isDue) {
            DATE_PATTERN.find(lower)?.let { normaliseDate(it.value) }
        } else null

        Log.d(TAG, "Regex fallback -> isUrgent=$isUrgent | isDue=$isDue | dueDate=$dueDate")
        return TagResult.Tagged(isUrgent = isUrgent, isDue = isDue, dueDate = dueDate)
    }

    private fun normaliseDate(raw: String): String? {
        val cleaned = raw.replace(Regex("[/.]"), "-")
        val parts = cleaned.split("-")
        if (parts.size != 3) return null
        return try {
            val (d, m, y) = parts
            val year = if (y.length == 2) "20$y" else y
            val cal = Calendar.getInstance()
            cal.set(year.toInt(), m.toInt() - 1, d.toInt())
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
        } catch (e: Exception) {
            null
        }
    }
}