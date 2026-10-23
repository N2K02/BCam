package com.bcam.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.text.InputType
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * Video settings dialog, opened from the ⚙ Settings button in the recording notification.
 * Transparent activity that only shows a dialog over whatever is on screen.
 * Saving while recording finishes the current part and starts a new one with the new settings.
 */
class SettingsActivity : Activity() {

    private var updates: UpdateSection? = null

    companion object {
        /** Open with the update check already running (from the "Update available" notification). */
        const val EXTRA_CHECK_UPDATES = "check_updates"
    }

    private class Choice<T>(val label: String, val value: T) {
        override fun toString() = label
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showDialog()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (updates == null) {
            showDialog()
        } else if (intent.getBooleanExtra(EXTRA_CHECK_UPDATES, false)) {
            intent.removeExtra(EXTRA_CHECK_UPDATES)
            updates?.check()
        }
    }

    override fun onResume() {
        super.onResume()
        updates?.onResume()
    }

    override fun onDestroy() {
        updates?.close()
        super.onDestroy()
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun showDialog() {
        val caps = cameraCaps()
        val updater = UpdateSection(this)
        updates?.close()
        updates = updater
        val initialFacing = getSharedPreferences(VideoSettings.PREFS, MODE_PRIVATE)
            .getInt(RecordService.KEY_FACING, CameraCharacteristics.LENS_FACING_BACK)
        androidx.activity.compose.setContent {
            BcamSettingsDialog(
                initial = VideoSettings.load(this),
                cameraOptions = caps.hasBothCameras,
                maximumHeight = caps.maxHeight,
                supports60Fps = caps.has60,
                initialFacing = initialFacing,
                updateSection = updater,
                onDismiss = { updates?.close(); finish(); overridePendingTransition(0, 0) },
                onSave = { settings, facing ->
                    save(settings, RecordService.isRunning, facing)
                    updates?.close()
                    finish()
                    overridePendingTransition(0, 0)
                },
                onExit = ::exitApp,
            )
        }
        if (intent?.getBooleanExtra(EXTRA_CHECK_UPDATES, false) == true) {
            intent.removeExtra(EXTRA_CHECK_UPDATES)
            updater.check()
        }
    }

    private fun save(s: VideoSettings, recording: Boolean, facing: Int?) {
        val changed = s != VideoSettings.load(this)
        s.save(this)
        val prefs = getSharedPreferences(VideoSettings.PREFS, MODE_PRIVATE)
        val oldFacing = prefs.getInt(RecordService.KEY_FACING, CameraCharacteristics.LENS_FACING_BACK)
        val switched = facing != null && facing != oldFacing
        if (switched) prefs.edit().putInt(RecordService.KEY_FACING, facing!!).apply()
        val camName = if (facing == CameraCharacteristics.LENS_FACING_FRONT) "front" else "back"
        when {
            !changed && !switched -> {}
            recording && RecordService.isRunning -> {
                startService(Intent(this, RecordService::class.java).setAction(RecordService.ACTION_APPLY_SETTINGS))
                val msg = if (switched) {
                    "Switching to the $camName camera — the video so far is saved and recording continues in a new file"
                } else {
                    "Saved — the video so far is kept and recording continues in a new file with the new settings"
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
            else -> Toast.makeText(
                this,
                if (switched) "Saved — the $camName camera is used next time you record" else "Saved — used next time you record",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    /** Exit BCam: stop and save the recording, close the dialog and remove BCam from recent apps. */
    private fun exitApp() {
        if (RecordService.isRunning) {
            startService(Intent(this, RecordService::class.java).setAction(RecordService.ACTION_STOP))
        }
        getSystemService(android.app.NotificationManager::class.java).cancel(AppUpdater.NOTIF_ID)
        updates?.close()
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        finishAndRemoveTask()
        overridePendingTransition(0, 0)
    }

    private class Caps(val maxHeight: Int, val has60: Boolean, val hasBothCameras: Boolean)

    /** What the phone's cameras can record, so the dialog only offers options that work. */
    private fun cameraCaps(): Caps = runCatching {
        val cm = getSystemService(CameraManager::class.java)
        var maxH = 0
        var has60 = false
        val facings = HashSet<Int>()
        for (id in cm.cameraIdList) {
            val c = cm.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.LENS_FACING)?.let { facings += it }
            val sizes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(MediaRecorder::class.java).orEmpty()
            sizes.forEach { maxH = maxOf(maxH, minOf(it.width, it.height)) }
            val ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
            if (ranges.any { it.upper >= 60 }) has60 = true
        }
        Caps(
            if (maxH == 0) 1080 else maxH, has60,
            CameraCharacteristics.LENS_FACING_BACK in facings && CameraCharacteristics.LENS_FACING_FRONT in facings,
        )
    }.getOrDefault(Caps(1080, false, false))
}
