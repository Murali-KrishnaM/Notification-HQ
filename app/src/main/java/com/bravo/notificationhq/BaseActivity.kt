package com.bravo.notificationhq

import android.content.Intent
import android.view.View
import androidx.appcompat.app.AppCompatActivity

abstract class BaseActivity : AppCompatActivity() {

    // onStart is called after the specific Activity's layout is drawn
    override fun onStart() {
        super.onStart()
        setupNavigationClicks()
    }

    private fun setupNavigationClicks() {
        // Find the views and attach click listeners
        findViewById<View>(R.id.navAcademics)?.setOnClickListener { navigateTo(AcademicsActivity::class.java) }
        findViewById<View>(R.id.navPlacements)?.setOnClickListener { navigateTo(PlacementsActivity::class.java) }
        findViewById<View>(R.id.navHostel)?.setOnClickListener { navigateTo(HostelActivity::class.java) }
        findViewById<View>(R.id.navNptel)?.setOnClickListener { navigateTo(NptelActivity::class.java) }
        findViewById<View>(R.id.fabHome)?.setOnClickListener { navigateTo(HomeActivity::class.java) }
    }

    private fun navigateTo(clazz: Class<*>) {
        // PREVENT WEIRD RELOADS: If the user clicks the tab they are already on, do nothing!
        if (this::class.java == clazz) return

        val intent = Intent(this, clazz)
        // This flag pulls the existing screen forward instead of opening a duplicate
        intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        startActivity(intent)
    }
}