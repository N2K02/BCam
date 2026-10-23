package com.bcam.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/** Dedicated entry point into the same Material 3 interface. */
class UpdatesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BCamSharedUpdater.initialize(this)
        setContent { BCamMaterialScreen(this, startUpdates = true,
            checkNow = intent.getBooleanExtra(SettingsActivity.EXTRA_CHECK_UPDATES, false)) }
    }
    override fun onResume() {
        super.onResume()
        val controller = BCamSharedUpdater.controller
        if (controller.status.value == dev.n2k02.updater.UpdateStatus.Idle) controller.check(manual = false)
    }
}
