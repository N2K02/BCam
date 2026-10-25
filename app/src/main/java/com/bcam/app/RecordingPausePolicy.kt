package com.bcam.app

/**
 * Pure control-state policy shared by the overlay and notification.
 * Pending pause means the user pressed Pause before the camera was ready,
 * NOT that MediaRecorder already paused.
 */
internal object RecordingPausePolicy {
    const val MIN_RECORDING_BEFORE_PAUSE_MS = 750L

    fun showsResume(paused: Boolean, pendingPause: Boolean): Boolean =
        paused || pendingPause

    fun pauseDelayMs(startedAtElapsedMs: Long, nowElapsedMs: Long): Long =
        (MIN_RECORDING_BEFORE_PAUSE_MS - (nowElapsedMs - startedAtElapsedMs))
            .coerceAtLeast(0L)
}
