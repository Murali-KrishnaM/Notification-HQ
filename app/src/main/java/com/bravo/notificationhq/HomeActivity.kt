package com.bravo.notificationhq

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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
 *
 * Shows a real-time summary of the student's academic situation:
 *
 *   1. Greeting line (Good morning / afternoon / evening)
 *   2. Stats bar — Urgent count | Due count | Pending (NOT_STARTED) count
 *   3. Next Due card — the most recent 🟡 DUE notification with a tap to open
 *   4. Recent Activity — last 10 notifications across all sources
 *
 * All data is loaded in a single IO coroutine pass on every onResume
 * so the dashboard always reflects the current state of the DB.
 *
 * Bug-fixes applied
 * ─────────────────
 * 1. RACE CONDITION / MID-SCROLL ADAPTER SWAP — loadDashboard() is called on
 *    every onResume.  Without cancellation the previous coroutine can still be
 *    in-flight when the new one finishes, causing two back-to-back
 *    `recyclerRecent.adapter = …` assignments while the user is scrolling,
 *    which crashes RecyclerView or silently corrupts item positions.
 *    Fix: track a [dashboardJob] and cancel it before launching a new one.
 *
 * 2. CONTEXT / MEMORY LEAK — NotificationAdapter now requires a caller-owned
 *    CoroutineScope.  Pass [lifecycleScope] so DB coroutines inside the
 *    adapter are cancelled when the Activity is destroyed.
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
    private lateinit var recyclerRecent:    RecyclerView
    private lateinit var layoutRecentEmpty: LinearLayout

    // BUG FIX: Track the active dashboard load so we can cancel it before
    // starting a new one on every onResume, preventing stale coroutines from
    // swapping the adapter mid-scroll.
    private var dashboardJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        bindViews()
    }

    override fun onResume() {
        super.onResume()
        setGreeting()
        loadDashboard()
    }

    // ─────────────────────────────────────────────────────────────────────
    // BIND VIEWS
    // ─────────────────────────────────────────────────────────────────────

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
        recyclerRecent      = findViewById(R.id.recyclerViewRecent)
        layoutRecentEmpty   = findViewById(R.id.layoutRecentEmpty)

        recyclerRecent.layoutManager = LinearLayoutManager(this)
    }

    // ─────────────────────────────────────────────────────────────────────
    // GREETING
    // ─────────────────────────────────────────────────────────────────────

    private fun setGreeting() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        tvGreeting.text = when {
            hour < 12 -> "Good morning — here's your update"
            hour < 17 -> "Good afternoon — here's your update"
            else      -> "Good evening — here's your update"
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // LOAD DASHBOARD — single IO pass
    // ─────────────────────────────────────────────────────────────────────

    private fun loadDashboard() {
        // BUG FIX: Cancel any in-flight dashboard load before launching a new
        // one.  Without this, rapid onResume calls (e.g. returning from
        // DetailActivity) leave two coroutines racing to call
        // `recyclerRecent.adapter = …`, which can crash RecyclerView or corrupt
        // item positions mid-scroll.
        dashboardJob?.cancel()

        dashboardJob = lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@HomeActivity)

            // Load all notifications in one query, sorted newest first
            val allNotifs = db.notificationDao().getAllNotifications()

            // ── Stats computation ─────────────────────────────────────────
            val urgentCount = allNotifs.count {
                (it.title ?: "").contains("🔴 URGENT") || (it.text ?: "").contains("🔴 URGENT")
            }
            val dueCount = allNotifs.count {
                (it.title ?: "").contains("🟡 DUE") || (it.text ?: "").contains("🟡 DUE")
            }

            // Pending = notifications with no status row (default NOT_STARTED)
            // We count total notifs minus those marked IN_PROGRESS or SUBMITTED
            val allNotifIds = allNotifs.map { it.id }
            val allStatuses = if (allNotifIds.isNotEmpty()) {
                db.taskStatusDao().getStatusesForIds(allNotifIds)
            } else emptyList()

            val resolvedIds = allStatuses
                .filter { it.status != TaskStatusModel.STATUS_NOT_STARTED }
                .map { it.notifId }
                .toSet()

            val pendingCount = allNotifs.count { it.id !in resolvedIds }

            // ── Next due item ─────────────────────────────────────────────
            val nextDueNotif = allNotifs.firstOrNull {
                (it.title ?: "").startsWith("🟡")
            }

            // ── Recent activity — last 10 across all sources ───────────────
            val recentNotifs = allNotifs.take(10).toMutableList()
            val recentIds    = recentNotifs.map { it.id }
            val recentStatuses = if (recentIds.isNotEmpty()) {
                db.taskStatusDao().getStatusesForIds(recentIds)
            } else emptyList()
            val recentStatusMap = recentStatuses
                .associate { it.notifId to it.status }
                .toMutableMap()

            withContext(Dispatchers.Main) {
                bindStats(urgentCount, dueCount, pendingCount)
                bindNextDue(nextDueNotif)
                bindRecentActivity(recentNotifs, recentStatusMap)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // BIND STATS BAR
    // ─────────────────────────────────────────────────────────────────────

    private fun bindStats(urgent: Int, due: Int, pending: Int) {
        tvUrgentCount.text     = urgent.toString()
        tvDueCount.text        = due.toString()
        tvNotStartedCount.text = pending.toString()
    }

    // ─────────────────────────────────────────────────────────────────────
    // BIND NEXT DUE CARD
    // ─────────────────────────────────────────────────────────────────────

    private fun bindNextDue(notif: NotificationModel?) {
        if (notif == null) {
            layoutAllClear.visibility = View.VISIBLE
            layoutDueItem.visibility  = View.GONE
            cardNextDue.setOnClickListener(null)
            return
        }

        layoutAllClear.visibility = View.GONE
        layoutDueItem.visibility  = View.VISIBLE

        // Course chip — use the source (course name) truncated
        tvDueCourseChip.text = (notif.source ?: "Course").take(20)

        // Title — strip the 🟡 prefix for cleaner display
        tvDueTitle.text = (notif.title ?: "")
            .replace("🟡 DUE", "").replace("🟡", "").trim()

        // Body — first two lines of the message
        val cleanBody = (notif.text ?: "")
            .replace("🟡 DUE", "").replace("🟡", "").trim()
        tvDueBody.text = cleanBody

        // Tap → open the course feed for this notification's source
        cardNextDue.setOnClickListener {
            startActivity(
                Intent(this, DetailActivity::class.java).apply {
                    putExtra("COURSE_NAME",   notif.source ?: "")
                    putExtra("COURSE_SYMBOL", "🟡")
                }
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // BIND RECENT ACTIVITY LIST
    // ─────────────────────────────────────────────────────────────────────

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

        // BUG FIX: Pass lifecycleScope so the adapter's internal DB coroutines
        // (status cycle, delete) are tied to this Activity's lifecycle and
        // automatically cancelled on destroy, preventing context leaks.
        recyclerRecent.adapter = NotificationAdapter(
            notifications = notifications,
            statusMap     = statusMap,
            scope         = lifecycleScope,
            onListChanged = {
                // Reload dashboard after a delete from the recent list.
                // dashboardJob?.cancel() inside loadDashboard() prevents the
                // now-stale previous load from racing with this reload.
                loadDashboard()
            }
        )
    }
}