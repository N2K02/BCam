package com.bcam.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout

/**
 * VREC-style floating controls. Close only hides controls; Stop saves the video.
 * Android's overlay special access must be granted separately from the default-on setting.
 */
class OverlayControlService : Service() {
    companion object {
        const val ACTION_SHOW = "com.bcam.app.overlay.SHOW"
        const val ACTION_DISMISS = "com.bcam.app.overlay.DISMISS"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val positions by lazy { getSharedPreferences("bcam_overlay_position", MODE_PRIVATE) }
    private lateinit var windows: WindowManager
    private var root: LinearLayout? = null
    private var statusDot: RecordingStatusDot? = null
    private var pauseButton: ImageButton? = null
    private var stopButton: ImageButton? = null
    private var blink: ObjectAnimator? = null
    private var stopping = false

    private val refresh = object : Runnable {
        override fun run() {
            if (!RecordService.isRunning || !VideoSettings.load(this@OverlayControlService).overlayEnabled) {
                removeOverlay()
                stopSelf()
                return
            }
            when {
                stopping -> renderSaving()
                !RecordService.isRecordingActive -> renderPreparing()
                RecordService.isPaused -> renderPaused()
                else -> renderRecording()
            }
            handler.postDelayed(this, 300L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISMISS) {
            removeOverlay()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_SHOW || !VideoSettings.load(this).overlayEnabled ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this))) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (root == null) createOverlay()
        if (root == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        handler.removeCallbacks(refresh)
        handler.post(refresh)
        return START_NOT_STICKY
    }

    private fun createOverlay() {
        windows = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), dp(4), dp(5), dp(4))
            background = GradientDrawable().apply {
                setColor(0xF2222222.toInt())
                cornerRadius = dp(28).toFloat()
                setStroke(dp(1), 0xFF666666.toInt())
            }
            elevation = dp(8).toFloat()
        }

        val dot = RecordingStatusDot(this).apply {
            contentDescription = "Recording status. Drag to move controls"
        }
        val pause = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_pause)
            contentDescription = "Pause recording"
            background = ColorDrawable(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            setOnClickListener {
                if (stopping || !RecordService.isRecordingActive) return@setOnClickListener
                isEnabled = false
                val action = if (RecordService.isPaused) RecordService.ACTION_RESUME else RecordService.ACTION_PAUSE
                startService(Intent(this@OverlayControlService, RecordService::class.java).setAction(action))
            }
        }
        val stop = ImageButton(this).apply {
            setImageResource(R.drawable.ic_stop)
            contentDescription = "Stop and save recording"
            background = ColorDrawable(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            setOnClickListener {
                if (stopping || !RecordService.isRunning) return@setOnClickListener
                stopping = true
                renderSaving()
                startService(Intent(this@OverlayControlService, RecordService::class.java)
                    .setAction(RecordService.ACTION_STOP))
            }
        }
        val close = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            contentDescription = "Close recording overlay, keep recording"
            background = ColorDrawable(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            setOnClickListener {
                // This does NOT stop the recorder; the ongoing notification remains.
                removeOverlay()
                stopSelf()
            }
        }
        val grip = OverlayDragHandle(this).apply {
            contentDescription = "Move floating recording controls"
        }
        panel.addView(dot, LinearLayout.LayoutParams(dp(44), dp(44)))
        panel.addView(pause, LinearLayout.LayoutParams(dp(44), dp(44)))
        panel.addView(stop, LinearLayout.LayoutParams(dp(44), dp(44)))
        panel.addView(close, LinearLayout.LayoutParams(dp(44), dp(44)))
        panel.addView(grip, LinearLayout.LayoutParams(dp(44), dp(44)))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = positions.getInt("x", dp(12))
            y = positions.getInt("y", dp(160))
        }
        val dragListener = object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var originalX = 0
            private var originalY = 0

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        originalX = params.x
                        originalY = params.y
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val (width, height) = screenSize()
                        val position = OverlayDragPosition.clamp(
                            originalX + (event.rawX - downX).toInt(),
                            originalY + (event.rawY - downY).toInt(),
                            width, height, panel.width, panel.height)
                        params.x = position.x
                        params.y = position.y
                        runCatching { windows.updateViewLayout(panel, params) }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        positions.edit().putInt("x", params.x).putInt("y", params.y).apply()
                        if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                        return true
                    }
                }
                return false
            }
        }
        dot.setOnTouchListener(dragListener)
        grip.setOnTouchListener(dragListener)

        val (screenWidth, screenHeight) = screenSize()
        OverlayDragPosition.clamp(params.x, params.y, screenWidth, screenHeight,
            dp(44) * 5 + dp(10), dp(44) + dp(8)).let {
            params.x = it.x
            params.y = it.y
        }

        root = panel
        statusDot = dot
        pauseButton = pause
        stopButton = stop
        runCatching { windows.addView(panel, params) }.onFailure {
            root = null
            statusDot = null
            pauseButton = null
            stopButton = null
            stopSelf()
        }
    }

    private fun renderRecording() {
        statusDot?.apply {
            setStatusColor(Color.RED)
            contentDescription = "Recording. Drag controls"
        }
        if (blink?.isRunning != true) {
            statusDot?.let { dot ->
                blink = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.25f).apply {
                    duration = 650L
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    start()
                }
            }
        }
        pauseButton?.apply {
            setImageResource(android.R.drawable.ic_media_pause)
            contentDescription = "Pause recording"
            isEnabled = true
            alpha = 1f
        }
        stopButton?.apply { isEnabled = true; alpha = 1f }
    }

    private fun renderPaused() {
        stopBlinking()
        statusDot?.apply {
            setStatusColor(0xFFFF9800.toInt())
            contentDescription = "Recording paused. Drag controls"
        }
        pauseButton?.apply {
            setImageResource(android.R.drawable.ic_media_play)
            contentDescription = "Resume recording"
            isEnabled = true
            alpha = 1f
        }
        stopButton?.apply { isEnabled = true; alpha = 1f }
    }

    private fun renderPreparing() {
        stopBlinking()
        statusDot?.apply {
            setStatusColor(0xFF2196F3.toInt())
            contentDescription = "Preparing recording. Drag controls"
        }
        pauseButton?.apply {
            setImageResource(android.R.drawable.ic_media_pause)
            contentDescription = "Recording not yet ready"
            isEnabled = false
            alpha = 0.45f
        }
        // A stalled camera may still need to be stopped before capture starts.
        stopButton?.apply { isEnabled = true; alpha = 1f }
    }

    private fun renderSaving() {
        stopBlinking()
        statusDot?.apply {
            setStatusColor(0xFF4CAF50.toInt())
            contentDescription = "Saving recording. Drag controls"
        }
        pauseButton?.apply { isEnabled = false; alpha = 0.45f }
        stopButton?.apply { isEnabled = false; alpha = 0.45f }
    }

    private fun stopBlinking() {
        blink?.cancel()
        blink = null
        statusDot?.alpha = 1f
    }

    private fun screenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windows.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            @Suppress("DEPRECATION")
            resources.displayMetrics.run { widthPixels to heightPixels }
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun removeOverlay() {
        handler.removeCallbacks(refresh)
        stopBlinking()
        root?.let { runCatching { windows.removeView(it) } }
        root = null
        statusDot = null
        pauseButton = null
        stopButton = null
        stopping = false
    }

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }
}

/** Draw circles rather than font glyphs to keep the status and grip centered. */
private class RecordingStatusDot(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED }
    private val radius = 7f * resources.displayMetrics.density
    init { isClickable = true }
    fun setStatusColor(color: Int) { paint.color = color; invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawCircle(width / 2f, height / 2f, radius, paint)
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}

private class OverlayDragHandle(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFCCCCCC.toInt() }
    private val radius = 2.2f * resources.displayMetrics.density
    private val spacing = 7f * resources.displayMetrics.density
    init { isClickable = true }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (index in -1..1) {
            canvas.drawCircle(width / 2f, height / 2f + index * spacing, radius, paint)
        }
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
