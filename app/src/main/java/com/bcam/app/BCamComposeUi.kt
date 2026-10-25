package com.bcam.app

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.compose.runtime.saveable.rememberSaveable
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
    var page by rememberSaveable { mutableStateOf(if (startUpdates) BCamPage.UPDATES else BCamPage.SETTINGS) }
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
    // Settings stops recording on entry; changes take effect next time it starts.
    fun save() {
        settings.save(activity)
        prefs.edit().putInt(RecordService.KEY_FACING, facing).apply()
    }
    BackHandler(enabled = page != BCamPage.SETTINGS) {
        page = page.backDestination()
    }
    // Match MRadio: the shared N2K02Theme owns system day/night and Material You colors.
    dev.n2k02.theme.N2K02Theme {
        Scaffold(
            topBar = {
                // One host-owned header at every depth, matching mRadio settings.
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            Icon(
                                when (page) {
                                    BCamPage.SETTINGS -> Icons.Rounded.Settings
                                    BCamPage.RECORDING -> Icons.Rounded.Videocam
                                    BCamPage.AUDIO_FILES -> Icons.Rounded.AudioFile
                                    BCamPage.UPDATES -> Icons.Rounded.SystemUpdate
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp),
                            )
                            Text(page.title, maxLines = 1)
                        }
                    },
                    actions = {
                        dev.n2k02.updater.N2K02AppIcon(Modifier.size(28.dp))
                        Text("BCam", modifier = Modifier.padding(start = 6.dp, end = 16.dp),
                            style = MaterialTheme.typography.titleMedium)
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(selected = page.isSettingsPage, onClick = { page = BCamPage.SETTINGS },
                        icon = { dev.n2k02.updater.N2K02UpdateBadgedIcon(updateStatus) { Icon(Icons.Rounded.Settings, "Settings") } }, label = { Text("Settings") })
                    NavigationBarItem(selected = page == BCamPage.UPDATES, onClick = { page = BCamPage.UPDATES },
                        icon = { Icon(Icons.Rounded.SystemUpdate, "Updates") }, label = { Text("Updates") })
                    NavigationBarItem(selected = false, onClick = { quitDialog = true },
                        icon = { Icon(Icons.Rounded.ExitToApp, null) }, label = { Text("Quit") })
                }
            }
        ) { insets ->
            if (page == BCamPage.UPDATES) {
                // Standalone updater owns scrolling and receives the Scaffold
                // bottom-bar inset. Avoid a scrollable Column inside another.
                dev.n2k02.updater.N2K02UpdatePage(
                    controller = BCamSharedUpdater.controller,
                    installGuard = BCamSharedUpdater.guard,
                    modifier = Modifier.fillMaxSize().padding(insets),
                    experimentalEnabled = BCamSharedUpdater.experimental,
                    onExperimentalEnabledChange = { BCamSharedUpdater.toggle(activity, it) },
                )
            } else {
            Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (page == BCamPage.SETTINGS) {
                    Text("Configure your camera", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 12.dp))
                    listOf(
                        Triple(BCamPage.RECORDING, "Camera, resolution, frame rate and quality", Icons.Rounded.Videocam),
                        Triple(BCamPage.AUDIO_FILES, "Sound, file segments and auto-stop", Icons.Rounded.Settings),
                    ).forEach { (destination, subtitle, icon) ->
                        ListItem(headlineContent = { Text(destination.title) },
                            supportingContent = { Text(subtitle) },
                            leadingContent = { Icon(icon, null) },
                            modifier = Modifier.clickable { page = destination })
                    }
                    HorizontalDivider()
                    ListItem(headlineContent = { Text("About & updates") },
                        supportingContent = { Text(if (updateStatus.hasPendingUpdate()) "A new update is available" else "Version, experimental channel and update checks") },
                        leadingContent = { Icon(Icons.Rounded.SystemUpdate, null) },
                        trailingContent = { dev.n2k02.updater.N2K02UpdateBadge(updateStatus) },
                        modifier = Modifier.clickable { page = BCamPage.UPDATES })
                    Text("Opening Settings stops recording and saves the current video.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                } else {
                    if (page == BCamPage.RECORDING) {
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
                    if (page == BCamPage.AUDIO_FILES) {
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
                }
            }
            }
        }
        if (quitDialog) AlertDialog(
            onDismissRequest = { quitDialog = false },
            title = { Text("Close BCam settings?") },
            text = { Text("Opening Settings stops recording and saves the current video. Close settings?") },
            confirmButton = {
                TextButton(onClick = {
                    quitDialog = false
                    activity.finish()
                }) { Text("Close") }
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
