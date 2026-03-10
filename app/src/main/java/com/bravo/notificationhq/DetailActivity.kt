package com.bravo.notificationhq

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class DetailActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        // 1. Get the data passed from the Dashboard click
        val subjectName = intent.getStringExtra("SUBJECT_NAME") ?: "Unknown Subject"
        val targetGroup = intent.getStringExtra("TARGET_GROUP") ?: ""

        // 2. Set the huge Title on the screen
        findViewById<TextView>(R.id.tvDetailTitle).text = subjectName

        // 3. THE MAGIC FILTER: Search our MemoryDB for messages matching this exact group!
        val filteredList = MemoryDB.savedNotifications
            .filter { it.source == targetGroup }
            .toMutableList()

        // 4. Show them in the list (reusing the adapter we built yesterday!)
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerViewDetail)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = NotificationAdapter(filteredList)
    }
}