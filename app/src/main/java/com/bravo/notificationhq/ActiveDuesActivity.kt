package com.bravo.notificationhq

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class ActiveDuesActivity : BaseActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var layoutEmptyState: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_active_dues)

        recyclerView = findViewById(R.id.recyclerViewActiveDues)
        layoutEmptyState = findViewById(R.id.layoutDuesEmptyState)

        recyclerView.layoutManager = LinearLayoutManager(this)
    }

    override fun onResume() {
        super.onResume()
        loadActiveDues()
    }

    private fun loadActiveDues() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@ActiveDuesActivity)
            val allNotifs = db.notificationDao().getAllNotifications()

            val allIds = allNotifs.map { it.id }
            val statusRows = if (allIds.isNotEmpty()) db.taskStatusDao().getStatusesForIds(allIds) else emptyList()
            val statusByNotifId = statusRows.associate { it.notifId to it.status }

            // 1. Filter: Keep only DUE items that are NOT submitted
            val activeDues = allNotifs.filter { notif ->
                val isDue = (notif.title ?: "").contains("🟡 DUE") || (notif.text ?: "").contains("🟡 DUE")
                val status = statusByNotifId[notif.id] ?: TaskStatusModel.STATUS_NOT_STARTED
                isDue && status != TaskStatusModel.STATUS_SUBMITTED
            }

            // 2. Sort: Chronological order (nearest deadline first)
            val sortedDues = activeDues.sortedBy { notif ->
                extractDateEpoch(notif.title ?: "", notif.timestamp)
            }.toMutableList()

            // 3. Status map for the adapter
            val dueIds = sortedDues.map { it.id }
            val dueStatuses = if (dueIds.isNotEmpty()) db.taskStatusDao().getStatusesForIds(dueIds) else emptyList()
            val statusMap = dueStatuses.associate { it.notifId to it.status }.toMutableMap()

            withContext(Dispatchers.Main) {
                if (sortedDues.isEmpty()) {
                    recyclerView.visibility = View.GONE
                    layoutEmptyState.visibility = View.VISIBLE
                } else {
                    recyclerView.visibility = View.VISIBLE
                    layoutEmptyState.visibility = View.GONE

                    recyclerView.adapter = NotificationAdapter(
                        notifications = sortedDues,
                        statusMap = statusMap,
                        scope = lifecycleScope,
                        onListChanged = { loadActiveDues() } // Reload if they mark one as submitted!
                    )
                }
            }
        }
    }

    // Reuse the exact same Smart Date Reader logic here
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
        } catch (e: Exception) { }
        return fallbackTimestamp
    }
}