package com.bcam.app

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** Native Material 3 Logs subpage, backed by the shared private diagnostic store. */
class LogsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        setContent {
            MaterialTheme {
                val context = LocalContext.current
                val enabled by AppLog.enabled.collectAsState()
                val version by AppLog.version.collectAsState()
                val logText = remember(version) { AppLog.all() }
                Scaffold(topBar = {
                    Surface(tonalElevation = 2.dp) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("‹ Logs", style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.weight(1f))
                            Text(context.applicationInfo.loadLabel(context.packageManager).toString(),
                                style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }) { padding ->
                    Column(
                        modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Enable detailed logging", style = MaterialTheme.typography.titleMedium)
                            Switch(checked = enabled, onCheckedChange = { AppLog.setEnabled(it) })
                        }
                        Text("Private storage · 7 days · fatal crashes always captured",
                            style = MaterialTheme.typography.bodySmall)
                        Surface(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainer
                        ) {
                            Text(logText.ifBlank { "No saved diagnostic events." },
                                modifier = Modifier.verticalScroll(rememberScrollState()).padding(12.dp),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                context.getSystemService(ClipboardManager::class.java)
                                    ?.setPrimaryClip(ClipData.newPlainText("App diagnostic logs", AppLog.all()))
                            }) { Text("Copy") }
                            OutlinedButton(onClick = { AppLog.clear() }) { Text("Clear all") }
                            Button(onClick = { finish() }) { Text("Back") }
                        }
                    }
                }
            }
        }
    }
}
