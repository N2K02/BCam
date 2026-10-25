package com.bcam.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.view.View
import android.widget.RemoteViews
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.BatteryManager
import android.os.SystemClock
import android.os.storage.StorageManager
import android.view.OrientationEventListener
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that records video with no preview.
 *  - Splits automatically into parts (about 30 minutes by default) and keeps going until stopped.
 *    The switch to the next file is seamless: the camera keeps running, so no footage is lost.
 *  - Records the way the phone is held when recording starts or settings change (portrait or landscape).
 *  - Stops by itself, saving the video, when storage or battery is about to run out.
 *  - Uses H.265/HEVC when the phone has a hardware HEVC encoder, otherwise H.264.
 *  - Notification has "Pause"/"Resume", "Stop" and "⚙ Settings"; the camera is chosen in Settings.
 *  - Pause keeps the same file: the recorder pauses and the camera stops sending frames.
 *  - Resolution, frame rate, quality, codec, sound and part length come from [VideoSettings].
 * All camera / recorder work happens on one background thread ([h]).
 */
class RecordService : Service() {

    companion object {
        private const val TAG = "BCam"
        const val ACTION_START = "com.bcam.app.START"
        const val ACTION_STOP = "com.bcam.app.STOP"
        const val ACTION_DISCARD_CURRENT = "com.bcam.app.DISCARD_CURRENT"
        const val ACTION_SWITCH = "com.bcam.app.SWITCH"
        const val ACTION_APPLY_SETTINGS = "com.bcam.app.APPLY_SETTINGS"
        const val ACTION_PAUSE_RESUME = "com.bcam.app.PAUSE_RESUME"
        const val ACTION_PAUSE = "com.bcam.app.PAUSE"
        const val ACTION_RESUME = "com.bcam.app.RESUME"

        private const val MAX_FILE_BYTES = 3_900_000_000L // stay under the 4 GB MP4 limit
        private const val CHANNEL = "recording"
        private const val NOTIF_ID = 1
        private const val RETRY_MS = 3000L
        /** Stop (and keep the video) when less than this much storage is left. */
        private const val MIN_FREE_BYTES = 300_000_000L
        /** Stop (and keep the video) at this battery level unless the phone is charging. */
        private const val MIN_BATTERY_PERCENT = 5
        private const val SAFETY_CHECK_MS = 30_000L
        private const val PREFS = VideoSettings.PREFS
        const val KEY_FACING = "facing"

        @Volatile
        var isRunning = false
        @Volatile var isPaused = false
            private set
    }

    /** Where one part is written. Android 10+: MediaStore (Movies/BCam, shows in Gallery). */
    private inner class Output(val uri: Uri?, val file: File?, val pfd: ParcelFileDescriptor?) {
        fun finish(keep: Boolean) {
            try { pfd?.close() } catch (_: Exception) {}
            val u: Uri? = uri
            if (u != null) {
                if (keep) {
                    val v = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                    runCatching { contentResolver.update(u, v, null, null) }
                } else {
                    runCatching { contentResolver.delete(u, null, null) }
                }
            } else if (file != null && !keep) {
                file.delete()
            }
        }
    }

    private class Config(
        val size: Size,
        val hevc: Boolean,
        val fps: Int,
        val fpsRange: Range<Int>?,
        val orientation: Int,
        val continuousAf: Boolean,
    )

    private lateinit var thread: HandlerThread
    private lateinit var h: Handler
    private val main = Handler(Looper.getMainLooper())
    private val cm by lazy { getSystemService(CameraManager::class.java) }
    private val nm by lazy { getSystemService(NotificationManager::class.java) }

    private var wakeLock: PowerManager.WakeLock? = null
    private var facing = CameraCharacteristics.LENS_FACING_BACK
    private var camera: CameraDevice? = null
    private var cameraId: String? = null
    private var opening = false
    private var session: CameraCaptureSession? = null
    private var recorder: MediaRecorder? = null
    private var recStarted = false
    private var out: Output? = null
    /** The file the recorder switches to when the current one is full (set just before the switch). */
    private var nextOut: Output? = null
    /** How the phone is held, from the accelerometer: 0, 90, 180 or 270 degrees. */
    @Volatile private var deviceDegrees = 0
    private var orientationListener: OrientationEventListener? = null
    private var token = 0           // bumped to invalidate stale async callbacks
    private var stopped = false
    private var part = 0
    private var savedParts = 0
    private var partStartElapsed = 0L
    private var partStartWall = 0L
    private var usingHevc = false
    private var settings = VideoSettings()
    private var activeSize: Size? = null
    private var activeFps = 30
    private var status = "Starting…"
    private var paused = false
    private var pausedAt = 0L          // wall time when the current pause began
    private var pausedTotalMs = 0L     // pauses in the current part, so the timer shows recorded time
    private var repeating: CaptureRequest? = null

    private val retry = Runnable { if (!stopped && recorder == null) startPart() }

    /** Every 30 s while recording: stop cleanly before storage or battery runs out. */
    private val safetyCheck = object : Runnable {
        override fun run() {
            if (stopped) return
            lowResourceReason()?.let { reason -> stopForSafety(reason); return }
            h.postDelayed(this, SAFETY_CHECK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("bcam-camera").also { it.start() }
        h = Handler(thread.looper)
        facing = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getInt(KEY_FACING, CameraCharacteristics.LENS_FACING_BACK)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while BCam is recording"
                setShowBadge(false)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (isRunning) return START_NOT_STICKY
                if (!goForeground()) { stopSelf(); return START_NOT_STICKY }
                isRunning = true
                acquireWakeLock()
                startOrientationListener()
                h.post {
                    stopped = false
                    val reason = lowResourceReason()
                    if (reason != null) { stopForSafety(reason); return@post }
                    startPart()
                    h.postDelayed(safetyCheck, SAFETY_CHECK_MS)
                }
            }
            ACTION_DISCARD_CURRENT -> {
                if (!isRunning) { stopSelf(); return START_NOT_STICKY }
                h.post { shutdown(userStopped = true, discardCurrent = true) }
            }
            ACTION_STOP -> {
                if (!isRunning) { stopSelf(); return START_NOT_STICKY }
                h.post { shutdown(userStopped = true) }
            }
            ACTION_SWITCH -> if (isRunning) h.post { switchCamera() }
            ACTION_PAUSE_RESUME -> if (isRunning) h.post { togglePause() }
            ACTION_PAUSE -> if (isRunning) h.post { if (!paused) togglePause() }
            ACTION_RESUME -> if (isRunning) h.post { if (paused) togglePause() }
            ACTION_APPLY_SETTINGS -> if (isRunning) h.post { applySettings() } else stopSelf()
            else -> if (!isRunning) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopOrientationListener()
        if (isRunning) h.post { shutdown(userStopped = false) }
        thread.quitSafely()
        releaseWakeLock()
        isRunning = false
        super.onDestroy()
    }

    // ---------------------------------------------------------------- foreground / notification

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private val withAudio get() = has(Manifest.permission.RECORD_AUDIO) && settings.audio

    private fun goForeground(): Boolean {
        if (!has(Manifest.permission.CAMERA)) return false
        settings = VideoSettings.load(this)
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                if (withAudio) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                startForeground(NOTIF_ID, buildNotification(), type)
            } else {
                startForeground(NOTIF_ID, buildNotification())
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            main.post { Toast.makeText(this, "BCam could not start: ${e.message}", Toast.LENGTH_LONG).show() }
            false
        }
    }

    private fun servicePi(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code, Intent(this, RecordService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun settingsPi(): PendingIntent =
        PendingIntent.getActivity(
            this, 3,
            Intent(this, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun buildNotification(): Notification {
        val camName = if (facing == CameraCharacteristics.LENS_FACING_FRONT) "Front" else "Back"
        val recording = recStarted
        val title = when {
            recording && paused -> "❚❚ Paused · $camName camera · Part $part"
            recording -> "● REC · $camName camera · Part $part"
            else -> "BCam · $status"
        }
        val codec = if (usingHevc) "H.265" else "H.264"
        val res = activeSize?.let { "${minOf(it.width, it.height)}p$activeFps · " } ?: ""
        val mute = if (withAudio) "" else " · no sound"
        val text = when {
            recording && paused -> "Tap Resume to continue in the same file"
            recording -> "$res$codec$mute · new file about every ${settings.segmentMinutes} min · tap for settings"
            else -> "$camName camera · tap for settings"
        }

        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setColor(getColor(R.color.icon_primary))
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        // Android controls the alignment of default Notification.Action labels.
        // A decorated custom view centers all text and gives each action equal
        // horizontal space, with large touch targets even on narrow screens.
        b.setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(recordingNotificationViews(title, text, recording, expanded = false))
            .setCustomBigContentView(recordingNotificationViews(title, text, recording, expanded = true))
            .setContentIntent(settingsPi())
        if (recording && !paused) {
            // Timer shows recorded time: pauses are skipped.
            b.setUsesChronometer(true).setShowWhen(true).setWhen(partStartWall + pausedTotalMs)
        } else {
            b.setShowWhen(false)
        }
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return b.build()
    }

    /** Keep compact and expanded notifications in sync with the recorder state. */
    private fun recordingNotificationViews(
        title: String,
        detail: String,
        recording: Boolean,
        expanded: Boolean,
    ): RemoteViews = RemoteViews(packageName, R.layout.notification_recording).apply {
        setTextViewText(R.id.notification_title, title)
        setTextViewText(R.id.notification_detail, detail)
        setViewVisibility(R.id.notification_actions, if (expanded) View.VISIBLE else View.GONE)
        // Pause/Resume is unavailable until the recorder actually starts.
        setViewVisibility(R.id.notification_pause, if (recording) View.VISIBLE else View.GONE)
        setTextViewText(R.id.notification_pause, if (paused) "▶ Resume" else "❚❚ Pause")
        setOnClickPendingIntent(R.id.notification_root, settingsPi())
        setOnClickPendingIntent(R.id.notification_pause, servicePi(ACTION_PAUSE_RESUME, 4))
        setOnClickPendingIntent(R.id.notification_stop, servicePi(ACTION_STOP, 1))
        setOnClickPendingIntent(R.id.notification_settings, settingsPi())
    }

    private fun updateNotification() {
        if (isRunning && !stopped) nm.notify(NOTIF_ID, buildNotification())
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BCam:recording")
            .also { it.acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // ---------------------------------------------------------------- camera selection

    private fun facingOf(id: String) = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)


    private fun pickCamera(): String? = runCatching {
        val ids = cm.cameraIdList
        ids.firstOrNull { facingOf(it) == facing } ?: ids.firstOrNull()?.also { facing = facingOf(it) ?: facing }
    }.getOrNull()

    private fun chooseConfig(id: String): Config {
        val c = cm.getCameraCharacteristics(id)
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val sizes = map.getOutputSizes(MediaRecorder::class.java).toList()
        // Sizes are landscape (e.g. 1920x1080); keep the largest one up to the chosen height, 16:9 preferred.
        val maxH = settings.resolution
        val maxW = maxH * 16 / 9
        val fitting = sizes.filter { it.width <= maxW && it.height <= maxH }
        val wanted = fitting.filter { it.width * 9 == it.height * 16 }.maxByOrNull { it.width * it.height }
            ?: fitting.maxByOrNull { it.width * it.height }
            ?: sizes.minBy { it.width * it.height }

        // Use the chosen frame rate if the camera supports it, otherwise 30 (or whatever it can do).
        val ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
        val fpsRange = ranges.filter { it.upper == settings.fps }.maxByOrNull { it.lower }
            ?: ranges.filter { it.upper == 30 }.maxByOrNull { it.lower }
        val fps = fpsRange?.upper ?: 30

        // If no hardware encoder handles that size/fps, step down to a size that one does.
        var size = wanted
        if (!hasHardwareEncoder(MediaFormat.MIMETYPE_VIDEO_AVC, size, fps)) {
            size = sizes.filter { it.width * it.height < wanted.width * wanted.height }
                .sortedByDescending { it.width * it.height }
                .firstOrNull { hasHardwareEncoder(MediaFormat.MIMETYPE_VIDEO_AVC, it, fps) }
                ?: wanted
        }

        val afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList().orEmpty()
        val sensor = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        // Match how the phone is held right now (front camera turns the other way).
        val front = c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        val device = if (front) -deviceDegrees else deviceDegrees
        val orientation = (sensor + device + 360) % 360

        val hevc = settings.preferHevc && hasHardwareEncoder(MediaFormat.MIMETYPE_VIDEO_HEVC, size, fps)
        return Config(size, hevc, fps, fpsRange, orientation, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO in afModes)
    }

    /** True if the phone has a *hardware* encoder for [mime] at [size] and [fps] (software encoders are too slow). */
    private fun hasHardwareEncoder(mime: String, size: Size, fps: Int): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder &&
                info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                isHardware(info) &&
                info.getCapabilitiesForType(mime).videoCapabilities
                    ?.areSizeAndRateSupported(size.width, size.height, fps.toDouble()) == true
        }
    }.getOrDefault(false)

    private fun isHardware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated
        val n = info.name.lowercase(Locale.US)
        return !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw."))
    }

    // ---------------------------------------------------------------- recording

    private fun startPart() {
        if (stopped || recorder != null) return
        val id = pickCamera() ?: return fail("No camera found")
        val cam = camera
        if (cam == null || cameraId != id) {
            closeCamera()
            openCamera(id)
            return
        }

        val cfg = try { chooseConfig(id) } catch (e: Exception) { return fail("Camera info error: ${e.message}") }

        // Try HEVC first (if a hardware encoder exists), then fall back to H.264.
        var rec: MediaRecorder? = null
        for (useHevc in if (cfg.hevc) listOf(true, false) else listOf(false)) {
            rec = try { buildRecorder(cfg, useHevc) } catch (e: Exception) {
                Log.w(TAG, "Recorder prepare failed (hevc=$useHevc)", e)
                out?.finish(keep = false); out = null
                null
            }
            if (rec != null) { usingHevc = useHevc; activeSize = cfg.size; activeFps = cfg.fps; break }
        }
        val mr: MediaRecorder = rec ?: return fail("Video encoder could not start")
        recorder = mr
        recStarted = false
        val t = ++token
        val surface = mr.surface

        try {
            @Suppress("DEPRECATION")
            cam.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (t != token || stopped) { s.close(); return }
                    session = s
                    try {
                        val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(surface)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            if (cfg.continuousAf) set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            cfg.fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                        }
                        val built = req.build()
                        repeating = built
                        s.setRepeatingRequest(built, null, h)
                        mr.start()
                        recStarted = true
                        paused = false
        isPaused = false
                        pausedTotalMs = 0L
                        part++
                        partStartElapsed = SystemClock.elapsedRealtime()
                        partStartWall = System.currentTimeMillis()
                        status = "Recording"
                        updateNotification()
                    } catch (e: Exception) {
                        Log.e(TAG, "Start recording failed", e)
                        finishPart()
                        retryLater("Could not start recording, retrying…")
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (t != token) return
                    finishPart()
                    retryLater("Camera session failed, retrying…")
                }
            }, h)
        } catch (e: Exception) {
            Log.e(TAG, "createCaptureSession failed", e)
            finishPart()
            closeCamera()
            retryLater("Camera busy, retrying…")
        }
    }

    private fun buildRecorder(cfg: Config, hevc: Boolean): MediaRecorder {
        val output = newOutput()
        out = output
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            val audio = withAudio
            if (audio) r.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            if (output.pfd != null) r.setOutputFile(output.pfd.fileDescriptor) else r.setOutputFile(output.file!!.absolutePath)

            val w = cfg.size.width
            val ht = cfg.size.height
            // Bitrate from the quality setting (bits per pixel per frame; HEVC needs ~40% less).
            // Standard 1080p30 => ~5 Mbps HEVC (~1.1 GB / 30 min) or ~8.7 Mbps H.264.
            val bitrate = settings.videoBitrate(w, ht, cfg.fps, hevc)
            r.setVideoEncoder(if (hevc) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264)
            r.setVideoSize(w, ht)
            r.setVideoFrameRate(cfg.fps)
            r.setVideoEncodingBitRate(bitrate)

            if (audio) {
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioSamplingRate(48000)
                r.setAudioChannels(1)
                r.setAudioEncodingBitRate(VideoSettings.AUDIO_BITRATE)
            }
            // The way the phone was held when this file started (see chooseConfig).
            r.setOrientationHint(cfg.orientation)
            // Parts are cut by size, sized to about the chosen length: Android can only switch to
            // the next file without stopping the camera when a size limit is reached.
            val bitsPerSecond = bitrate.toLong() + if (audio) VideoSettings.AUDIO_BITRATE else 0
            val segmentBytes = (bitsPerSecond / 8 * settings.segmentMinutes * 60).coerceIn(50_000_000L, MAX_FILE_BYTES)
            r.setMaxFileSize(segmentBytes)
            r.setOnInfoListener { mr, what, _ ->
                when (what) {
                    // Nearly full: hand the recorder the next file now, so it continues without a gap.
                    MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING ->
                        h.post { if (recorder === mr) prepareNextFile(mr) }
                    MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED ->
                        h.post { if (recorder === mr) nextFileStarted() }
                    // Only when the seamless switch wasn't possible: restart with a new file.
                    MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED,
                    MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED ->
                        h.post { if (recorder === mr) rollover() }
                }
            }
            r.setOnErrorListener { mr, what, extra ->
                Log.e(TAG, "MediaRecorder error $what/$extra")
                h.post {
                    if (recorder === mr) { finishPart(); retryLater("Recorder error, restarting…") }
                }
            }
            r.prepare()
            return r
        } catch (e: Exception) {
            r.release()
            throw e
        }
    }

    private fun newOutput(): Output {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val cam = if (facing == CameraCharacteristics.LENS_FACING_FRONT) "front" else "back"
        val name = "BCam_${stamp}_$cam.mp4"
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/BCam")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values
            ) ?: throw IllegalStateException("Cannot create video file")
            val pfd = contentResolver.openFileDescriptor(uri, "rw")
                ?: run { contentResolver.delete(uri, null, null); throw IllegalStateException("Cannot open video file") }
            Output(uri, null, pfd)
        } else {
            val dir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "BCam").apply { mkdirs() }
            Output(null, File(dir, name), null)
        }
    }

    /** Close the current part and save it (unless it never really started). */
    private fun finishPart(discard: Boolean = false) {
        token++
        val r = recorder
        val s = session
        val o = out
        recorder = null; session = null; out = null
        var keep = false
        if (r != null) {
            if (recStarted) {
                keep = try {
                    r.stop(); true
                } catch (e: RuntimeException) {
                    // stop() throws if almost nothing was recorded, or if the recorder
                    // already stopped itself at the 30-min limit (file is fine then).
                    SystemClock.elapsedRealtime() - partStartElapsed > 2000
                }
            }
            runCatching { s?.stopRepeating() }
            runCatching { s?.close() }
            runCatching { r.reset() }
            r.release()
        } else {
            runCatching { s?.close() }
        }
        if (keep && !discard) savedParts++
        o?.finish(keep && !discard)
        // A next file that was set up but never started is empty.
        nextOut?.finish(keep = false)
        nextOut = null
        recStarted = false
        paused = false
        pausedTotalMs = 0L
        repeating = null
    }

    /** The current file is nearly full: give the recorder the next one so it can switch seamlessly. */
    private fun prepareNextFile(mr: MediaRecorder) {
        if (stopped || nextOut != null) return
        val o = try { newOutput() } catch (e: Exception) {
            Log.w(TAG, "Next file could not be created; the part will restart instead", e)
            return
        }
        try {
            if (o.pfd != null) mr.setNextOutputFile(o.pfd.fileDescriptor) else mr.setNextOutputFile(o.file!!)
            nextOut = o
        } catch (e: Exception) {
            Log.w(TAG, "setNextOutputFile failed; the part will restart instead", e)
            o.finish(keep = false)
        }
    }

    /** The recorder moved on to the next file: save the finished one and count a new part. */
    private fun nextFileStarted() {
        val next = nextOut ?: return
        val finished = out
        out = next
        nextOut = null
        finished?.finish(keep = true)
        savedParts++
        part++
        partStartElapsed = SystemClock.elapsedRealtime()
        partStartWall = System.currentTimeMillis()
        pausedTotalMs = 0L
        updateNotification()
    }

    /** Why recording must stop now (storage or battery nearly gone), or null when it can go on. */
    private fun lowResourceReason(): String? {
        val free = runCatching {
            getSystemService(StorageManager::class.java).getAllocatableBytes(StorageManager.UUID_DEFAULT)
        }.getOrElse { runCatching { android.os.StatFs(filesDir.path).availableBytes }.getOrDefault(Long.MAX_VALUE) }
        if (free < MIN_FREE_BYTES) return "storage is almost full"
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (battery != null) {
            val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            if (level >= 0 && scale > 0 && !plugged && level * 100 / scale <= MIN_BATTERY_PERCENT) return "the battery is almost empty"
        }
        return null
    }

    private fun stopForSafety(reason: String) {
        Log.w(TAG, "Stopping: $reason")
        val saved = savedParts + if (recStarted) 1 else 0
        main.post {
            Toast.makeText(
                this,
                if (saved > 0) "BCam stopped because $reason. Your video was saved." else "BCam can't record: $reason.",
                Toast.LENGTH_LONG,
            ).show()
        }
        shutdown(userStopped = false)
    }

    private fun startOrientationListener() {
        if (orientationListener != null) return
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                // Flat on a table reports UNKNOWN: keep the last real reading.
                if (degrees == ORIENTATION_UNKNOWN) return
                deviceDegrees = ((degrees + 45) / 90 * 90) % 360
            }
        }.also { if (it.canDetectOrientation()) it.enable() }
    }

    private fun stopOrientationListener() {
        orientationListener?.disable()
        orientationListener = null
    }

    /** Pause: the recorder pauses (same file) and the camera stops sending frames. Resume continues. */
    private fun togglePause() {
        val mr = recorder ?: return
        if (stopped || !recStarted) return
        try {
            if (!paused) {
                mr.pause()
                runCatching { session?.stopRepeating() }
                paused = true
                isPaused = true
                pausedAt = System.currentTimeMillis()
                status = "Paused"
            } else {
                val req = repeating
                val s = session
                if (req != null && s != null) s.setRepeatingRequest(req, null, h)
                mr.resume()
                paused = false
                isPaused = false
                pausedTotalMs += System.currentTimeMillis() - pausedAt
                status = "Recording"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Pause/resume failed", e)
            main.post { Toast.makeText(this, "BCam: couldn't ${if (paused) "resume" else "pause"} (${e.message})", Toast.LENGTH_SHORT).show() }
        }
        updateNotification()
    }

    /** 30 minutes reached: save this part and immediately begin the next one. */
    private fun rollover() {
        if (stopped || recorder == null) return
        finishPart()
        startPart()
    }

    /** Settings changed from the dialog: save the current part and continue with the new settings. */
    private fun applySettings() {
        if (stopped) return
        settings = VideoSettings.load(this)
        h.removeCallbacks(retry)
        finishPart()
        // Camera chosen in Settings: the two cameras record at different angles and sizes,
        // so the other camera continues in a new file.
        val wanted = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_FACING, facing)
        status = if (wanted != facing) {
            facing = wanted
            closeCamera()
            "Switching camera…"
        } else {
            "Applying new settings…"
        }
        updateNotification()
        startPart()
    }

    private fun switchCamera() {
        if (stopped) return
        facing = if (facing == CameraCharacteristics.LENS_FACING_FRONT)
            CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_FACING, facing).apply()
        h.removeCallbacks(retry)
        finishPart()
        closeCamera()
        status = "Switching camera…"
        updateNotification()
        startPart()
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(id: String) {
        if (opening || stopped) return
        opening = true
        status = "Opening camera…"
        try {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) {
                    opening = false
                    if (stopped) { c.close(); return }
                    camera = c
                    cameraId = id
                    startPart()
                }

                override fun onDisconnected(c: CameraDevice) {
                    opening = false
                    lost(c, "Camera taken by another app, waiting…")
                }

                override fun onError(c: CameraDevice, error: Int) {
                    opening = false
                    lost(c, "Camera unavailable (error $error), retrying…")
                }
            }, h)
        } catch (e: CameraAccessException) {
            opening = false
            retryLater("Camera busy, retrying…")
        } catch (e: SecurityException) {
            opening = false
            fail("Camera permission missing")
        } catch (e: Exception) {
            opening = false
            retryLater("Camera error, retrying…")
        }
    }

    private fun lost(c: CameraDevice, msg: String) {
        if (camera === c || camera == null) {
            finishPart()
            camera = null
            cameraId = null
        }
        runCatching { c.close() }
        retryLater(msg)
    }

    private fun closeCamera() {
        runCatching { camera?.close() }
        camera = null
        cameraId = null
    }

    private fun retryLater(msg: String) {
        if (stopped) return
        status = msg
        updateNotification()
        h.removeCallbacks(retry)
        h.postDelayed(retry, RETRY_MS)
    }

    private fun fail(msg: String) {
        Log.e(TAG, msg)
        main.post { Toast.makeText(this, "BCam: $msg", Toast.LENGTH_LONG).show() }
        shutdown(userStopped = false)
    }

    private fun shutdown(userStopped: Boolean, discardCurrent: Boolean = false) {
        if (stopped) return
        stopped = true
        h.removeCallbacksAndMessages(null)
        finishPart(discard = discardCurrent)
        closeCamera()
        val saved = savedParts
        main.post {
            isRunning = false
            stopOrientationListener()
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            if (userStopped) {
                val where = if (Build.VERSION.SDK_INT >= 29) "Movies/BCam" else "Android/data/com.bcam.app/files/Movies/BCam"
                val msg = if (saved == 1) "BCam saved 1 video to $where" else "BCam saved $saved videos to $where"
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }
    }
}
