package com.bcam.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keep actionable native notification controls visible in the Android notification shade.
 * RemoteViews are intentionally not used for the three recording actions.
 */
class RecordingNotificationActionsContractTest {
    private fun source(): String {
        val path = "app/src/main/java/com/bcam/app/RecordService.kt"
        return listOf(File(path), File("../$path"))
            .firstOrNull { it.isFile }?.readText() ?: error("Missing $path")
    }

    @Test fun notificationUsesAndroidNativeActionsInsteadOfCustomHiddenButtons() {
        val src = source()
        val notification = src.substringAfter("private fun buildNotification(): Notification {")
            .substringBefore("private fun updateNotification()")
        assertTrue(notification.contains("Notification.Action.Builder("))
        assertTrue(notification.contains("b.addAction("))
        assertFalse(notification.contains("setCustomContentView("))
        assertFalse(notification.contains("setCustomBigContentView("))
        assertFalse(notification.contains("DecoratedCustomViewStyle("))
        assertFalse(notification.contains("recordingNotificationViews("))
    }

    @Test fun pauseResumeStopAndSettingsRetainCorrectActions() {
        val src = source().substringAfter("private fun buildNotification(): Notification {")
            .substringBefore("private fun updateNotification()")
        assertTrue("Pause/Resume appears once recording has begun", src.contains("if (recording) {"))
        assertTrue(src.contains("if (paused) Notification.Action.Builder(ic(R.drawable.ic_play), \"▶ Resume\", servicePi(ACTION_PAUSE_RESUME, 4))"))
        assertTrue(src.contains("else Notification.Action.Builder(ic(R.drawable.ic_pause), \"❚❚ Pause\", servicePi(ACTION_PAUSE_RESUME, 4))"))
        assertTrue("Stop remains available while preparing", src.contains("b.addAction(Notification.Action.Builder(ic(R.drawable.ic_stop), \"■ Stop\", servicePi(ACTION_STOP, 1))"))
        assertTrue("Settings remains available", src.contains("b.addAction(Notification.Action.Builder(ic(R.drawable.ic_settings), \"⚙ Settings\", settingsPi())"))
        assertTrue("Whole notification opens settings", src.contains("b.setContentIntent(settingsPi())"))

        val pause = src.indexOf("ic(R.drawable.ic_pause)")
        val stop = src.indexOf("ic(R.drawable.ic_stop)")
        val settings = src.indexOf("ic(R.drawable.ic_settings)")
        assertTrue("Buttons must follow Pause, Stop, Settings", pause in 0 until stop && stop < settings)
    }
}
