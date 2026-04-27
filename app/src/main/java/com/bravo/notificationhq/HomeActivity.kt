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
 *   2. Stats bar — Urgent count | Due count | Pending count
 *   3. Next Due card — the most recent 🟡 DUE notification with a tap to open
 *   4. Recent Activity — last 10 notifications across all sources
 *
 * ── Pending count logic ───────────────────────────────────────────────────
 *
 * A notification is counted as PENDING (exactly once) when:
 *   • It is DUE-tagged AND status ≠ SUBMITTED      → student still owes it
 *   • It is NOT DUE-tagged AND status = IN_PROGRESS → student started it
 *
 * This avoids double-counting a DUE-tagged notification that is also
 * IN_PROGRESS (DUE already contributes 1; promoting it to IN_PROGRESS must
 * not add another point).  Setting either kind to SUBMITTED removes it from
 * the count entirely.
 *
 * ── Bug fixes applied ─────────────────────────────────────────────────────
 * 1. RACE CONDITION / MID-SCROLL ADAPTER SWAP — dashboardJob is cancelled
 *    before every new launch so stale coroutines never race to swap the
 *    adapter while the user is scrolling.
 *
 * 2. CONTEXT / MEMORY LEAK — lifecycleScope is passed to NotificationAdapter
 *    so its internal DB coroutines are cancelled on Activity destroy.
 *
 * 3. NEXT DUE STRING STRIP — only the emoji+keyword prefix is stripped;
 *    date context embedded in the title is now preserved.
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

    // BUG FIX 1: Track the active dashboard load so we can cancel it before
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
        // BUG FIX 1: Cancel any in-flight dashboard load before launching a
        // new one. Without this, rapid onResume calls leave two coroutines
        // racing to call `recyclerRecent.adapter = …`.
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

            // ── Pending count ─────────────────────────────────────────────
            //
            // Rule (no double-counting):
            //   1. DUE-tagged  AND  status ≠ SUBMITTED   → pending (1×)
            //   2. Non-DUE     AND  status = IN_PROGRESS  → pending (1×)
            //
            // A DUE notification that is also IN_PROGRESS is already covered
            // by rule 1, so rule 2 explicitly excludes DUE-tagged items.
            // Setting any item to SUBMITTED removes it from pending entirely.
            //
            val allNotifIds = allNotifs.map { it.id }
            val statusRows  = if (allNotifIds.isNotEmpty()) {
                db.taskStatusDao().getStatusesForIds(allNotifIds)
            } else emptyList()

            // Build a fast lookup: notifId → status string
            val statusByNotifId = statusRows.associate { it.notifId to it.status }

            val pendingCount = allNotifs.count { notif ->
                val isDue = (notif.title ?: "").contains("🟡 DUE") ||
                        (notif.text  ?: "").contains("🟡 DUE")
                val status = statusByNotifId[notif.id] ?: TaskStatusModel.STATUS_NOT_STARTED

                if (isDue) {
                    // DUE items are pending until the student submits them
                    status != TaskStatusModel.STATUS_SUBMITTED
                } else {
                    // Non-DUE items only become pending once work has started
                    status == TaskStatusModel.STATUS_IN_PROGRESS
                }
            }

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

        // BUG FIX 3: Strip only the leading "🟡 DUE" keyword token — do NOT
        // blindly wipe every occurrence of "🟡" or "DUE" in the string, as
        // those characters may appear inside an embedded date like
        // "🟡 DUE — Submit by 🟡 Jan 12".  A simple removePrefix on the
        // trimmed string is sufficient and preserves date context.
        tvDueTitle.text = (notif.title ?: "")
            .trim()
            .removePrefix("🟡 DUE")
            .removePrefix("🟡")
            .trim()
            .replaceFirstChar { it.uppercaseChar() }   // re-capitalise if needed

        // Body — same safe strip: only the leading label, not mid-string emojis
        tvDueBody.text = (notif.text ?: "")
            .trim()
            .removePrefix("🟡 DUE")
            .removePrefix("🟡")
            .trim()

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

        // BUG FIX 2: Pass lifecycleScope so the adapter's internal DB
        // coroutines (status cycle, delete) are tied to this Activity's
        // lifecycle and automatically cancelled on destroy.
        recyclerRecent.adapter = NotificationAdapter(
            notifications = notifications,
            statusMap     = statusMap,
            scope         = lifecycleScope,
            onListChanged = {
                // Reload dashboard after a delete from the recent list.
                loadDashboard()
            }
        )
    }
}