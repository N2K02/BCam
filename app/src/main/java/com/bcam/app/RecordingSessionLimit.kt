package com.bcam.app

/** Wall-clock independent limit for one session, including pauses and camera restarts. */
internal class RecordingSessionLimit(startElapsedMs: Long, limitMinutes: Int) {
    private val deadlineElapsedMs = if (limitMinutes > 0) startElapsedMs + limitMinutes * 60_000L else null

    /** Null is Unlimited; zero means the session must stop. */
    fun remainingMs(nowElapsedMs: Long): Long? = deadlineElapsedMs?.let { (it - nowElapsedMs).coerceAtLeast(0L) }
}
