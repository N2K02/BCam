package com.bcam.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** JVM regression tests for VREC-style controls and persisted default. */
class RecordingOverlayContractTest {
    private fun source(path: String): String {
        val files = listOf(File("src/main/java/com/bcam/app/$path"),
            File("app/src/main/java/com/bcam/app/$path"))
        return files.first { it.exists() }.readText()
    }

    @Test fun overlayIsEnabledForNewInstalls() {
        assertTrue(VideoSettings().overlayEnabled)
    }

    @Test fun dragIsBoundedEvenOnSmallerDisplays() {
        assertEquals(OverlayDragPosition.Position(0, 0),
            OverlayDragPosition.clamp(-10, 900, 100, 40, 220, 50))
        assertEquals(OverlayDragPosition.Position(180, 250),
            OverlayDragPosition.clamp(900, 900, 400, 300, 220, 50))
    }

    @Test fun overlayUsesIdempotentPauseResumeAndStopActions() {
        val overlay = source("OverlayControlService.kt")
        assertTrue(overlay.contains("RecordService.ACTION_RESUME else RecordService.ACTION_PAUSE"))
        assertTrue(overlay.contains("RecordService.isPausePending -> renderPauseQueued()"))
        assertTrue(overlay.contains("Pause recording when camera is ready"))
        assertTrue(overlay.contains("Cancel pending pause"))
        assertTrue(overlay.contains(".setAction(RecordService.ACTION_STOP)"))
        assertTrue(overlay.contains("Close recording overlay, keep recording"))
        assertTrue(overlay.contains("positions.edit().putInt(\"x\""))
        assertFalse(overlay.contains("RecordService.ACTION_DISCARD_CURRENT"))
    }

    @Test fun serviceDoesNotRestartHiddenControlsDuringOneRecording() {
        val service = source("RecordService.kt")
        assertTrue(service.contains("isRunning = true\n                showOverlayIfEnabled()"))
        assertTrue(service.contains("dismissOverlay()"))
        assertTrue(service.contains("isRecordingActive = false"))
        assertTrue(service.contains("ACTION_PAUSE -> if (isRunning) h.post { requestPause() }"))
        assertTrue(service.contains("ACTION_RESUME -> if (isRunning) h.post { requestResume() }"))
        assertTrue(service.contains("applyPendingPauseWhenReady()"))
    }
}
