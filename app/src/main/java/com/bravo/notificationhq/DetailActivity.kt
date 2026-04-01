package com.bravo.notificationhq

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DetailActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        val courseName   = intent.getStringExtra("COURSE_NAME") ?: "Unknown Subject"
        val courseSymbol = intent.getStringExtra("COURSE_SYMBOL") ?: ""

        val tvTitle            = findViewById<TextView>(R.id.tvDetailTitle)
        val tvSubtitle         = findViewById<TextView>(R.id.tvDetailSubtitle)
        val recyclerView       = findViewById<RecyclerView>(R.id.recyclerViewDetail)
        val layoutEmptyState   = findViewById<LinearLayout>(R.id.layoutDetailEmptyState)

        // Set the header immediately — don't wait for DB
        tvTitle.text    = courseName
        tvSubtitle.text = if (courseSymbol.isNotEmpty()) courseSymbol else "Notifications"

        recyclerView.layoutManager = LinearLayoutManager(this)

        CoroutineScope(Dispatchers.IO).launch {
            val db            = AppDatabase.getDatabase(this@DetailActivity)
            val notifications = db.notificationDao().getNotificationsForCourse(courseName)

            withContext(Dispatchers.Main) {
                if (notifications.isEmpty()) {
                    recyclerView.visibility    = View.GONE
                    layoutEmptyState.visibility = View.VISIBLE
                } else {
                    recyclerView.visibility    = View.VISIBLE
                    layoutEmptyState.visibility = View.GONE

                    val count = notifications.size
                    tvSubtitle.text = "$count notification${if (count == 1) "" else "s"}" +
                            if (courseSymbol.isNotEmpty()) " · $courseSymbol" else ""

                    recyclerView.adapter = NotificationAdapter(notifications)
                }
            }
        }
    }
}
