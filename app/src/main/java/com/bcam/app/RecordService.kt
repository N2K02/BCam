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
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
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
import android.os.SystemClock
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
 *  - Splits automatically into parts (30 minutes by default) and keeps going until stopped.
 *  - Uses H.265/HEVC when the phone has a hardware HEVC encoder, otherwise H.264.
 *  - Notification has "Pause"/"Resume", "B/F" (switch camera) and "Stop"; tapping it opens Settings.
 *  - Pause keeps the same file: the recorder pauses and the camera stops sending frames.
 *  - Resolution, frame rate, quality, codec, sound and part length come from [VideoSettings].
 * All camera / recorder work happens on one background thread ([h]).
 */
class RecordService : Service() {

    companion object {
        private const val TAG = "BCam"
        const val ACTION_START = "com.bcam.app.START"
        const val ACTION_STOP = "com.bcam.app.STOP"
        const val ACTION_SWITCH = "com.bcam.app.SWITCH"
        const val ACTION_APPLY_SETTINGS = "com.bcam.app.APPLY_SETTINGS"
        const val ACTION_PAUSE_RESUME = "com.bcam.app.PAUSE_RESUME"

        private const val MAX_FILE_BYTES = 3_900_000_000L // stay under the 4 GB MP4 limit
        private const val CHANNEL = "recording"
        private const val NOTIF_ID = 1
        private const val RETRY_MS = 3000L
        private const val PREFS = VideoSettings.PREFS
        private const val KEY_FACING = "facing"

        @Volatile
        var isRunning = false
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
                h.post { stopped = false; startPart() }
            }
            ACTION_STOP -> {
                if (!isRunning) { stopSelf(); return START_NOT_STICKY }
                h.post { shutdown(userStopped = true) }
            }
            ACTION_SWITCH -> if (isRunning) h.post { switchCamera() }
            ACTION_PAUSE_RESUME -> if (isRunning) h.post { togglePause() }
            ACTION_APPLY_SETTINGS -> if (isRunning) h.post { applySettings() } else stopSelf()
            else -> if (!isRunning) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
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
        val other = if (facing == CameraCharacteristics.LENS_FACING_FRONT) "back" else "front"
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
            recording -> "$res$codec$mute · new file every ${settings.segmentMinutes} min · tap for settings"
            else -> "$camName camera · tap for settings"
        }

        val icon = Icon.createWithResource(this, R.drawable.ic_stat_rec)
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setColor(getColor(R.color.icon_primary))
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        // Android shows at most 3 buttons: Pause/Resume, B/F (switch camera), Stop.
        // Tapping the notification itself opens Settings.
        // Most phones show only the button text, so each label starts with a symbol; the icon is
        // used where Android does show it (some phones, watches).
        fun ic(res: Int) = Icon.createWithResource(this, res)
        if (recording) {
            b.addAction(
                if (paused) Notification.Action.Builder(ic(R.drawable.ic_play), "▶ Resume", servicePi(ACTION_PAUSE_RESUME, 4)).build()
                else Notification.Action.Builder(ic(R.drawable.ic_pause), "❚❚ Pause", servicePi(ACTION_PAUSE_RESUME, 4)).build(),
            )
        }
        if (hasBothCameras()) {
            b.addAction(Notification.Action.Builder(ic(R.drawable.ic_switch), "⟲ B/F", servicePi(ACTION_SWITCH, 2)).build())
        }
        b.addAction(Notification.Action.Builder(ic(R.drawable.ic_stop), "■ Stop", servicePi(ACTION_STOP, 1)).build())
        b.setContentIntent(settingsPi())
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

    private fun hasBothCameras(): Boolean = runCatching {
        val f = cm.cameraIdList.map { facingOf(it) }
        CameraCharacteristics.LENS_FACING_BACK in f && CameraCharacteristics.LENS_FACING_FRONT in f
    }.getOrDefault(false)

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

        val hevc = settings.preferHevc && hasHardwareEncoder(MediaFormat.MIMETYPE_VIDEO_HEVC, size, fps)
        return Config(size, hevc, fps, fpsRange, sensor, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO in afModes)
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
            // Assumes the phone is held upright (portrait), the usual way for pocket/background recording.
            r.setOrientationHint(cfg.orientation)
            r.setMaxDuration(settings.segmentMs)
            r.setMaxFileSize(MAX_FILE_BYTES)
            r.setOnInfoListener { mr, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                    what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED
                ) h.post { if (recorder === mr) rollover() }
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
    private fun finishPart() {
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
        if (keep) savedParts++
        o?.finish(keep)
        recStarted = false
        paused = false
        pausedTotalMs = 0L
        repeating = null
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
                pausedAt = System.currentTimeMillis()
                status = "Paused"
            } else {
                val req = repeating
                val s = session
                if (req != null && s != null) s.setRepeatingRequest(req, null, h)
                mr.resume()
                paused = false
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
        status = "Applying new settings…"
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

    private fun shutdown(userStopped: Boolean) {
        if (stopped) return
        stopped = true
        h.removeCallbacksAndMessages(null)
        finishPart()
        closeCamera()
        val saved = savedParts
        main.post {
            isRunning = false
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
