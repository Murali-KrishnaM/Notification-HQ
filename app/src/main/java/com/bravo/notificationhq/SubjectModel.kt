package com.bravo.notificationhq

// SubjectModel is no longer used by MainActivity or SubjectAdapter.
// Both now work directly with CourseModel.
// Kept here only to avoid any potential reference errors during migration.
// Safe to delete once you've confirmed the build is clean.
data class SubjectModel(
    val name: String,
    val targetGroup: String
)
