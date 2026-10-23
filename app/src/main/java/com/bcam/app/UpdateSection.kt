@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.bcam.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File

/** UI state survives recomposition; no Android Views are embedded in Compose. */
class UpdateSection(private val activity: Activity) {
    data class UiState(
        val phase: Phase = Phase.Idle,
        val title: String = "Updates",
        val message: String = "",
        val notes: String = "",
        val progress: Float? = null,
        val diagnostics: String = "",
        val detailsVisible: Boolean = false,
        val experimental: Boolean = false,
    )
    enum class Phase { Idle, Checking, Current, Available, Saving, Downloading, Permission, Ready, Failed }
    var state by mutableStateOf(UiState(
        message = "Installed version: ${AppUpdater.installedVersionName(activity)}",
        experimental = AppUpdater.experimentalEnabled(activity),
    ))
        private set

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    @Volatile private var cancelled = false
    private var generation = 0
    private var release: AppUpdater.Release? = null
    private var file: File? = null
    private var waitingForPermission = false

    fun close() {
        closed = true
        cancelled = true
        generation++
        handler.removeCallbacksAndMessages(null)
    }
    fun onResume() {
        if (waitingForPermission && AppUpdater.canInstall(activity)) {
            waitingForPermission = false
            file?.let(::install)
        }
    }
    private fun ui(block: () -> Unit) = activity.runOnUiThread {
        if (!closed && !activity.isFinishing) block()
    }
    fun setExperimental(enabled: Boolean) {
        if (state.experimental == enabled) return
        AppUpdater.setExperimentalEnabled(activity, enabled)
        state = state.copy(experimental = enabled)
        check()
    }
    fun check() {
        if (closed) return
        cancelled = true
        val token = ++generation
        release = null
        state = state.copy(phase = Phase.Checking, title = "Checking for updates",
            message = "Contacting GitHub for the latest BCam release…", notes = "",
            progress = null, diagnostics = "", detailsVisible = false)
        Thread {
            val result = runCatching { AppUpdater.latestRelease(activity.applicationContext) }
            ui {
                if (token != generation) return@ui
                val r = result.getOrNull()
                when {
                    result.isFailure -> failed("Couldn't check GitHub for updates.", null,
                        diagnosticReport("Release check", null, result.exceptionOrNull()))
                    r == null || !AppUpdater.isNewer(activity, r) ->
                        state = state.copy(phase = Phase.Current, title = "You're up to date",
                            message = "BCam ${AppUpdater.installedVersionName(activity)} is the latest version.")
                    else -> offer(r)
                }
            }
        }.start()
    }
    private fun offer(r: AppUpdater.Release) {
        release = r
        val warning = if (RecordService.isRunning)
            "\nA recording is active. It will be saved before the update." else ""
        state = state.copy(phase = Phase.Available, title = "BCam ${r.versionName} available",
            message = "Installed: ${AppUpdater.installedVersionName(activity)}",
            notes = r.notes.joinToString("\n") { "• $it" }.ifBlank { "Bug fixes and improvements." } + warning,
            progress = null, detailsVisible = false)
    }
    fun skip() {
        release?.let { AppUpdater.skip(activity, it) }
        state = state.copy(phase = Phase.Idle, title = "Updates",
            message = "This release has been skipped.", notes = "")
    }
    fun download() {
        val r = release ?: return
        if (state.phase == Phase.Downloading || state.phase == Phase.Saving) return
        if (RecordService.isRunning) {
            state = state.copy(phase = Phase.Saving, title = "Saving your recording",
                message = "Waiting for the recording to finish safely.", progress = null)
            activity.startService(Intent(activity, RecordService::class.java).setAction(RecordService.ACTION_STOP))
            waitForStop(System.currentTimeMillis()) { startDownload(r) }
        } else startDownload(r)
    }
    private fun waitForStop(start: Long, next: () -> Unit) {
        handler.postDelayed({
            if (closed) return@postDelayed
            if (!RecordService.isRunning) next()
            else if (System.currentTimeMillis() - start >= 8_000L)
                failed("Recording is still active. The update was stopped to protect your video.", release)
            else waitForStop(start, next)
        }, 300L)
    }
    private fun startDownload(r: AppUpdater.Release) {
        cancelled = false
        val token = ++generation
        state = state.copy(phase = Phase.Downloading, title = "Downloading BCam ${r.versionName}",
            message = "Downloading from GitHub…", progress = null, diagnostics = "")
        Thread {
            val result = runCatching {
                val apk = AppUpdater.download(activity.applicationContext, r,
                    progress = { p -> ui {
                        if (token == generation && state.phase == Phase.Downloading)
                            state = state.copy(progress = p?.coerceIn(0f, 1f))
                    } },
                    cancelled = { cancelled || closed || token != generation })
                apk to AppUpdater.verify(activity.applicationContext, apk, r)
            }
            ui {
                if (cancelled || token != generation) return@ui
                val pair = result.getOrElse {
                    failed("Download failed.", r, diagnosticReport("APK download", r, it))
                    return@ui
                }
                if (pair.second != null) {
                    pair.first.delete()
                    failed(pair.second!!, r, diagnosticReport("APK verification", r,
                        IllegalStateException(pair.second)))
                } else {
                    file = pair.first
                    if (AppUpdater.canInstall(activity)) install(pair.first) else permission()
                }
            }
        }.start()
    }
    fun cancelDownload() {
        cancelled = true
        generation++
        release?.let(::offer)
    }
    private fun permission() {
        state = state.copy(phase = Phase.Permission, title = "Installation permission",
            message = "Allow BCam to install updates in Android settings, then return.")
    }
    fun openPermissionSettings() {
        waitingForPermission = true
        runCatching {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}")))
        }.onFailure {
            waitingForPermission = false
            failed("Couldn't open installation settings.", release, diagnosticReport("Permission", release, it))
        }
    }
    fun installReady() { file?.let(::install) }
    private fun install(apk: File) {
        if (RecordService.isRunning) {
            state = state.copy(phase = Phase.Saving, title = "Saving your recording",
                message = "Waiting for recording to stop before installation.")
            activity.startService(Intent(activity, RecordService::class.java).setAction(RecordService.ACTION_STOP))
            waitForStop(System.currentTimeMillis()) { openInstaller(apk) }
        } else openInstaller(apk)
    }
    private fun openInstaller(apk: File) {
        if (runCatching { activity.startActivity(AppUpdater.installIntent(activity, apk)) }.isFailure) {
            failed("Couldn't open Android's installer.", release)
            return
        }
        state = state.copy(phase = Phase.Ready, title = "Ready to install",
            message = "Follow Android's installer to finish updating BCam.")
    }
    fun showDetails() { state = state.copy(detailsVisible = !state.detailsVisible) }
    fun copyDiagnostics() {
        val manager = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        manager?.setPrimaryClip(ClipData.newPlainText("BCam update diagnostics", state.diagnostics))
    }
    fun openReleasePage() {
        val url = release?.pageUrl ?: "https://github.com/${AppUpdater.REPO}/releases/latest"
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
    fun retry() { if (release != null) download() else check() }
    private fun failed(message: String, r: AppUpdater.Release?, diagnostics: String = "") {
        state = state.copy(phase = Phase.Failed, title = "Update problem", message = message,
            diagnostics = diagnostics.ifBlank { diagnosticReport("Update", r, null) },
            progress = null, detailsVisible = false)
    }
    private fun diagnosticReport(stage: String, r: AppUpdater.Release?, error: Throwable?) = buildString {
        appendLine("App: BCam")
        appendLine("Repository: ${AppUpdater.REPO}")
        appendLine("Stage: $stage")
        appendLine("Installed version: ${AppUpdater.installedVersionName(activity)}")
        appendLine("Target version: ${r?.versionName ?: "unknown"}")
        appendLine("APK URL: ${r?.apkUrl ?: "not available"}")
        appendLine("Exception: ${error?.javaClass?.name ?: "unknown"}")
        appendLine("Message: ${error?.message ?: "No exception message"}")
    }.trim()
}

@Composable
fun BcamUpdateScreen(updater: UpdateSection, modifier: Modifier = Modifier) {
    val s = updater.state
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Installed version: ${AppUpdater.installedVersionName(androidx.compose.ui.platform.LocalContext.current)}",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(s.title, style = MaterialTheme.typography.titleLarge)
                Text(s.message, style = MaterialTheme.typography.bodyMedium)
                if (s.notes.isNotBlank()) Text(s.notes, style = MaterialTheme.typography.bodyMedium)
                when (s.phase) {
                    UpdateSection.Phase.Checking, UpdateSection.Phase.Saving ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LoadingIndicator(Modifier.size(36.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(if (s.phase == UpdateSection.Phase.Checking) "Checking…" else "Saving…")
                        }
                    UpdateSection.Phase.Downloading -> {
                        if (s.progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else {
                            LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                            Text("${(s.progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    else -> Unit
                }
                when (s.phase) {
                    UpdateSection.Phase.Idle, UpdateSection.Phase.Current ->
                        Button(onClick = updater::check, modifier = Modifier.fillMaxWidth()) {
                            Text(if (s.phase == UpdateSection.Phase.Current) "Check again" else "Check for updates")
                        }
                    UpdateSection.Phase.Available -> {
                        Button(onClick = updater::download, modifier = Modifier.fillMaxWidth()) { Text("Update now") }
                        TextButton(onClick = updater::skip) { Text("Skip this version") }
                    }
                    UpdateSection.Phase.Downloading ->
                        OutlinedButton(onClick = updater::cancelDownload) { Text("Cancel download") }
                    UpdateSection.Phase.Permission ->
                        Button(onClick = updater::openPermissionSettings) { Text("Open Android settings") }
                    UpdateSection.Phase.Ready ->
                        Button(onClick = updater::installReady) { Text("Install") }
                    UpdateSection.Phase.Failed -> {
                        Button(onClick = updater::retry) { Text("Try again") }
                        OutlinedButton(onClick = updater::openReleasePage) { Text("Release page") }
                        TextButton(onClick = updater::showDetails) {
                            Text(if (s.detailsVisible) "Hide diagnostics" else "Show diagnostics")
                        }
                        if (s.detailsVisible) {
                            Text(s.diagnostics, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = updater::copyDiagnostics) { Text("Copy diagnostics") }
                        }
                    }
                    else -> Unit
                }
            }
        }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Experimental updates", style = MaterialTheme.typography.titleMedium)
                Text("Include preview releases", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = s.experimental, onCheckedChange = updater::setExperimental,
                enabled = s.phase !in listOf(UpdateSection.Phase.Downloading, UpdateSection.Phase.Saving))
        }
        Text("Downloads are verified before Android installs them.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
