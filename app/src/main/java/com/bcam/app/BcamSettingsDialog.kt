@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.bcam.app

import android.hardware.camera2.CameraCharacteristics
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale

@Composable
fun BcamSettingsDialog(
    initial: VideoSettings,
    cameraOptions: Boolean,
    maximumHeight: Int,
    supports60Fps: Boolean,
    initialFacing: Int,
    updateSection: UpdateSection,
    onDismiss: () -> Unit,
    onSave: (VideoSettings, Int?) -> Unit,
    onExit: () -> Unit,
) {
    MaterialExpressiveTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        motionScheme = MotionScheme.expressive(),
        shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(28.dp), large = RoundedCornerShape(32.dp)),
    ) {
        var settings by remember { mutableStateOf(initial) }
        var facing by remember { mutableIntStateOf(initialFacing) }
        var customStop by remember { mutableStateOf(initial.autoStopMinutes !in VideoSettings.AUTO_STOP_OPTIONS) }
        var customMinutes by remember { mutableStateOf(if (customStop) initial.autoStopMinutes.toString() else "") }
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.90f),
                shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(20.dp)) {
                    Text("Video settings", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(12.dp))
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (cameraOptions) BcamChoice("Camera", facing, listOf(
                            CameraCharacteristics.LENS_FACING_BACK to "Back camera",
                            CameraCharacteristics.LENS_FACING_FRONT to "Front camera")) { facing = it }
                        BcamChoice("Resolution", settings.resolution, VideoSettings.RESOLUTIONS
                            .filter { it <= maximumHeight || it == settings.resolution || it == 720 }
                            .map { it to VideoSettings.resolutionLabel(it) }) { settings = settings.copy(resolution = it) }
                        BcamChoice("Frame rate", settings.fps, VideoSettings.FPS_OPTIONS
                            .filter { it != 60 || supports60Fps || settings.fps == 60 }
                            .map { it to "$"+"it fps" }) { settings = settings.copy(fps = it) }
                        BcamChoice("Quality", settings.quality, listOf(0 to "Low", 1 to "Standard", 2 to "High")) { settings = settings.copy(quality = it) }
                        BcamChoice("Codec", settings.preferHevc, listOf(true to "H.265", false to "H.264")) { settings = settings.copy(preferHevc = it) }
                        BcamChoice("Sound", settings.audio, listOf(true to "On", false to "Off")) { settings = settings.copy(audio = it) }
                        BcamChoice("New file every", settings.segmentMinutes, VideoSettings.SEGMENTS.map { it to "$"+"it min" }) { settings = settings.copy(segmentMinutes = it) }
                        BcamChoice("Auto-stop after", if (customStop) -1 else settings.autoStopMinutes,
                            VideoSettings.AUTO_STOP_OPTIONS.map { it to (if (it == 0) "Unlimited" else "$"+"it min") } + (-1 to "Custom")) {
                            customStop = it == -1
                            if (it != -1) settings = settings.copy(autoStopMinutes = it)
                        }
                        if (customStop) OutlinedTextField(
                            value = customMinutes, onValueChange = { customMinutes = it.filter(Char::isDigit).take(4) },
                            label = { Text("Custom minutes (1–1440)") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth())
                        val gb = settings.estimatedPartBytes(settings.preferHevc) / 1_000_000_000.0
                        val size = if (gb >= 1) String.format(Locale.US, "%.1f GB", gb) else "$"+"{(gb * 1000).toInt()} MB"
                        Text("About $"+"size per $"+"{settings.segmentMinutes}-minute file",
                            style = MaterialTheme.typography.bodySmall)
                        AndroidView(factory = { updateSection.view }, modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) {
                            Text("Stop recording / exit BCam", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        TextButton(onClick = { onSave(VideoSettings(), null) }) { Text("Reset") }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = {
                            onSave(if (customStop) settings.copy(autoStopMinutes = (customMinutes.toIntOrNull() ?: 15).coerceIn(1, 1440)) else settings,
                                if (cameraOptions) facing else null)
                        }) { Text("Save") }
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> BcamChoice(label: String, selected: T, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == selected }?.second ?: "",
            onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable))
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(value); expanded = false },
                    leadingIcon = if (value == selected) ({ Icon(androidx.compose.material.icons.Icons.Filled.Check, null) }) else null)
            }
        }
    }
}
