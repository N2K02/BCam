package com.bcam.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Switch
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.File

/**
 * The "Updates" card inside the Settings dialog: version, Check for updates, what's new,
 * download progress and Install, all in place (the dialog stays open).
 * Downloads run on a background thread; results are shown only while the dialog is open.
 */
class UpdateSection(private val activity: Activity) {

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), activity.resources.displayMetrics).toInt()

    private val title = TextView(activity).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTypeface(typeface, Typeface.BOLD)
    }
    private val status = TextView(activity).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        alpha = 0.8f
        setPadding(0, dp(2), 0, 0)
    }
    private val notes = TextView(activity).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(0, dp(6), 0, 0)
        visibility = View.GONE
    }
    private val diagnosticsText = TextView(activity).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(0, dp(8), 0, dp(4))
        visibility = View.GONE
    }
    private val copyDiagnosticsButton = Button(activity, null, android.R.attr.borderlessButtonStyle).apply {
        text = "Copy diagnostics"
        isAllCaps = false
        visibility = View.GONE
        setOnClickListener {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("Update diagnostics", lastDiagnostics))
        }
    }
    private val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        visibility = View.GONE
    }
    private val buttons = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
    }

    private val channelSwitch = Switch(activity).apply {
        text = "Experimental updates (opt in)"
        isChecked = AppUpdater.experimentalEnabled(activity)
        setOnCheckedChangeListener { _, enabled ->
            AppUpdater.setExperimentalEnabled(activity, enabled)
            check()
        }
    }

    val view: View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(12), dp(4))
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(0x221A73E8) // light tint of the accent, readable in light and dark
        }
        addView(title)
        addView(channelSwitch)
        addView(status)
        addView(notes)
        addView(diagnosticsText)
        addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(copyDiagnosticsButton)
    }

    @Volatile private var cancelled = false
    @Volatile private var closed = false
    private var release: AppUpdater.Release? = null
    private var file: File? = null
    private var waitingForPermission = false
    private var lastDiagnostics = ""

    init {
        idle()
    }

    /** Call when the dialog closes: stops updating the views (a download in progress is cancelled). */
    fun close() {
        closed = true
        cancelled = true
    }

    /** Call from the activity's onResume: continues after "Allow from this source" was turned on. */
    fun onResume() {
        if (waitingForPermission && AppUpdater.canInstall(activity)) {
            waitingForPermission = false
            file?.let { install(it) }
        }
    }

    private fun ui(block: () -> Unit) = activity.runOnUiThread { if (!closed && !activity.isFinishing) block() }

    private fun show(t: String, s: String, n: String? = null, progress: Int? = null, vararg actions: Pair<String, () -> Unit>) {
        title.text = t
        status.text = s
        notes.text = n.orEmpty()
        notes.visibility = if (n.isNullOrEmpty()) View.GONE else View.VISIBLE
        bar.visibility = if (progress == null) View.GONE else View.VISIBLE
        if (progress != null) {
            bar.isIndeterminate = progress < 0
            if (progress >= 0) bar.progress = progress
        }
        buttons.removeAllViews()
        diagnosticsText.visibility = View.GONE
        copyDiagnosticsButton.visibility = View.GONE
        actions.forEach { (label, action) ->
            buttons.addView(Button(activity, null, android.R.attr.borderlessButtonStyle).apply {
                text = label
                isAllCaps = false
                setOnClickListener { action() }
            })
        }
    }

    private val versionLine get() = "You have BCam ${AppUpdater.installedVersionName(activity)}"

    private fun idle() = show("Updates", versionLine, null, null, "Check again" to ::check)

    // ---------------------------------------------------------------- steps

    fun check() {
        lastDiagnostics = ""
        show("Updates", "Checking GitHub for a newer BCam…", null, -1)
        Thread {
            val result = runCatching { AppUpdater.latestRelease(activity) }
            ui {
                val r = result.getOrNull()
                when {
                    result.isFailure -> failed("GitHub request failed. Tap Show details for diagnostics.", null, diagnosticReport("Release check", null, result.exceptionOrNull()))
                    r == null || !AppUpdater.isNewer(activity, r) ->
                        show("Updates", "$versionLine. It's the latest ✓", null, null, "Check again" to ::check)
                    else -> offer(r)
                }
            }
        }.start()
    }

    private fun offer(r: AppUpdater.Release) {
        release = r
        val whatsNew = r.notes.joinToString("\n") { "•  $it" }.ifEmpty { "Bug fixes and improvements." }
        val warn = if (RecordService.isRunning) "\n\nUpdate now stops the recording first (the video so far is saved), then updates." else ""
        show(
            "Update available: BCam ${r.versionName}",
            "$versionLine.",
            "What's new:\n$whatsNew$warn",
            null,
            "Skip this version" to { AppUpdater.skip(activity, r); idle() },
            "Update now" to { download(r) },
        )
    }

    /** Update now: stop and save a running recording first, then download and install. */
    private fun download(r: AppUpdater.Release) {
        if (RecordService.isRunning) {
            show("Saving the recording", "Stopping the recording so the video is saved before the update…", null, -1)
            activity.startService(Intent(activity, RecordService::class.java).setAction(RecordService.ACTION_STOP))
            waitForRecordingToStop(System.currentTimeMillis()) { startDownload(r) }
        } else {
            startDownload(r)
        }
    }

    /** Waits (up to 8 s) until the recorder has finished writing and the service has stopped. */
    private fun waitForRecordingToStop(since: Long, then: () -> Unit) {
        view.postDelayed({
            if (closed) return@postDelayed
            if (RecordService.isRunning && System.currentTimeMillis() - since < 8000) waitForRecordingToStop(since, then) else then()
        }, 300)
    }

    private fun startDownload(r: AppUpdater.Release) {
        lastDiagnostics = ""
        cancelled = false
        show("Downloading BCam ${r.versionName}", "Getting the update from GitHub…", null, -1, "Cancel" to { cancelled = true; offer(r) })
        Thread {
            val result = runCatching {
                val f = AppUpdater.download(activity, r, progress = { p ->
                    ui { if (bar.visibility == View.VISIBLE) { bar.isIndeterminate = p == null; if (p != null) bar.progress = (p * 100).toInt() } }
                }, cancelled = { cancelled })
                f to AppUpdater.verify(activity, f, r)
            }
            ui {
                if (cancelled) return@ui
                val (f, problem) = result.getOrElse {
                    failed("Download failed. Tap Show details for diagnostics.", r, diagnosticReport("APK download", r, it))
                    return@ui
                }
                if (problem != null) {
                    f.delete()
                    failed(problem, r, diagnosticReport("APK verification", r, IllegalStateException(problem)))
                    return@ui
                }
                file = f
                if (AppUpdater.canInstall(activity)) install(f) else askPermission()
            }
        }.start()
    }

    private fun askPermission() {
        show(
            "One more step",
            "Android needs your OK to let BCam install its own updates. Turn on \"Allow from this source\", then come back.",
            null, null,
            "Open settings" to {
                waitingForPermission = true
                runCatching {
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                }.onFailure { waitingForPermission = false }
            },
        )
    }

    private fun install(f: File) {
        val r = release
        if (RecordService.isRunning) {
            // Recording was started again while downloading: stop and save it before installing.
            show("Saving the recording", "Stopping the recording so the video is saved before the update…", null, -1)
            activity.startService(Intent(activity, RecordService::class.java).setAction(RecordService.ACTION_STOP))
            waitForRecordingToStop(System.currentTimeMillis()) { openInstaller(f, r) }
        } else {
            openInstaller(f, r)
        }
    }

    /** Opens Android's installer; the card keeps an Install button in case it is closed without installing. */
    private fun openInstaller(f: File, r: AppUpdater.Release?) {
        val ok = runCatching { activity.startActivity(AppUpdater.installIntent(activity, f)) }.isSuccess
        if (!ok) {
            failed("Couldn't open Android's installer.", r)
            return
        }
        show(
            "BCam ${r?.versionName.orEmpty()} is ready to install",
            "Android's installer opens. If Play Protect asks, you can scan the app or choose to install anyway. BCam restarts on the new version.",
            null, null,
            "Install" to { openInstaller(f, r) },
        )
    }

    private fun diagnosticReport(stage: String, r: AppUpdater.Release?, error: Throwable?): String = buildString {
        appendLine("App: BCam")
        appendLine("Repository: " + AppUpdater.REPO)
        appendLine("Stage: " + stage)
        appendLine("Installed version: " + AppUpdater.installedVersionName(activity))
        appendLine("Target version: " + (r?.versionName ?: "unknown"))
        appendLine("Release API URL: https://api.github.com/repos/" + AppUpdater.REPO + "/releases/latest")
        appendLine("APK URL: " + (r?.apkUrl ?: "not available"))
        appendLine("Exception: " + (error?.javaClass?.name ?: "unknown"))
        appendLine("Message: " + (error?.message ?: "No exception message"))
    }.trim()

    private fun failed(message: String, r: AppUpdater.Release?, diagnostics: String = "") {
        lastDiagnostics = diagnostics
        val actions = mutableListOf<Pair<String, () -> Unit>>(
            "Release page" to {
                runCatching {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r?.pageUrl ?: "https://github.com/${AppUpdater.REPO}/releases/latest")))
                }
            },
        )
        actions += "Show details" to {
            diagnosticsText.text = lastDiagnostics.ifBlank { "No additional diagnostic details were captured." }
            diagnosticsText.visibility = View.VISIBLE
            copyDiagnosticsButton.visibility = View.VISIBLE
        }
        if (r != null) actions += "Try again" to { lastDiagnostics = ""; diagnosticsText.visibility = View.GONE; copyDiagnosticsButton.visibility = View.GONE; download(r) }
        show("Update problem", message, null, null, *actions.toTypedArray())
    }
}
