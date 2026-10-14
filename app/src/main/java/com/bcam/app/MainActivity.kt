package com.bcam.app

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible launcher screen. Tapping the app icon:
 *  - asks for permissions the first time,
 *  - starts background recording (or tells you it is already recording),
 *  - then closes itself straight away.
 * Recording is stopped / camera switched from the notification.
 */
class MainActivity : Activity() {

    private val required: Array<String>
        get() = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startAndClose() else requestPermissions(missing.toTypedArray(), REQ)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "No microphone permission: recording without sound", Toast.LENGTH_LONG).show()
            }
            startAndClose()
        } else {
            Toast.makeText(this, "BCam needs Camera permission to record", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun startAndClose() {
        // Looks for a newer BCam on GitHub (at most every 12 h); shows a notification if there is one.
        AppUpdater.checkInBackgroundIfDue(this)
        if (RecordService.isRunning) {
            if (getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
                Toast.makeText(this, "BCam is already recording — use the notification to stop", Toast.LENGTH_SHORT).show()
            } else {
                // Notifications are blocked, so there is no Stop button: tapping the icon stops instead.
                startService(Intent(this, RecordService::class.java).setAction(RecordService.ACTION_STOP))
            }
        } else {
            val i = Intent(this, RecordService::class.java).setAction(RecordService.ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            Toast.makeText(this, "BCam recording started", Toast.LENGTH_SHORT).show()
        }
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val REQ = 1
    }
}
