package com.bravo.notificationhq

import android.os.Bundle
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

        // 1. Get the data passed from the Dashboard click
        val subjectName = intent.getStringExtra("SUBJECT_NAME") ?: "Unknown Subject"
        val targetGroup = intent.getStringExtra("TARGET_GROUP") ?: ""

        // 2. Set the huge Title on the screen
        findViewById<TextView>(R.id.tvDetailTitle).text = subjectName

        // Get the Database instance
        val db = AppDatabase.getDatabase(this)

        //  Open a background thread to fetch data
        CoroutineScope(Dispatchers.IO).launch {

            // Query the database for this specific subject
            val filteredList = db.notificationDao().getNotificationsBySource(targetGroup).toMutableList()

            //Switch back to the Main Thread to update the UI
            withContext(Dispatchers.Main) {
                val recyclerView = findViewById<RecyclerView>(R.id.recyclerViewDetail)
                recyclerView.layoutManager = LinearLayoutManager(this@DetailActivity)
                recyclerView.adapter = NotificationAdapter(filteredList)
            }
        }
    }
}