package com.bravo.notificationhq

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DetailActivity : AppCompatActivity() {

    private lateinit var recyclerView:     RecyclerView
    private lateinit var layoutEmptyState: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        val courseName   = intent.getStringExtra("COURSE_NAME")   ?: "Unknown Subject"
        val courseSymbol = intent.getStringExtra("COURSE_SYMBOL") ?: ""

        val tvTitle    = findViewById<TextView>(R.id.tvDetailTitle)
        val tvSubtitle = findViewById<TextView>(R.id.tvDetailSubtitle)
        recyclerView     = findViewById(R.id.recyclerViewDetail)
        layoutEmptyState = findViewById(R.id.layoutDetailEmptyState)

        tvTitle.text    = courseName
        tvSubtitle.text = if (courseSymbol.isNotEmpty()) courseSymbol else "Notifications"

        recyclerView.layoutManager = LinearLayoutManager(this)

        loadNotifications(courseName, courseSymbol)
    }

    // ─────────────────────────────────────────────────────────────────────
    // LOAD NOTIFICATIONS + STATUS MAP
    // ─────────────────────────────────────────────────────────────────────

    private fun loadNotifications(courseName: String, courseSymbol: String) {
        val tvSubtitle = findViewById<TextView>(R.id.tvDetailSubtitle)

        lifecycleScope.launch(Dispatchers.IO) {
            val db            = AppDatabase.getDatabase(this@DetailActivity)
            val notifications = db.notificationDao()
                .getNotificationsForCourse(courseName)
                .toMutableList()

            // Pre-load all status rows for these notifications in one DB query
            val notifIds   = notifications.map { it.id }
            val statusRows = if (notifIds.isNotEmpty()) {
                db.taskStatusDao().getStatusesForIds(notifIds)
            } else {
                emptyList()
            }

            // Build a mutable map: notifId → status string
            // Notifications with no row default to NOT_STARTED (handled in adapter)
            val statusMap = statusRows
                .associate { it.notifId to it.status }
                .toMutableMap()

            withContext(Dispatchers.Main) {
                renderList(notifications, statusMap, courseSymbol, tvSubtitle)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // RENDER LIST
    // Called on first load and again after a delete via onListChanged.
    // ─────────────────────────────────────────────────────────────────────

    private fun renderList(
        notifications: MutableList<NotificationModel>,
        statusMap: MutableMap<Int, String>,
        courseSymbol: String,
        tvSubtitle: TextView
    ) {
        if (notifications.isEmpty()) {
            recyclerView.visibility     = View.GONE
            layoutEmptyState.visibility = View.VISIBLE
            tvSubtitle.text = if (courseSymbol.isNotEmpty()) courseSymbol else "Notifications"
        } else {
            recyclerView.visibility     = View.VISIBLE
            layoutEmptyState.visibility = View.GONE

            val count = notifications.size
            tvSubtitle.text = "$count notification${if (count == 1) "" else "s"}" +
                    if (courseSymbol.isNotEmpty()) " · $courseSymbol" else ""

            recyclerView.adapter = NotificationAdapter(
                notifications = notifications,
                statusMap     = statusMap,
                onListChanged = {
                    // Called by adapter after a delete — check if list is now empty
                    if (notifications.isEmpty()) {
                        recyclerView.visibility     = View.GONE
                        layoutEmptyState.visibility = View.VISIBLE
                        tvSubtitle.text = if (courseSymbol.isNotEmpty()) courseSymbol else "Notifications"
                    } else {
                        val newCount = notifications.size
                        tvSubtitle.text = "$newCount notification${if (newCount == 1) "" else "s"}" +
                                if (courseSymbol.isNotEmpty()) " · $courseSymbol" else ""
                    }
                }
            )
        }
    }
}