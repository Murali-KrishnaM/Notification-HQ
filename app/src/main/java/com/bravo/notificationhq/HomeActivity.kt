package com.bravo.notificationhq

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * ══════════════════════════════════════════════════════════════════════════
 * HOME ACTIVITY — DASHBOARD
 * ══════════════════════════════════════════════════════════════════════════
 */
class HomeActivity : BaseActivity() {

    // ── Views ──────────────────────────────────────────────────────────────
    private lateinit var tvGreeting:        TextView
    private lateinit var tvUrgentCount:     TextView
    private lateinit var tvDueCount:        TextView
    private lateinit var tvNotStartedCount: TextView
    private lateinit var cardNextDue:       LinearLayout
    private lateinit var layoutAllClear:    LinearLayout
    private lateinit var layoutDueItem:     LinearLayout
    private lateinit var tvDueCourseChip:   TextView
    private lateinit var tvDueTitle:        TextView
    private lateinit var tvDueBody:         TextView
    private lateinit var btnViewAllDues:    TextView
    private lateinit var recyclerRecent:    RecyclerView
    private lateinit var layoutRecentEmpty: LinearLayout

    private var dashboardJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        bindViews()

        // Launch the new sorted dues list
        btnViewAllDues.setOnClickListener {
            startActivity(Intent(this, ActiveDuesActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        setGreeting()
        loadDashboard()
    }

    private fun bindViews() {
        tvGreeting          = findViewById(R.id.tvGreeting)
        tvUrgentCount       = findViewById(R.id.tvUrgentCount)
        tvDueCount          = findViewById(R.id.tvDueCount)
        tvNotStartedCount   = findViewById(R.id.tvNotStartedCount)
        cardNextDue         = findViewById(R.id.cardNextDue)
        layoutAllClear      = findViewById(R.id.layoutAllClear)
        layoutDueItem       = findViewById(R.id.layoutDueItem)
        tvDueCourseChip     = findViewById(R.id.tvDueCourseChip)
        tvDueTitle          = findViewById(R.id.tvDueTitle)
        tvDueBody           = findViewById(R.id.tvDueBody)
        btnViewAllDues      = findViewById(R.id.btnViewAllDues)
        recyclerRecent      = findViewById(R.id.recyclerViewRecent)
        layoutRecentEmpty   = findViewById(R.id.layoutRecentEmpty)

        recyclerRecent.layoutManager = LinearLayoutManager(this)
    }

    private fun setGreeting() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        tvGreeting.text = when {
            hour < 12 -> "Good morning — here's your update"
            hour < 17 -> "Good afternoon — here's your update"
            else      -> "Good evening — here's your update"
        }
    }

    private fun loadDashboard() {
        dashboardJob?.cancel()

        dashboardJob = lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@HomeActivity)
            val allNotifs = db.notificationDao().getAllNotifications()

            val allNotifIds = allNotifs.map { it.id }
            val statusRows  = if (allNotifIds.isNotEmpty()) {
                db.taskStatusDao().getStatusesForIds(allNotifIds)
            } else emptyList()

            // Fast lookup: notifId → status string
            val statusByNotifId = statusRows.associate { it.notifId to it.status }

            // ── Urgent Count ──────────────────────────────────────────────
            val urgentCount = allNotifs.count {
                (it.title ?: "").contains("🔴 URGENT") || (it.text ?: "").contains("🔴 URGENT")
            }

            // ── Master Active Dues List ───────────────────────────────────
            val activeDues = allNotifs.filter { notif ->
                val isDue = (notif.title ?: "").contains("🟡 DUE") || (notif.text ?: "").contains("🟡 DUE")
                val status = statusByNotifId[notif.id] ?: TaskStatusModel.STATUS_NOT_STARTED
                isDue && status != TaskStatusModel.STATUS_SUBMITTED
            }

            val activeDueCount = activeDues.size

            // ── Pending count ─────────────────────────────────────────────
            val pendingCount = allNotifs.count { notif ->
                val isDue = (notif.title ?: "").contains("🟡 DUE") || (notif.text ?: "").contains("🟡 DUE")
                val status = statusByNotifId[notif.id] ?: TaskStatusModel.STATUS_NOT_STARTED

                if (isDue) {
                    status != TaskStatusModel.STATUS_SUBMITTED
                } else {
                    status == TaskStatusModel.STATUS_IN_PROGRESS
                }
            }

            // ── True Next Due ─────────────────────────────────────────────
            val sortedActiveDues = activeDues.sortedBy { notif ->
                extractDateEpoch(notif.title ?: "", notif.timestamp)
            }

            val trueNextDueNotif = sortedActiveDues.firstOrNull()

            // ── Recent activity (Unchanged) ───────────────────────────────
            val recentNotifs = allNotifs.take(10).toMutableList()
            val recentIds    = recentNotifs.map { it.id }
            val recentStatuses = if (recentIds.isNotEmpty()) {
                db.taskStatusDao().getStatusesForIds(recentIds)
            } else emptyList()
            val recentStatusMap = recentStatuses.associate { it.notifId to it.status }.toMutableMap()

            withContext(Dispatchers.Main) {
                bindStats(urgentCount, activeDueCount, pendingCount)
                bindNextDue(trueNextDueNotif)
                bindRecentActivity(recentNotifs, recentStatusMap)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // SMART DATE READER
    // ─────────────────────────────────────────────────────────────────────
    private fun extractDateEpoch(title: String, fallbackTimestamp: Long): Long {
        val dueStr = title.substringAfter("🟡 DUE ", "").substringBefore(" — ").trim()
        if (dueStr.isEmpty()) return fallbackTimestamp

        val cal = Calendar.getInstance()

        try {
            if (dueStr.contains("tomorrow", ignoreCase = true)) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
                return cal.timeInMillis
            }

            val monthNames = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
            val words = dueStr.lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(Regex("\\s+"))

            var monthIndex = -1
            var dayOfMonth = -1

            for (word in words) {
                if (monthIndex == -1) monthIndex = monthNames.indexOfFirst { word.startsWith(it) }
                if (dayOfMonth == -1 && word.matches(Regex("\\d{1,2}"))) dayOfMonth = word.toInt()
            }

            if (monthIndex != -1 && dayOfMonth != -1) {
                cal.set(Calendar.MONTH, monthIndex)
                cal.set(Calendar.DAY_OF_MONTH, dayOfMonth)

                if (cal.timeInMillis < System.currentTimeMillis() - (14L * 24 * 60 * 60 * 1000)) {
                    cal.add(Calendar.YEAR, 1)
                }
                return cal.timeInMillis
            }
        } catch (e: Exception) {
            // If parse fails, fallback safely
        }
        return fallbackTimestamp
    }

    // ─────────────────────────────────────────────────────────────────────
    // BIND VIEWS
    // ─────────────────────────────────────────────────────────────────────

    private fun bindStats(urgent: Int, due: Int, pending: Int) {
        tvUrgentCount.text     = urgent.toString()
        tvDueCount.text        = due.toString()
        tvNotStartedCount.text = pending.toString()
    }

    private fun bindNextDue(notif: NotificationModel?) {
        if (notif == null) {
            layoutAllClear.visibility = View.VISIBLE
            layoutDueItem.visibility  = View.GONE
            cardNextDue.setOnClickListener(null)
            return
        }

        layoutAllClear.visibility = View.GONE
        layoutDueItem.visibility  = View.VISIBLE

        tvDueCourseChip.text = (notif.source ?: "Course").take(20)

        tvDueTitle.text = (notif.title ?: "")
            .trim()
            .removePrefix("🟡 DUE")
            .removePrefix("🟡")
            .trim()
            .replaceFirstChar { it.uppercaseChar() }

        tvDueBody.text = (notif.text ?: "")
            .trim()
            .removePrefix("🟡 DUE")
            .removePrefix("🟡")
            .trim()

        cardNextDue.setOnClickListener {
            startActivity(
                Intent(this, DetailActivity::class.java).apply {
                    putExtra("COURSE_NAME",   notif.source ?: "")
                    putExtra("COURSE_SYMBOL", "🟡")
                }
            )
        }
    }

    private fun bindRecentActivity(
        notifications: MutableList<NotificationModel>,
        statusMap: MutableMap<Int, String>
    ) {
        if (notifications.isEmpty()) {
            recyclerRecent.visibility    = View.GONE
            layoutRecentEmpty.visibility = View.VISIBLE
            return
        }

        recyclerRecent.visibility    = View.VISIBLE
        layoutRecentEmpty.visibility = View.GONE

        recyclerRecent.adapter = NotificationAdapter(
            notifications = notifications,
            statusMap     = statusMap,
            scope         = lifecycleScope,
            onListChanged = { loadDashboard() }
        )
    }
}