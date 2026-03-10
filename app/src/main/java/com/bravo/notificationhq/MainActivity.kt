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

        // 2. Create our Hardcoded Demo Data (MVP Magic!)
        // Notice how we link the course to the WhatsApp group name here.
        val myCourses = listOf(
            SubjectModel("Design Thinking", "Secret Teleport"), // Your test group!
            SubjectModel("Computer Networks", "CN Class 2026"),
            SubjectModel("Software Engineering", "SE Project Team")
        )

        // 3. Attach it to the screen
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = SubjectAdapter(myCourses)
    }
}