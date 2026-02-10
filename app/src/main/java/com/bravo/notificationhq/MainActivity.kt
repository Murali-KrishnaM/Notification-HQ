package com.bravo.notificationhq

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager // NOTE: If this is red, we need to add a dependency!
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: NotificationAdapter
    private val dataList = mutableListOf<NotificationModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Find the RecyclerView from your XML
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)

        // 2. Set up the Adapter
        adapter = NotificationAdapter(dataList)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        // 3. Register a "Receiver" to listen for data from the Service
        LocalBroadcastManager.getInstance(this).registerReceiver(
            onNotificationReceived,
            IntentFilter("Msg_Received")
        )
    }

    // This function runs whenever the Service catches a notification
    private val onNotificationReceived = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val title = intent?.getStringExtra("title") ?: "Unknown"
            val text = intent?.getStringExtra("text") ?: ""
            val source = intent?.getStringExtra("source") ?: ""

            // Add it to the screen!
            val newItem = NotificationModel(title, text, source)
            adapter.addNotification(newItem)
        }
    }
}