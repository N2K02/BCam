package com.bcam.app

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object LogEventOrdering {
    private val stamp = Regex("""(?:^|\s)(?:(\d{4}-\d{2}-\d{2})[ T])?(\d{2}:\d{2}:\d{2})(?:\.(\d{1,3}))?""")
    fun merge(saved: List<String>, extra: List<String>, today: String): List<String> =
        (saved + extra).withIndex().sortedWith(
            compareBy<IndexedValue<String>> { item ->
                val match = stamp.find(item.value)
                if (match == null) "9999-99-99 99:99:99.999"
                else match.groupValues[1].ifBlank { today } + " " +
                    match.groupValues[2] + "." + match.groupValues[3].padEnd(3, '0')
            }.thenBy { it.index }
        ).map { it.value }
}

/** Shared AAYouTube-style logging controls. Call only from a host n2k02theme screen. */
@Composable
internal fun N2K02UnifiedLogsPage(
    modifier: Modifier = Modifier,
    appName: String,
    extraLabel: String? = null,
    extraLines: List<String> = emptyList(),
    clearExtra: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    LaunchedEffect(context) { AppLog.init(context.applicationContext) }
    val enabled by AppLog.enabled.collectAsState()
    val version by AppLog.version.collectAsState()
    var savedPage by rememberSaveable { mutableIntStateOf(0) }
    var followLatest by rememberSaveable { mutableStateOf(true) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val cols = ((viewport.width / with(density) { (11.sp * 0.6f).toPx() }).toInt() - 1)
        .coerceAtLeast(10)
    val perPage = (viewport.height / with(density) { 14.sp.toPx() }).toInt().coerceAtLeast(1)
    val rows = remember(version, extraLines, extraLabel, cols) {
        val saved = AppLog.slice(0, Int.MAX_VALUE)
        val additional = if (extraLabel != null)
            extraLines.ifEmpty { listOf("No events yet.") }.map { "[$extraLabel] $it" }
        else emptyList()
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        LogEventOrdering.merge(saved, additional, today)
            .ifEmpty { listOf("No saved diagnostic events.") }
            .flatMap { it.split('\n') }
            .flatMap { line ->
                if (line.length <= cols) listOf(line) else buildList {
                    add(line.take(cols))
                    var position = cols
                    val width = (cols - 2).coerceAtLeast(1)
                    while (position < line.length) {
                        add("  " + line.substring(position, (position + width).coerceAtMost(line.length)))
                        position += width
                    }
                }
            }
    }
    val pages = ((rows.size + perPage - 1) / perPage).coerceAtLeast(1)
    val shown = if (followLatest) pages - 1 else savedPage.coerceIn(0, pages - 1)
    BoxWithConstraints(modifier) {
        val compact = maxHeight < 430.dp
        Column(
            modifier = Modifier.fillMaxSize()
                .then(if (compact) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // AAYouTube Logs: compact toggle without a settings-list background.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Enable logging", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = AppLog::setEnabled)
            }
            Card(
                modifier = Modifier.fillMaxWidth().then(
                    if (compact) Modifier.height(140.dp) else Modifier.weight(1f)
                ),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(
                    Modifier.fillMaxSize().padding(10.dp).onSizeChanged { viewport = it },
                ) {
                    rows.drop(shown * perPage).take(perPage).forEach { row ->
                        Text(
                            row,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
                            ),
                            maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                        )
                    }
                }
            }
            // Same left / centre / right pagination and action spacing as AAYouTube.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { savedPage = (shown - 1).coerceAtLeast(0); followLatest = false },
                    enabled = shown > 0,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Prev", maxLines = 1, softWrap = false)
                }
                Text((shown + 1).toString() + " / " + pages, style = MaterialTheme.typography.labelLarge)
                Button(
                    onClick = {
                        savedPage = (shown + 1).coerceAtMost(pages - 1)
                        followLatest = savedPage >= pages - 1
                    },
                    enabled = shown < pages - 1,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Next", maxLines = 1, softWrap = false)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Button(
                    onClick = {
                        val combined = LogEventOrdering.merge(
                            AppLog.slice(0, Int.MAX_VALUE),
                            if (extraLabel != null) extraLines.map { "[$extraLabel] $it" }
                            else emptyList(),
                            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                                .format(java.util.Date()),
                        )
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText(appName + " logs", combined.joinToString("\n")))
                        Toast.makeText(context, "Logs copied", Toast.LENGTH_SHORT).show()
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy", maxLines = 1, softWrap = false)
                }
                Button(
                    onClick = {
                        AppLog.clear()
                        clearExtra?.invoke()
                        followLatest = true
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Icon(Icons.Filled.DeleteSweep, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Clear all", maxLines = 1, softWrap = false)
                }
            }
        }
    }
}
