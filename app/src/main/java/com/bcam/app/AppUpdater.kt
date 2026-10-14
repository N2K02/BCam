package com.bcam.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * In-app updates from BCam's GitHub Releases (the same APK the release page offers).
 *
 * BCam has no screen, so:
 * - Tapping the app icon checks GitHub in the background (at most every 12 hours). If a newer
 *   release exists, an "Update available" notification appears.
 * - The notification opens the ⚙ Settings dialog, whose Updates card ([UpdateSection]) shows
 *   what's new, downloads with progress, verifies and installs.
 *
 * Verified before installing: size and SHA-256 (from GitHub), same package, newer versionCode,
 * same signing key. Installed through Android's own package installer screen.
 */
object AppUpdater {
    const val REPO = "N2K02/bcam"
    private const val APP_NAME = "BCam"
    private const val CHECK_EVERY_MS = 12 * 60 * 60 * 1000L
    private const val CHANNEL = "updates"
    const val NOTIF_ID = 4242

    class Release(
        val tag: String,
        val versionName: String,
        val versionCode: Long,
        val notes: List<String>,
        val apkUrl: String,
        val apkName: String,
        val apkSize: Long,
        val sha256: String?,
        val pageUrl: String,
    )

    private fun prefs(c: Context) = c.getSharedPreferences("app_updater", Context.MODE_PRIVATE)

    fun installedVersionName(c: Context): String =
        runCatching { packageInfo(c, c.packageName).versionName }.getOrNull().orEmpty()

    private fun installedVersionCode(c: Context): Long = versionCodeOf(packageInfo(c, c.packageName))

    /** On tapping the app icon: check in the background if due, and notify when there's an update. */
    fun checkInBackgroundIfDue(context: Context) {
        val app = context.applicationContext
        val p = prefs(app)
        // An update that installed: remove its downloaded APK.
        if (p.getString("installedVersion", null) != installedVersionName(app)) {
            runCatching { updateDir(app).deleteRecursively() }
            p.edit().putString("installedVersion", installedVersionName(app)).apply()
        }
        if (System.currentTimeMillis() - p.getLong("lastCheck", 0L) < CHECK_EVERY_MS) return
        Thread {
            val release = runCatching { latestRelease() }.getOrNull()
            p.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
            if (release != null && release.versionCode > installedVersionCode(app) &&
                p.getString("skipped", null) != release.tag
            ) notifyAvailable(app, release)
        }.start()
    }

    fun skip(c: Context, release: Release) {
        prefs(c).edit().putString("skipped", release.tag).apply()
        c.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }

    private fun notifyAvailable(c: Context, release: Release) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            c, 0,
            Intent(c, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_CHECK_UPDATES, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setContentTitle("$APP_NAME ${release.versionName} is available")
            .setContentText(release.notes.firstOrNull() ?: "Tap to see what's new and update")
            .setStyle(Notification.BigTextStyle().bigText(
                (release.notes.take(5).joinToString("\n") { "• $it" }).ifEmpty { "Tap to see what's new and update" },
            ))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID, n)
    }

    // ---------------------------------------------------------------- GitHub (blocking: call off the main thread)

    fun latestRelease(): Release? {
        val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            c.setRequestProperty("User-Agent", "$APP_NAME-updater (Android)")
            if (c.responseCode == 404) return null
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
            val tag = o.optString("tag_name")
            val name = tag.removePrefix("v")
            val code = versionCodeFromName(name) ?: return null
            val assets = o.optJSONArray("assets") ?: return null
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) } ?: return null
            return Release(
                tag = tag,
                versionName = name,
                versionCode = code,
                notes = whatsNew(o.optString("body")),
                apkUrl = apk.optString("browser_download_url"),
                apkName = apk.optString("name"),
                apkSize = apk.optLong("size"),
                sha256 = apk.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase(),
                pageUrl = o.optString("html_url"),
            )
        } finally {
            c.disconnect()
        }
    }

    fun isNewer(c: Context, release: Release) = release.versionCode > installedVersionCode(c)

    /** Same rule as the build: "1.8" -> 18, "2.0" -> 20. */
    private fun versionCodeFromName(name: String): Long? {
        val m = Regex("""^(\d+)\.(\d)$""").find(name.trim()) ?: return null
        return m.groupValues[1].toLong() * 10 + m.groupValues[2].toLong()
    }

    private fun whatsNew(body: String): List<String> {
        val lines = body.lineSequence().map { it.trim() }
        val section = lines.dropWhile { !it.startsWith("### What's new", ignoreCase = true) }.drop(1)
            .takeWhile { !it.startsWith("#") }.toList()
            .ifEmpty { body.lines().map { it.trim() } }
        return section.filter { it.startsWith("- ") || it.startsWith("* ") }
            .map { it.drop(2).trim() }
            .filterNot { it.contains("[skip ci]") || it.startsWith("README", ignoreCase = true) }
            .take(12)
    }

    // ---------------------------------------------------------------- download + verify (blocking)

    private fun updateDir(c: Context) = File(c.cacheDir, UpdateFileProvider.DIR)

    fun apkFile(c: Context, release: Release) =
        File(updateDir(c), release.apkName.replace(Regex("[^A-Za-z0-9._-]"), "_"))

    /** Downloads the APK; [progress] gets 0..1, or null when the size is unknown. */
    fun download(c: Context, release: Release, progress: (Float?) -> Unit, cancelled: () -> Boolean): File {
        val file = apkFile(c, release)
        if (file.exists() && verify(c, file, release) == null) return file
        updateDir(c).mkdirs()
        updateDir(c).listFiles()?.filter { it != file }?.forEach { it.delete() }
        var url = URL(release.apkUrl)
        var conn: HttpURLConnection
        var hops = 0
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "$APP_NAME-updater (Android)")
            val code = conn.responseCode
            if (code in 300..399 && hops++ < 5) {
                val next = conn.getHeaderField("Location") ?: throw IOException("Redirect without location")
                conn.disconnect()
                url = URL(url, next)
                if (url.protocol != "https") throw IOException("Insecure download link")
                continue
            }
            if (code != 200) throw IOException("HTTP $code")
            break
        }
        try {
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize.takeIf { it > 0 }
            val tmp = File(file.path + ".part")
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = 0L
                    progress(if (total != null) 0f else null)
                    while (true) {
                        if (cancelled()) throw IOException("Cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total != null && done - last > 128 * 1024) {
                            last = done
                            progress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            if (!tmp.renameTo(file)) throw IOException("Couldn't save the download")
        } finally {
            conn.disconnect()
        }
        return file
    }

    /** Null if the APK is good to install, else what's wrong (in plain words). */
    fun verify(c: Context, file: File, release: Release): String? {
        if (release.apkSize > 0 && file.length() != release.apkSize) return "The download was incomplete. Please try again."
        release.sha256?.let { if (sha256(file) != it) return "The download is damaged (checksum mismatch). Please try again." }
        val apk = archiveInfo(c, file) ?: return "The downloaded file isn't a valid app."
        if (apk.packageName != c.packageName) return "The downloaded file is a different app."
        if (versionCodeOf(apk) <= installedVersionCode(c)) return "The downloaded version isn't newer than this one."
        val mine = signerDigests(packageInfo(c, c.packageName, signatures = true))
        val theirs = signerDigests(apk)
        if (mine.isNotEmpty() && theirs.isNotEmpty() && mine.intersect(theirs).isEmpty()) {
            return "This update is signed with a different key, so Android can't install it over the current app."
        }
        return null
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ---------------------------------------------------------------- install

    fun canInstall(c: Context) = c.packageManager.canRequestPackageInstalls()

    /**
     * The intent that opens Android's own package installer for the downloaded APK (the same
     * screen as tapping a downloaded APK). Play Protect, if it wants to scan, asks there.
     */
    fun installIntent(c: Context, file: File): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(UpdateFileProvider.uriFor(c, file), "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    // ---------------------------------------------------------------- package info helpers

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    @SuppressLint("PackageManagerGetSignatures")
    @Suppress("DEPRECATION")
    private fun packageInfo(c: Context, pkg: String, signatures: Boolean = false): PackageInfo {
        val flags = if (!signatures) 0 else if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return if (Build.VERSION.SDK_INT >= 33) {
            c.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            c.packageManager.getPackageInfo(pkg, flags)
        }
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(c: Context, file: File): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return if (Build.VERSION.SDK_INT >= 33) {
            c.packageManager.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            c.packageManager.getPackageArchiveInfo(file.path, flags)
        }
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val s = info.signingInfo ?: return emptySet()
            if (s.hasMultipleSigners()) s.apkContentsSigners else s.signingCertificateHistory
        } else {
            info.signatures
        } ?: return emptySet()
        val md = MessageDigest.getInstance("SHA-256")
        return sigs.map { sig -> md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }
}
