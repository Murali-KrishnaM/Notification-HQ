package com.bravo.notificationhq

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NptelActivity : BaseActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var layoutEmptyState: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nptel)

        recyclerView     = findViewById(R.id.recyclerViewNptelChannels)
        layoutEmptyState = findViewById(R.id.layoutNptelEmptyState)

        recyclerView.layoutManager = LinearLayoutManager(this)

        loadChannels()

        findViewById<FloatingActionButton>(R.id.fabAddNptelChannel).setOnClickListener {
            showAddChannelDialog()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LOAD CHANNELS
    // ─────────────────────────────────────────────────────────────────────────
    private fun loadChannels() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db       = AppDatabase.getDatabase(this@NptelActivity)
            val channels = db.nptelChannelDao().getAllChannels()

            val countMap = channels.associate { channel ->
                channel.label to db.notificationDao().getCountForCourse(channel.label)
            }

            withContext(Dispatchers.Main) {
                if (channels.isEmpty()) {
                    recyclerView.visibility     = View.GONE
                    layoutEmptyState.visibility = View.VISIBLE
                } else {
                    recyclerView.visibility     = View.VISIBLE
                    layoutEmptyState.visibility = View.GONE

                    recyclerView.adapter = NptelChannelAdapter(
                        channels        = channels,
                        notifCounts     = countMap,
                        onItemClick     = { channel -> openChannelDetail(channel) },
                        onItemLongClick = { channel -> showChannelOptions(channel) }
                    )
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // OPEN DETAIL
    // ─────────────────────────────────────────────────────────────────────────
    private fun openChannelDetail(channel: NptelChannelModel) {
        startActivity(Intent(this, DetailActivity::class.java).apply {
            putExtra("COURSE_NAME",   channel.label)
            putExtra("COURSE_SYMBOL", "📚")
        })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LONG PRESS OPTIONS
    // ─────────────────────────────────────────────────────────────────────────
    private fun showChannelOptions(channel: NptelChannelModel) {
        AlertDialog.Builder(this)
            .setTitle(channel.label)
            .setItems(arrayOf("✏️  Edit Course", "🗑️  Delete Course")) { _, which ->
                when (which) {
                    0 -> showEditChannelDialog(channel)
                    1 -> confirmDeleteChannel(channel)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONFIRM DELETE
    // ─────────────────────────────────────────────────────────────────────────
    private fun confirmDeleteChannel(channel: NptelChannelModel) {
        AlertDialog.Builder(this)
            .setTitle("Delete \"${channel.label}\"?")
            .setMessage("This will remove the course and all its captured notifications.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val db = AppDatabase.getDatabase(this@NptelActivity)
                    db.nptelChannelDao().deleteChannel(channel)
                    db.notificationDao().deleteNotificationsForCourse(channel.label)

                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@NptelActivity,
                            "🗑️ \"${channel.label}\" deleted",
                            Toast.LENGTH_SHORT
                        ).show()
                        loadChannels()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ADD CHANNEL DIALOG
    // ─────────────────────────────────────────────────────────────────────────
    private fun showAddChannelDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_nptel_channel, null)
        val tilLabel   = dialogView.findViewById<TextInputLayout>(R.id.tilNptelLabel)
        val tilEmails  = dialogView.findViewById<TextInputLayout>(R.id.tilNptelEmails)
        val etLabel    = dialogView.findViewById<TextInputEditText>(R.id.etNptelLabel)
        val etEmails   = dialogView.findViewById<TextInputEditText>(R.id.etNptelEmails)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            tilLabel.error  = null
            tilEmails.error = null

            val label  = etLabel.text.toString().trim()
            val emails = etEmails.text.toString().trim()

            var isValid = true
            if (label.isEmpty())  { tilLabel.error  = "Course name is required"; isValid = false }
            if (emails.isEmpty()) { tilEmails.error = "At least one sender email is required"; isValid = false }
            if (!isValid) return@setOnClickListener

            lifecycleScope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(this@NptelActivity)
                db.nptelChannelDao().insertChannel(
                    NptelChannelModel(label = label, emailAddresses = emails)
                )
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@NptelActivity,
                        "✅ \"$label\" added!",
                        Toast.LENGTH_SHORT
                    ).show()
                    dialog.dismiss()
                    loadChannels()
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // EDIT CHANNEL DIALOG
    // ─────────────────────────────────────────────────────────────────────────
    private fun showEditChannelDialog(channel: NptelChannelModel) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_nptel_channel, null)
        val tilLabel   = dialogView.findViewById<TextInputLayout>(R.id.tilNptelLabel)
        val tilEmails  = dialogView.findViewById<TextInputLayout>(R.id.tilNptelEmails)
        val etLabel    = dialogView.findViewById<TextInputEditText>(R.id.etNptelLabel)
        val etEmails   = dialogView.findViewById<TextInputEditText>(R.id.etNptelEmails)

        etLabel.setText(channel.label)
        etEmails.setText(channel.emailAddresses)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Edit NPTEL Course")
            .setView(dialogView)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            tilLabel.error  = null
            tilEmails.error = null

            val newLabel  = etLabel.text.toString().trim()
            val newEmails = etEmails.text.toString().trim()

            var isValid = true
            if (newLabel.isEmpty())  { tilLabel.error  = "Course name is required"; isValid = false }
            if (newEmails.isEmpty()) { tilEmails.error = "At least one email is required"; isValid = false }
            if (!isValid) return@setOnClickListener

            val oldLabel = channel.label

            lifecycleScope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(this@NptelActivity)
                db.nptelChannelDao().updateChannel(
                    channel.copy(label = newLabel, emailAddresses = newEmails)
                )
                if (oldLabel != newLabel) {
                    db.notificationDao().updateCourseNameInNotifications(
                        oldName = oldLabel,
                        newName = newLabel
                    )
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@NptelActivity,
                        "✅ \"$newLabel\" updated!",
                        Toast.LENGTH_SHORT
                    ).show()
                    dialog.dismiss()
                    loadChannels()
                }
            }
        }
    }
}