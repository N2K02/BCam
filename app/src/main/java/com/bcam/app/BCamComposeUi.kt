package com.bcam.app

import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import dev.n2k02.updater.PublishedUpdateSource
import dev.n2k02.updater.UpdateConfig
import dev.n2k02.updater.UpdateInstallGuard
import dev.n2k02.updater.hasPendingUpdate
import dev.n2k02.updater.UpdaterController
import java.util.Locale

internal object BCamSharedUpdater {
    private var controllerInstance: UpdaterController? by mutableStateOf(null)
    var experimental by mutableStateOf(false)
        private set
    val guard = UpdateInstallGuard { !RecordService.isRunning }
    fun initialize(activity: ComponentActivity) {
        if (controllerInstance != null) return
        experimental = AppUpdater.experimentalEnabled(activity)
        controllerInstance = newController(activity)
    }
    private fun newController(activity: ComponentActivity): UpdaterController {
        val p = activity.packageManager.getPackageInfo(activity.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()
        return UpdaterController(context = activity.applicationContext,
            config = UpdateConfig(repository = "N2K02/BCam", packageName = activity.packageName,
                installedVersionCode = code, experimentalEnabled = experimental),
            publishedSource = PublishedUpdateSource("https://n2k02.github.io/bcam/updates"),
            offerPreferences = activity.getSharedPreferences("app_updater", android.content.Context.MODE_PRIVATE))
    }
    val controller: UpdaterController get() = requireNotNull(controllerInstance)
    fun toggle(activity: ComponentActivity, enabled: Boolean) {
        controller.cancel()
        AppUpdater.setExperimentalEnabled(activity, enabled)
        experimental = enabled
        controllerInstance = newController(activity)
        controller.check(manual = true)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BCamMaterialScreen(activity: ComponentActivity, startUpdates: Boolean = false, checkNow: Boolean = false) {
    val updateStatus by BCamSharedUpdater.controller.status.collectAsState()
    var tab by remember { mutableIntStateOf(if (startUpdates) 1 else 0) }
    var section by remember { mutableStateOf("Overview") }
    var settings by remember { mutableStateOf(VideoSettings.load(activity)) }
    val prefs = activity.getSharedPreferences(VideoSettings.PREFS, android.content.Context.MODE_PRIVATE)
    var facing by remember { mutableIntStateOf(prefs.getInt(RecordService.KEY_FACING, CameraCharacteristics.LENS_FACING_BACK)) }
    var resetDialog by remember { mutableStateOf(false) }
    var quitDialog by remember { mutableStateOf(false) }
    val cameraManager = remember { activity.getSystemService(CameraManager::class.java) }
    val caps = remember {
        runCatching {
            var maxHeight = 720
            var has60 = false
            val faces = mutableSetOf<Int>()
            for (id in cameraManager.cameraIdList) {
                val c = cameraManager.getCameraCharacteristics(id)
                c.get(CameraCharacteristics.LENS_FACING)?.let { faces.add(it) }
                c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(MediaRecorder::class.java)?.forEach { maxHeight = maxOf(maxHeight, minOf(it.width, it.height)) }
                if (c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.any { it.upper >= 60 } == true) has60 = true
            }
            Triple(maxHeight, has60, faces.size > 1)
        }.getOrDefault(Triple(1080, false, false))
    }
    fun save() {
        val changed = settings != VideoSettings.load(activity)
        val switched = facing != prefs.getInt(RecordService.KEY_FACING, CameraCharacteristics.LENS_FACING_BACK)
        settings.save(activity)
        prefs.edit().putInt(RecordService.KEY_FACING, facing).apply()
        if ((changed || switched) && RecordService.isRunning) {
            activity.startService(Intent(activity, RecordService::class.java).setAction(RecordService.ACTION_APPLY_SETTINGS))
        }
    }
    // Back traverses the BCam settings hierarchy before leaving this activity.
    BackHandler(enabled = tab == 1 || section != "Overview") {
        if (tab == 1) {
            tab = 0
            section = "Overview"
        } else {
            section = "Overview"
        }
    }
    // Match MRadio: the shared N2K02Theme owns system day/night and Material You colors.
    dev.n2k02.theme.N2K02Theme {
        Scaffold(
            topBar = { CenterAlignedTopAppBar(title = { if (tab == 1) dev.n2k02.updater.N2K02UpdateTitle() else Text(if (section == "Overview") "Settings" else section) }) },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(selected = tab == 0, onClick = { tab = 0; section = "Overview" },
                        icon = { dev.n2k02.updater.N2K02UpdateBadgedIcon(updateStatus) { Icon(Icons.Rounded.Settings, "Settings") } }, label = { Text("Settings") })
                    NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                        icon = { dev.n2k02.updater.N2K02UpdateBadgedIcon(updateStatus) { Icon(Icons.Rounded.SystemUpdate, "Updates") } }, label = { Text("Updates") })
                    NavigationBarItem(selected = false, onClick = { quitDialog = true },
                        icon = { Icon(Icons.Rounded.ExitToApp, null) }, label = { Text("Quit") })
                }
            }
        ) { insets ->
            Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (tab == 0 && section == "Overview") {
                    Text("Settings", style = MaterialTheme.typography.headlineMedium)
                    listOf(
                        Triple("Recording", "Camera, resolution, frame rate and quality", Icons.Rounded.Videocam),
                        Triple("Audio & files", "Sound, file segments and auto-stop", Icons.Rounded.Settings),
                    ).forEach { (title, subtitle, icon) ->
                        Surface(onClick = { section = title }, shape = MaterialTheme.shapes.extraLarge,
                            color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                            ListItem(headlineContent = { Text(title) }, supportingContent = { Text(subtitle) },
                                leadingContent = { Icon(icon, null) }, trailingContent = { dev.n2k02.updater.N2K02UpdateBadge(updateStatus) })
                        }
                    }
                    Surface(onClick = { tab = 1 }, shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                        ListItem(headlineContent = { Text("About & updates") },
                            supportingContent = { Text(if (updateStatus.hasPendingUpdate()) "A new update is available" else "Version, experimental channel and update checks") },
                            leadingContent = { Icon(Icons.Rounded.SystemUpdate, null) },
                            trailingContent = { Icon(Icons.Rounded.ChevronRight, null) })
                    }
                } else if (tab == 0) {
                    Text("‹ Settings", modifier = Modifier.fillMaxWidth().clickable { section = "Overview" }.padding(vertical = 8.dp),
                        style = MaterialTheme.typography.titleMedium)
                    Text(section, style = MaterialTheme.typography.headlineSmall)
                    if (section == "Recording") {
                    Text("Recording", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    if (caps.third) ChoiceRow("Camera", if (facing == CameraCharacteristics.LENS_FACING_FRONT) "Front camera" else "Back camera",
                        listOf("Back camera", "Front camera")) {
                        facing = if (it == "Front camera") CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
                    }
                    ChoiceRow("Resolution", VideoSettings.resolutionLabel(settings.resolution),
                        VideoSettings.RESOLUTIONS.filter { it <= caps.first || it == settings.resolution }.map(VideoSettings::resolutionLabel)) { chosen ->
                        settings = settings.copy(resolution = VideoSettings.RESOLUTIONS.first { VideoSettings.resolutionLabel(it) == chosen })
                    }
                    ChoiceRow("Frame rate", "${settings.fps} fps",
                        VideoSettings.FPS_OPTIONS.filter { it != 60 || caps.second || settings.fps == 60 }.map { "$it fps" }) {
                        settings = settings.copy(fps = it.substringBefore(" ").toInt())
                    }
                    ChoiceRow("Quality", listOf("Low", "Standard", "High")[settings.quality], listOf("Low", "Standard", "High")) {
                        settings = settings.copy(quality = listOf("Low", "Standard", "High").indexOf(it))
                    }
                    ChoiceRow("Codec", if (settings.preferHevc) "H.265 (HEVC)" else "H.264 (AVC)", listOf("H.265 (HEVC)", "H.264 (AVC)")) {
                        settings = settings.copy(preferHevc = it.startsWith("H.265"))
                    }
                    }
                    if (section == "Audio & files") {
                    ChoiceRow("Sound", if (settings.audio) "On" else "Off", listOf("On", "Off")) {
                        settings = settings.copy(audio = it == "On")
                    }
                    ChoiceRow("New file every", "${settings.segmentMinutes} min", VideoSettings.SEGMENTS.map { "$it min" }) {
                        settings = settings.copy(segmentMinutes = it.substringBefore(" ").toInt())
                    }
                    ChoiceRow("Auto-stop after", if (settings.autoStopMinutes == 0) "Unlimited" else "${settings.autoStopMinutes} min",
                        (VideoSettings.AUTO_STOP_OPTIONS + settings.autoStopMinutes).distinct().map { if (it == 0) "Unlimited" else "$it min" }) {
                        settings = settings.copy(autoStopMinutes = if (it == "Unlimited") 0 else it.substringBefore(" ").toInt())
                    }
                    val mb = settings.estimatedPartBytes(settings.preferHevc) / 1_000_000.0
                    Text("Estimated ${String.format(Locale.US, "%.0f", mb)} MB per ${settings.segmentMinutes}-minute file",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { resetDialog = true }, modifier = Modifier.weight(1f)) { Text("Reset") }
                        Button(onClick = { save(); activity.finish() }, modifier = Modifier.weight(1.5f)) { Text("Save changes") }
                    }
                } else {
                    dev.n2k02.updater.N2K02UpdateScreen(
                        controller = BCamSharedUpdater.controller,
                        installGuard = BCamSharedUpdater.guard,
                        modifier = Modifier.padding(horizontal = 0.dp, vertical = 8.dp),
                        experimentalEnabled = BCamSharedUpdater.experimental,
                        onExperimentalEnabledChange = { BCamSharedUpdater.toggle(activity, it) },
                    )
                }
            }
        }
        if (quitDialog) AlertDialog(
            onDismissRequest = { quitDialog = false },
            title = { Text(if (RecordService.isRunning) "Quit without saving?" else "Quit BCam?") },
            text = { Text(if (RecordService.isRunning)
                "Stop recording and discard the current video segment? Earlier completed segments remain saved."
                else "Close BCam settings?") },
            confirmButton = {
                TextButton(onClick = {
                    quitDialog = false
                    if (RecordService.isRunning) {
                        activity.startService(Intent(activity, RecordService::class.java)
                            .setAction(RecordService.ACTION_DISCARD_CURRENT))
                    }
                    activity.finish()
                }) { Text(if (RecordService.isRunning) "Discard & quit" else "Quit") }
            },
            dismissButton = { TextButton(onClick = { quitDialog = false }) { Text("Cancel") } }
        )
        if (resetDialog) AlertDialog(onDismissRequest = { resetDialog = false },
            title = { Text("Reset video settings?") },
            text = { Text("Restore the default recording settings?") },
            confirmButton = { TextButton(onClick = { settings = VideoSettings(); resetDialog = false }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { resetDialog = false }) { Text("Cancel") } })
    }
    LaunchedEffect(checkNow) { if (checkNow) BCamSharedUpdater.controller.check(manual = true) }
}

@Composable
private fun ChoiceRow(label: String, selected: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clickable { expanded = true }) {
        Box {
            ListItem(headlineContent = { Text(label) },
                supportingContent = { Text(selected, fontWeight = FontWeight.Medium) },
                trailingContent = { Icon(Icons.Rounded.ArrowDropDown, null) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer))
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(option); expanded = false })
                }
            }
        }
    }
}
