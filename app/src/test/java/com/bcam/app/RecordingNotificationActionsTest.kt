package com.bcam.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard: native Android notification actions must remain present.
 * RemoteViews hid the action row in compact notifications on real devices.
 */
class RecordingNotificationActionsTest {
    private fun serviceSource(): String {
        val relative = "app/src/main/java/com/bcam/app/RecordService.kt"
        return listOf(File(relative), File("../$relative"))
            .firstOrNull { it.isFile }?.readText() ?: error("Missing $relative")
    }

    @Test fun notificationAlwaysIncludesNativeStopAndSettingsActions() {
        val method = serviceSource().substringAfter("private fun buildNotification(): Notification {")
            .substringBefore("private fun updateNotification()")
        assertTrue(method.contains("Notification.Action.Builder(ic(R.drawable.ic_stop), \"■ Stop\", servicePi(ACTION_STOP, 1))"))
        assertTrue(method.contains("Notification.Action.Builder(ic(R.drawable.ic_settings), \"⚙ Settings\", settingsPi())"))
        assertTrue(method.contains("b.setContentIntent(settingsPi())"))
        assertTrue(method.contains("return b.build()"))
    }

    @Test fun pauseAndResumeUseNativeActionsBoundToTheExistingServiceIntent() {
        val method = serviceSource().substringAfter("private fun buildNotification(): Notification {")
            .substringBefore("private fun updateNotification()")
        assertTrue(method.contains("if (recording) {"))
        assertTrue(method.contains("if (paused) Notification.Action.Builder(ic(R.drawable.ic_play), \"▶ Resume\", servicePi(ACTION_PAUSE_RESUME, 4))"))
        assertTrue(method.contains("Notification.Action.Builder(ic(R.drawable.ic_pause), \"❚❚ Pause\", servicePi(ACTION_PAUSE_RESUME, 4))"))
        assertTrue(method.contains("b.setUsesChronometer(true)"))
    }

    @Test fun noCustomLayoutCanHideNotificationActionButtons() {
        val source = serviceSource()
        assertFalse(source.contains("setCustomContentView("))
        assertFalse(source.contains("setCustomBigContentView("))
        assertFalse(source.contains("recordingNotificationViews("))
        assertFalse(source.contains("setViewVisibility(R.id.notification_actions"))
        assertFalse(source.contains("Notification.DecoratedCustomViewStyle()"))
    }
}
