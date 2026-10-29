package com.bcam.app

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

/** Shared AAYouTube-style logging controls. Call only from a host n2k02theme screen. */
@Composable
internal fun N2K02UnifiedLogsPage(
    modifier: Modifier = Modifier,
    appName: String,
    onBack: () -> Unit,
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
            listOf("— $extraLabel —") + extraLines.ifEmpty { listOf("No events yet.") }
        else emptyList()
        (saved + additional).ifEmpty { listOf("No saved diagnostic events.") }
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
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ListItem(
                headlineContent = { Text("Enable logging") },
                supportingContent = { Text("Optional diagnostics · fatal crashes always saved locally") },
                trailingContent = { Switch(checked = enabled, onCheckedChange = AppLog::setEnabled) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
            if (!enabled) Text(
                "Detailed logging is off. Saved crash reports and events remain available.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { savedPage = (shown - 1).coerceAtLeast(0); followLatest = false },
                    enabled = shown > 0,
                ) { Text("Prev") }
                Text("${shown + 1} / $pages", style = MaterialTheme.typography.labelLarge)
                TextButton(
                    onClick = {
                        savedPage = (shown + 1).coerceAtMost(pages - 1)
                        followLatest = savedPage >= pages - 1
                    },
                    enabled = shown < pages - 1,
                ) { Text("Next") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val combined = AppLog.slice(0, Int.MAX_VALUE) +
                        if (extraLabel != null) listOf("— $extraLabel —") + extraLines else emptyList()
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("$appName logs", combined.joinToString("\n")))
                    Toast.makeText(context, "Logs copied", Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.weight(1f)) { Text("Copy") }
                Button(onClick = {
                    AppLog.clear()
                    clearExtra?.invoke()
                    followLatest = true
                }, modifier = Modifier.weight(1f)) { Text("Clear all") }
                TextButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Back") }
            }
        }
    }
}
