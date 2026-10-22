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

    private var dialog: AlertDialog? = null
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
        if (dialog?.isShowing != true) {
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
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        super.onDestroy()
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun showDialog() {
        val cur = VideoSettings.load(this)
        val caps = cameraCaps()

        val resChoices = VideoSettings.RESOLUTIONS
            .filter { it <= caps.maxHeight || it == cur.resolution || it == 720 }
            .map { Choice(VideoSettings.resolutionLabel(it), it) }
        val fpsChoices = VideoSettings.FPS_OPTIONS
            .filter { it != 60 || caps.has60 || cur.fps == 60 }
            .map { Choice("$it fps", it) }
        val qualityChoices = listOf(
            Choice("Low · smaller files", 0),
            Choice("Standard", 1),
            Choice("High · bigger files", 2),
        )
        val codecChoices = listOf(
            Choice("H.265 (smaller, if supported)", true),
            Choice("H.264 (plays everywhere)", false),
        )
        val audioChoices = listOf(Choice("On", true), Choice("Off", false))
        val segChoices = VideoSettings.SEGMENTS.map { Choice("$it min", it) }
        val stopChoices = VideoSettings.AUTO_STOP_OPTIONS.map { Choice(if (it == 0) "Unlimited" else "$it min", it) } + Choice("Custom", -1)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(4))
        }
        val estimate = TextView(this).apply {
            setPadding(0, dp(12), 0, 0)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            alpha = 0.75f
        }

        lateinit var read: () -> VideoSettings
        val refresh = {
            val s = read()
            val hevc = s.preferHevc
            val gb = s.estimatedPartBytes(hevc) / 1_000_000_000.0
            val size = if (gb >= 1) String.format(Locale.US, "%.1f GB", gb) else "${(gb * 1000).toInt()} MB"
            estimate.text = "About $size per ${s.segmentMinutes}-minute file" +
                if (hevc) " (H.265; more if the phone falls back to H.264)" else ""
        }

        fun <T> row(label: String, choices: List<Choice<T>>, selected: T): Spinner {
            val spinner = Spinner(this).apply {
                adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, choices)
                setSelection(choices.indexOfFirst { it.value == selected }.coerceAtLeast(0))
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = refresh()
                    override fun onNothingSelected(p: AdapterView<*>?) {}
                }
            }
            val title = TextView(this).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            }
            content.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(spinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            })
            return spinner
        }

        @Suppress("UNCHECKED_CAST")
        fun <T> Spinner.value(): T = (selectedItem as Choice<T>).value

        // Back / front camera. While recording, saving a change continues with the other camera
        // in a new file (the two cameras record at different angles and sizes).
        val curFacing = getSharedPreferences(VideoSettings.PREFS, MODE_PRIVATE)
            .getInt(RecordService.KEY_FACING, CameraCharacteristics.LENS_FACING_BACK)
        val sCam = if (caps.hasBothCameras) {
            row(
                "Camera",
                listOf(
                    Choice("Back camera", CameraCharacteristics.LENS_FACING_BACK),
                    Choice("Front camera", CameraCharacteristics.LENS_FACING_FRONT),
                ),
                curFacing,
            )
        } else {
            null
        }
        val sRes = row("Resolution", resChoices, cur.resolution)
        val sFps = row("Frame rate", fpsChoices, cur.fps)
        val sQuality = row("Quality", qualityChoices, cur.quality)
        val sCodec = row("Codec", codecChoices, cur.preferHevc)
        val sAudio = row("Sound", audioChoices, cur.audio)
        val sSeg = row("New file every", segChoices, cur.segmentMinutes)
        val sStop = row("Auto-stop after", stopChoices, if (cur.autoStopMinutes in VideoSettings.AUTO_STOP_OPTIONS) cur.autoStopMinutes else -1)
        val customMinutes = EditText(this).apply {
            hint = "Custom minutes (1–1440)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(if (cur.autoStopMinutes !in VideoSettings.AUTO_STOP_OPTIONS) cur.autoStopMinutes.toString() else "")
        }
        content.addView(customMinutes)
        val updateCustomVisibility = { customMinutes.visibility = if (sStop.value<Int>() == -1) View.VISIBLE else View.GONE }
        sStop.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { updateCustomVisibility(); refresh() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        updateCustomVisibility()
        content.addView(estimate)
        // Updates card: check, what's new, download and install without leaving this dialog.
        updates?.close()
        val upd = UpdateSection(this)
        updates = upd
        content.addView(upd.view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
            bottomMargin = dp(8)
        })
        if (intent?.getBooleanExtra(EXTRA_CHECK_UPDATES, false) == true) {
            intent.removeExtra(EXTRA_CHECK_UPDATES)
            upd.check()
        }
        // Exit: stop (and save) the recording and close BCam.
        content.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = if (RecordService.isRunning) "⏻  Stop recording & exit BCam" else "⏻  Exit BCam"
            isAllCaps = false
            setTextColor(0xFFD93025.toInt())
            gravity = Gravity.CENTER
            setOnClickListener { exitApp() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(4)
        })

        read = {
            VideoSettings(
                resolution = sRes.value(),
                fps = sFps.value(),
                quality = sQuality.value(),
                preferHevc = sCodec.value(),
                audio = sAudio.value(),
                segmentMinutes = sSeg.value(),
                autoStopMinutes = if (sStop.value<Int>() == -1) (customMinutes.text.toString().toIntOrNull() ?: 15).coerceIn(1, 1440) else sStop.value(),
            )
        }
        refresh()

        val recording = RecordService.isRunning
        dialog = AlertDialog.Builder(this)
            .setTitle("Video settings")
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Reset") { _, _ -> save(VideoSettings(), recording, null) }
            .setPositiveButton("Save") { _, _ -> save(read(), recording, sCam?.value<Int>()) }
            .setOnDismissListener { updates?.close(); finish(); overridePendingTransition(0, 0) }
            .show()
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
