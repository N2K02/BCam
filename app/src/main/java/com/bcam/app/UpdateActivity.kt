package com.bcam.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.File

/**
 * Update dialog (transparent screen, like the settings dialog): checks GitHub, shows what's new,
 * downloads with progress, verifies and installs the new APK.
 * Opened from the "Update available" notification or "Check for updates" in Settings.
 */
class UpdateActivity : Activity() {

    private var dialog: AlertDialog? = null
    private var switching = false
    private var cancelled = false
    private var release: AppUpdater.Release? = null
    private var file: File? = null
    private var waitingForPermission = false
    private var installerOpened = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getSystemService(android.app.NotificationManager::class.java).cancel(AppUpdater.NOTIF_ID)
        check()
    }

    override fun onResume() {
        super.onResume()
        // Back from "Install unknown apps": continue if allowed now.
        if (waitingForPermission && AppUpdater.canInstall(this)) {
            waitingForPermission = false
            file?.let { install(it) }
        }
    }

    override fun onDestroy() {
        cancelled = true
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        super.onDestroy()
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun show(b: AlertDialog.Builder) {
        switching = true
        dialog?.dismiss()
        switching = false
        dialog = b.setOnDismissListener { if (!switching && !waitingForPermission && !installerOpened) close() }.show()
    }

    private fun close() {
        finish()
        overridePendingTransition(0, 0)
    }

    private fun progressView(text: String, bar: ProgressBar): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(16), dp(24), dp(8))
        addView(TextView(this@UpdateActivity).apply { this.text = text })
        addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
    }

    private fun busy(title: String, text: String) {
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        show(AlertDialog.Builder(this).setTitle(title).setView(progressView(text, bar)).setNegativeButton("Close", null))
    }

    // ---------------------------------------------------------------- steps

    private fun check() {
        busy("Updates", "Checking GitHub for a newer BCam…")
        Thread {
            val result = runCatching { AppUpdater.latestRelease() }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val r = result.getOrNull()
                when {
                    result.isFailure -> error("Couldn't reach GitHub. Check your connection.", null)
                    r == null || !AppUpdater.isNewer(this, r) -> show(
                        AlertDialog.Builder(this)
                            .setTitle("BCam is up to date")
                            .setMessage("You have the latest version, ${AppUpdater.installedVersionName(this)}.")
                            .setPositiveButton("OK", null),
                    )
                    else -> offer(r)
                }
            }
        }.start()
    }

    private fun offer(r: AppUpdater.Release) {
        release = r
        val notes = r.notes.joinToString("\n") { "•  $it" }.ifEmpty { "Bug fixes and improvements." }
        val warning = if (RecordService.isRunning) "\n\nBCam is recording now. Updating stops the recording first (the video so far is saved)." else ""
        show(
            AlertDialog.Builder(this)
                .setTitle("BCam ${r.versionName} is available")
                .setMessage("You have ${AppUpdater.installedVersionName(this)}.\n\nWhat's new:\n$notes$warning")
                .setPositiveButton("Update now") { _, _ -> download(r) }
                .setNeutralButton("Skip this version") { _, _ -> AppUpdater.skip(this, r) }
                .setNegativeButton("Later", null),
        )
    }

    private fun download(r: AppUpdater.Release) {
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true; max = 100 }
        cancelled = false
        show(
            AlertDialog.Builder(this)
                .setTitle("Downloading BCam ${r.versionName}")
                .setView(progressView("Downloading the update from GitHub…", bar))
                .setNegativeButton("Cancel") { _, _ -> cancelled = true },
        )
        Thread {
            val result = runCatching {
                val f = AppUpdater.download(this, r, progress = { p ->
                    runOnUiThread {
                        if (p == null) bar.isIndeterminate = true else { bar.isIndeterminate = false; bar.progress = (p * 100).toInt() }
                    }
                }, cancelled = { cancelled })
                f to AppUpdater.verify(this, f, r)
            }
            runOnUiThread {
                if (isFinishing || cancelled) return@runOnUiThread
                val (f, problem) = result.getOrElse {
                    error("Download failed. Check your connection and try again.", r)
                    return@runOnUiThread
                }
                if (problem != null) {
                    f.delete()
                    error(problem, r)
                    return@runOnUiThread
                }
                file = f
                if (AppUpdater.canInstall(this)) install(f) else askPermission()
            }
        }.start()
    }

    private fun askPermission() {
        show(
            AlertDialog.Builder(this)
                .setTitle("One more step")
                .setMessage("Android needs your OK to let BCam install its own updates. Turn on \"Allow from this source\", then come back.")
                .setPositiveButton("Open settings") { _, _ ->
                    // Keeps this screen open while the user is in Settings; onResume continues.
                    waitingForPermission = true
                    runCatching {
                        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    }.onFailure { waitingForPermission = false }
                }
                .setNegativeButton("Later", null),
        )
    }

    private fun install(f: File) {
        val wasRecording = RecordService.isRunning
        if (wasRecording) {
            busy("Saving the recording", "Stopping the recording so the video is saved before the update…")
            startService(Intent(this, RecordService::class.java).setAction(RecordService.ACTION_STOP))
            // Give the recorder a moment to finish writing the current video.
            window.decorView.postDelayed({ if (!isFinishing) openInstaller(f) }, 2000)
        } else {
            openInstaller(f)
        }
    }

    /** Opens Android's installer; when the user comes back without installing, offers it again. */
    private fun openInstaller(f: File) {
        val ok = runCatching { startActivity(AppUpdater.installIntent(this, f)) }.isSuccess
        if (!ok) {
            error("Couldn't open Android's installer.", release)
            return
        }
        installerOpened = true
        readyDialog(f)
    }

    private fun readyDialog(f: File) {
        show(
            AlertDialog.Builder(this)
                .setTitle("BCam ${release?.versionName.orEmpty()} is ready to install")
                .setMessage(
                    "Android's installer opens. If Play Protect asks, you can scan the app or choose to install anyway. " +
                        "BCam closes and starts on the new version.",
                )
                .setPositiveButton("Install") { _, _ -> openInstaller(f) }
                .setNegativeButton("Later") { _, _ -> installerOpened = false },
        )
    }

    private fun error(text: String, r: AppUpdater.Release?) {
        val b = AlertDialog.Builder(this).setTitle("Update problem").setMessage(text)
            .setNeutralButton("Release page") { _, _ ->
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r?.pageUrl ?: "https://github.com/${AppUpdater.REPO}/releases/latest")))
                }
            }
            .setNegativeButton("Close", null)
        if (r != null) b.setPositiveButton("Try again") { _, _ -> download(r) }
        show(b)
    }
}
