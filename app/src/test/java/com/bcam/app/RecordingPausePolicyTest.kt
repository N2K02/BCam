package com.bcam.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingPausePolicyTest {
    @Test fun showsPauseBeforeRecordingAndResumeForQueuedOrPaused() {
        assertFalse(RecordingPausePolicy.showsResume(paused = false, pendingPause = false))
        assertTrue(RecordingPausePolicy.showsResume(paused = false, pendingPause = true))
        assertTrue(RecordingPausePolicy.showsResume(paused = true, pendingPause = false))
        assertTrue(RecordingPausePolicy.showsResume(paused = true, pendingPause = true))
    }

    @Test fun pauseWaitsForInitialRecorderWarmupButNeverAddsUnnecessaryDelay() {
        assertEquals(750L, RecordingPausePolicy.pauseDelayMs(1000, 1000))
        assertEquals(200L, RecordingPausePolicy.pauseDelayMs(1000, 1550))
        assertEquals(0L, RecordingPausePolicy.pauseDelayMs(1000, 1750))
        assertEquals(0L, RecordingPausePolicy.pauseDelayMs(1000, 2500))
    }
}
