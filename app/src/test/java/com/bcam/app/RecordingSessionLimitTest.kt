package com.bcam.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingSessionLimitTest {
    @Test fun stopsAtTheDeadlineAndRemainsExpired() {
        val start = 1_234L
        val limit = RecordingSessionLimit(start, 5)
        assertEquals(300_000L, limit.remainingMs(start))
        assertEquals(1L, limit.remainingMs(start + 299_999L))
        assertEquals(0L, limit.remainingMs(start + 300_000L))
        assertEquals(0L, limit.remainingMs(start + 360_000L))
    }

    @Test fun elapsedTimeIsNotResetByRolloverRetryOrPause() {
        val start = 4_000_000_000L
        val limit = RecordingSessionLimit(start, 15)
        assertEquals(600_000L, limit.remainingMs(start + 300_000L)) // next file
        assertEquals(120_000L, limit.remainingMs(start + 780_000L)) // camera retry
        assertEquals(0L, limit.remainingMs(start + 900_000L)) // still expires while paused
    }

    @Test fun unlimitedDoesNotScheduleAStop() {
        val limit = RecordingSessionLimit(123L, 0)
        assertNull(limit.remainingMs(123L))
        assertNull(limit.remainingMs(24 * 60 * 60_000L))
    }

    @Test fun eachNewSessionGetsItsOwnDeadline() {
        val first = RecordingSessionLimit(1_000L, 5)
        val next = RecordingSessionLimit(500_000L, 10)
        assertEquals(0L, first.remainingMs(500_000L))
        assertEquals(600_000L, next.remainingMs(500_000L))
    }

    @Test fun recorderArmsOnlyOnStartAndUsesTheSavePathAtExpiry() {
        val source = serviceSource()
        val start = source.substringAfter("ACTION_START -> {").substringBefore("ACTION_DISCARD_CURRENT ->")
        assertTrue(start.contains("RecordingSessionLimit(SystemClock.elapsedRealtime(), settings.autoStopMinutes)"))
        assertTrue(start.contains("sessionLimit = limit"))
        assertTrue(start.contains("autoStop.run()"))
        val timeout = source.substringAfter("private val autoStop =").substringBefore("private fun stopIfSessionExpired")
        assertTrue(timeout.contains("shutdown(userStopped = false, autoStopped = true)"))
        val shutdown = source.substringAfter("private fun shutdown(")
        assertTrue(shutdown.contains("sessionLimit = null"))
        assertTrue(shutdown.contains("h.removeCallbacksAndMessages(null)"))
        assertTrue(shutdown.contains("finishPart(discard = discardCurrent)"))
    }

    @Test fun recoveryPathsCannotStartOrResumeAnExpiredSession() {
        val source = serviceSource()
        for (entry in listOf("private fun startPart()", "private fun requestResume()", "private fun rollover()", "private fun retryLater(")) {
            val body = source.substringAfter(entry).substringAfter("{")
            assertTrue(entry, body.lineSequence().first { it.isNotBlank() }.contains("stopIfSessionExpired()"))
        }
        val start = source.substringAfter("override fun onConfigured(").substringBefore("override fun onConfigureFailed(")
        assertTrue(start.substringBefore("mr.start()").contains("stopIfSessionExpired()"))
    }

    private fun serviceSource(): String {
        val path = "app/src/main/java/com/bcam/app/RecordService.kt"
        return listOf(File(path), File("../$path")).first { it.isFile }.readText()
    }
}
