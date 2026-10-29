package com.bcam.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Optional daily private logs; fatal crashes use a separate always-on journal. */
object AppLog {
    private const val MAX = 5000
    private const val RETENTION_DAYS = 7L
    private const val DAY_BYTES = 256 * 1024L
    private const val TOTAL_BYTES = 2 * 1024 * 1024L
    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private val io = Executors.newSingleThreadExecutor { task ->
        Thread(task, "bcam-log-writer").apply { isDaemon = true }
    }
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val date = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    @Volatile private var context: Context? = null
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    fun init(app: Context) {
        synchronized(lock) {
            if (context != null) return
            context = app.applicationContext
            val home = File(app.filesDir, "diagnostics").apply { mkdirs() }
            prune(home)
            home.listFiles()?.filter { it.name.matches(Regex("log-\\d{4}-\\d{2}-\\d{2}\\.txt")) }
                ?.sortedBy { it.name }?.forEach { file ->
                    file.forEachLine { appendMemory(it) }
                }
            listOf("crash-journal.log", "crash-journal-web.log").forEach { name ->
                File(app.filesDir, name).takeIf { it.exists() }
                    ?.forEachLine { appendMemory("[CRASH] " + it) }
            }
            _enabled.value = app.getSharedPreferences("diagnostic_settings", Context.MODE_PRIVATE)
                .getBoolean("enabled", false)
            _version.value++
        }
    }

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        context?.getSharedPreferences("diagnostic_settings", Context.MODE_PRIVATE)
            ?.edit()?.putBoolean("enabled", on)?.apply()
        if (on) add("LOG", "Logging enabled") else _version.value++
    }

    /** Never pass secrets or request bodies here: redaction is a defense in depth. */
    fun add(tag: String, message: String) {
        if (!_enabled.value) return
        val now = Date()
        val row: String
        val fileName: String
        synchronized(lock) {
            val safeTag = tag.take(24).replace(Regex("[^A-Za-z0-9_-]"), "_")
            row = clock.format(now) + " [" + safeTag + "] (" +
                Thread.currentThread().name.take(14) + ") " + redact(message)
                    .replace('\n', ' ').replace('\r', ' ').take(1000)
            fileName = "log-" + date.format(now) + ".txt"
            appendMemory(row)
            _version.value++
        }
        val app = context ?: return
        io.execute {
            try {
                val dir = File(app.filesDir, "diagnostics").apply { mkdirs() }
                val file = File(dir, fileName)
                if (file.length() + row.length * 4 > DAY_BYTES) {
                    val recent = file.readLines().takeLast(600)
                    file.writeText(recent.joinToString("\n", postfix = if (recent.isEmpty()) "" else "\n"))
                }
                file.appendText(row + "\n")
                prune(dir)
            } catch (_: Exception) { /* Logging must never crash the app. */ }
        }
    }

    internal fun redact(value: String): String = value
        .replace(Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/-]+"), "$1[REDACTED]")
        .replace(Regex("(?i)(token|key|password|secret|authorization)(\\s*[:=]\\s*)[^\\s&,;]+"), "$1$2[REDACTED]")
        .replace(Regex("(?i)https?://\\S+"), "[URL]")
        .replace(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[EMAIL]")
        .replace(Regex("(?<![A-Za-z0-9])\\+?[0-9][0-9() -]{7,}[0-9](?![A-Za-z0-9])"), "[NUMBER]")

    private fun appendMemory(row: String) {
        if (lines.size >= MAX) lines.removeFirst()
        lines.addLast(row)
    }

    private fun prune(dir: File) {
        val files = dir.listFiles()?.filter { it.name.matches(Regex("log-\\d{4}-\\d{2}-\\d{2}\\.txt")) }
            ?.sortedBy { it.name } ?: return
        val cutoff = System.currentTimeMillis() - RETENTION_DAYS * 86400000L
        files.forEach { if (it.lastModified() < cutoff) it.delete() }
        var total = files.filter { it.exists() }.sumOf { it.length() }
        files.forEach {
            if (total > TOTAL_BYTES && it.exists()) {
                total -= it.length()
                it.delete()
            }
        }
    }

    val size: Int get() = synchronized(lock) { lines.size }
    fun slice(from: Int, count: Int): List<String> = synchronized(lock) { lines.drop(from).take(count) }
    fun all(): String = synchronized(lock) { lines.joinToString("\n") }
    fun clear() {
        synchronized(lock) { lines.clear(); _version.value++ }
        val app = context ?: return
        io.execute {
            File(app.filesDir, "diagnostics").listFiles()?.filter {
                it.name.startsWith("log-")
            }?.forEach { it.delete() }
            File(app.filesDir, "crash-journal.log").delete()
            File(app.filesDir, "crash-journal-web.log").delete()
        }
    }
}
