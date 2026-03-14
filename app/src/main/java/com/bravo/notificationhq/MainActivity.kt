package com.bravo.notificationhq

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: SubjectAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.recyclerViewSubjects)
        recyclerView.layoutManager = LinearLayoutManager(this)

        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)

        // 1. Define our Segregated Streams
        val academicsList = listOf(
            SubjectModel("Design Thinking", "Secret Teleport"),
            SubjectModel("Google Classroom", "Classroom"),
            SubjectModel("Important Emails", "Gmail")
        )

        val placementsList = listOf(
            SubjectModel("Placement Cell Updates", "Placement Cell"),
            SubjectModel("Interview Schedules", "HR Updates")
        )

        val clubsList = listOf(
            SubjectModel("Coding Club", "Dev Core Team"),
            SubjectModel("Photography Club", "Shutterbugs")
        )

        // 2. Set the default view (Academics) when the app opens
        adapter = SubjectAdapter(academicsList)
        recyclerView.adapter = adapter

        // 3. Listen for Tab Clicks to swap the data
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> updateList(academicsList)   // Academics Tab
                    1 -> updateList(placementsList)  // Placements Tab
                    2 -> updateList(clubsList)       // Clubs Tab
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    // Helper function to smoothly swap the data in the list
    private fun updateList(newList: List<SubjectModel>) {
        adapter = SubjectAdapter(newList)
        recyclerView.adapter = adapter
    }
}