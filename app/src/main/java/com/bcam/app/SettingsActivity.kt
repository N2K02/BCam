package com.bcam.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/** Material 3 settings and updates, shared with UpdatesActivity. */
class SettingsActivity : ComponentActivity() {
    companion object { const val EXTRA_CHECK_UPDATES = "check_updates" }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BCamSharedUpdater.initialize(this)
        setContent { BCamMaterialScreen(this, startUpdates = intent.getBooleanExtra(EXTRA_CHECK_UPDATES, false),
            checkNow = intent.getBooleanExtra(EXTRA_CHECK_UPDATES, false)) }
    }
    override fun onResume() {
        super.onResume()
        BCamSharedUpdater.controller.onHostResume(this, BCamSharedUpdater.guard)
    }
}
