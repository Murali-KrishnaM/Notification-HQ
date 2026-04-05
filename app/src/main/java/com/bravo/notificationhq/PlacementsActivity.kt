package com.bravo.notificationhq

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlacementsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var layoutEmptyState: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_placements)

        recyclerView     = findViewById(R.id.recyclerViewChannels)
        layoutEmptyState = findViewById(R.id.layoutPlacementEmptyState)

        recyclerView.layoutManager = LinearLayoutManager(this)

        loadChannels()

        findViewById<FloatingActionButton>(R.id.fabAddChannel).setOnClickListener {
            showAddChannelDialog()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LOAD CHANNELS FROM DB
    // ─────────────────────────────────────────────────────────────────────────
    private fun loadChannels() {
        CoroutineScope(Dispatchers.IO).launch {
            val db       = AppDatabase.getDatabase(this@PlacementsActivity)
            val channels = db.placementChannelDao().getAllChannels()

            // Badge count — notifications routed to BUCKET_PLACEMENTS
            // Individual channel counts use the channel label as source key
            val countMap = channels.associate { channel ->
                channel.label to db.notificationDao()
                    .getCountForCourse(channel.label)
            }

            withContext(Dispatchers.Main) {
                if (channels.isEmpty()) {
                    recyclerView.visibility     = View.GONE
                    layoutEmptyState.visibility = View.VISIBLE
                } else {
                    recyclerView.visibility     = View.VISIBLE
                    layoutEmptyState.visibility = View.GONE

                    recyclerView.adapter = PlacementChannelAdapter(
                        channels    = channels,
                        notifCounts = countMap,
                        onItemClick = { channel ->
                            openChannelDetail(channel)
                        },
                        onItemLongClick = { channel ->
                            showChannelOptionsSheet(channel)
                        }
                    )
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // OPEN DETAIL — reuse DetailActivity with channel label as course name
    // ─────────────────────────────────────────────────────────────────────────
    private fun openChannelDetail(channel: PlacementChannelModel) {
        val intent = Intent(this, DetailActivity::class.java).apply {
            putExtra("COURSE_NAME",   channel.label)
            putExtra("COURSE_SYMBOL", "🏢")
        }
        startActivity(intent)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LONG PRESS OPTIONS
    // ─────────────────────────────────────────────────────────────────────────
    private fun showChannelOptionsSheet(channel: PlacementChannelModel) {
        val items = arrayOf("✏️  Edit Channel", "🗑️  Delete Channel")
        AlertDialog.Builder(this)
            .setTitle(channel.label)
            .setItems(items) { _, which ->
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
    private fun confirmDeleteChannel(channel: PlacementChannelModel) {
        AlertDialog.Builder(this)
            .setTitle("Delete \"${channel.label}\"?")
            .setMessage("This will remove the channel and all its captured notifications.")
            .setPositiveButton("Delete") { _, _ ->
                CoroutineScope(Dispatchers.IO).launch {
                    val db = AppDatabase.getDatabase(this@PlacementsActivity)
                    db.placementChannelDao().deleteChannel(channel)
                    db.notificationDao().deleteNotificationsForCourse(channel.label)

                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@PlacementsActivity,
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
        val dialogView = LayoutInflater.from(this)
            .inflate(R.layout.dialog_add_channel, null)

        val tilLabel  = dialogView.findViewById<TextInputLayout>(R.id.tilChannelLabel)
        val tilEmails = dialogView.findViewById<TextInputLayout>(R.id.tilPlacementEmails)
        val etLabel   = dialogView.findViewById<TextInputEditText>(R.id.etChannelLabel)
        val etWaGroup = dialogView.findViewById<TextInputEditText>(R.id.etPlacementWaGroup)
        val etEmails  = dialogView.findViewById<TextInputEditText>(R.id.etPlacementEmails)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            tilLabel.error  = null
            tilEmails.error = null

            val label   = etLabel.text.toString().trim()
            val waGroup = etWaGroup.text.toString().trim()
            val emails  = etEmails.text.toString().trim()

            // Validation — label required, and at least one channel
            var isValid = true
            if (label.isEmpty()) {
                tilLabel.error = "Channel label is required"
                isValid = false
            }
            if (waGroup.isEmpty() && emails.isEmpty()) {
                tilEmails.error = "Add at least one WhatsApp group or email address"
                isValid = false
            }
            if (!isValid) return@setOnClickListener

            val newChannel = PlacementChannelModel(
                label             = label,
                whatsappGroupName = waGroup.ifEmpty { null },
                emailAddresses    = emails
            )

            CoroutineScope(Dispatchers.IO).launch {
                val db = AppDatabase.getDatabase(this@PlacementsActivity)
                db.placementChannelDao().insertChannel(newChannel)

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@PlacementsActivity,
                        "✅ \"$label\" channel added!",
                        Toast.LENGTH_SHORT
                    ).show()
                    dialog.dismiss()
                    loadChannels()
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // EDIT CHANNEL DIALOG — pre-filled
    // ─────────────────────────────────────────────────────────────────────────
    private fun showEditChannelDialog(channel: PlacementChannelModel) {
        val dialogView = LayoutInflater.from(this)
            .inflate(R.layout.dialog_add_channel, null)

        val tilLabel  = dialogView.findViewById<TextInputLayout>(R.id.tilChannelLabel)
        val tilEmails = dialogView.findViewById<TextInputLayout>(R.id.tilPlacementEmails)
        val etLabel   = dialogView.findViewById<TextInputEditText>(R.id.etChannelLabel)
        val etWaGroup = dialogView.findViewById<TextInputEditText>(R.id.etPlacementWaGroup)
        val etEmails  = dialogView.findViewById<TextInputEditText>(R.id.etPlacementEmails)

        // Pre-fill
        etLabel.setText(channel.label)
        etWaGroup.setText(channel.whatsappGroupName ?: "")
        etEmails.setText(channel.emailAddresses)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Edit Channel")
            .setView(dialogView)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            tilLabel.error  = null
            tilEmails.error = null

            val newLabel   = etLabel.text.toString().trim()
            val newWaGroup = etWaGroup.text.toString().trim()
            val newEmails  = etEmails.text.toString().trim()

            var isValid = true
            if (newLabel.isEmpty()) {
                tilLabel.error = "Channel label is required"
                isValid = false
            }
            if (newWaGroup.isEmpty() && newEmails.isEmpty()) {
                tilEmails.error = "Add at least one WhatsApp group or email address"
                isValid = false
            }
            if (!isValid) return@setOnClickListener

            val oldLabel = channel.label

            val updatedChannel = channel.copy(
                label             = newLabel,
                whatsappGroupName = newWaGroup.ifEmpty { null },
                emailAddresses    = newEmails
            )

            CoroutineScope(Dispatchers.IO).launch {
                val db = AppDatabase.getDatabase(this@PlacementsActivity)
                db.placementChannelDao().updateChannel(updatedChannel)

                // Re-link notifications if label changed
                if (oldLabel != newLabel) {
                    db.notificationDao().updateCourseNameInNotifications(
                        oldName = oldLabel,
                        newName = newLabel
                    )
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@PlacementsActivity,
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