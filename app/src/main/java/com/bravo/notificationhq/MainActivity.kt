package com.bravo.notificationhq

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Find the new RecyclerView ID
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerViewSubjects)

        // 2. The Multi-Channel Dashboard
        val myCourses = listOf(
            SubjectModel("Design Thinking", "Secret Teleport"), // Our WhatsApp tester
            SubjectModel("Google Classroom", "Classroom"),      // New: Classroom catch-all
            SubjectModel("Important Emails", "Gmail")           // New: Gmail catch-all
        )

        // 3. Attach it to the screen
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = SubjectAdapter(myCourses)
    }
}