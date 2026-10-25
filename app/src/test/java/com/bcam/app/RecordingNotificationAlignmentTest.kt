package com.bcam.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Keep compact/expanded text centered and all notification controls evenly spaced. */
class RecordingNotificationAlignmentTest {
    private fun source(path: String): String =
        listOf(File(path), File("../$path"))
            .firstOrNull { it.isFile }?.readText() ?: error("Missing $path")

    @Test fun allNotificationTextAndActionCellsAreCentered() {
        val file = listOf(
            File("app/src/main/res/layout/notification_recording.xml"),
            File("../app/src/main/res/layout/notification_recording.xml"),
        ).firstOrNull { it.isFile } ?: error("Notification layout missing")
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(file).documentElement
        val android = "http://schemas.android.com/apk/res/android"
        assertEquals("center", root.getAttributeNS(android, "gravity"))

        val textViews = root.getElementsByTagName("TextView")
        val expected = setOf(
            "@+id/notification_title", "@+id/notification_detail",
            "@+id/notification_pause", "@+id/notification_stop", "@+id/notification_settings",
        )
        val found = mutableSetOf<String>()
        for (i in 0 until textViews.length) {
            val textView = textViews.item(i) as Element
            val id = textView.getAttributeNS(android, "id")
            found += id
            assertEquals("Text $id must be centered", "center", textView.getAttributeNS(android, "gravity"))
            assertEquals("Text $id must have center alignment", "center", textView.getAttributeNS(android, "textAlignment"))
            if (id in expected.filter { it.startsWith("@+id/notification_") && it !in
                setOf("@+id/notification_title", "@+id/notification_detail") }) {
                assertEquals("Action $id must divide the row evenly", "1",
                    textView.getAttributeNS(android, "layout_weight"))
                assertEquals("Action $id must take one weighted cell", "0dp",
                    textView.getAttributeNS(android, "layout_width"))
                assertEquals("Action $id should have an accessible 48dp tap target", "48dp",
                    textView.getAttributeNS(android, "layout_height"))
            }
        }
        assertTrue("All status fields and action labels must exist", found.containsAll(expected))
    }

    @Test fun recorderStateAndOriginalControlIntentsArePreserved() {
        val service = source("app/src/main/java/com/bcam/app/RecordService.kt")
        assertTrue(service.contains("Notification.DecoratedCustomViewStyle()"))
        assertTrue(service.contains("setCustomContentView(recordingNotificationViews(title, text, recording, expanded = false))"))
        assertTrue(service.contains("setCustomBigContentView(recordingNotificationViews(title, text, recording, expanded = true))"))
        assertTrue(service.contains("setOnClickPendingIntent(R.id.notification_pause, servicePi(ACTION_PAUSE_RESUME, 4))"))
        assertTrue(service.contains("setOnClickPendingIntent(R.id.notification_stop, servicePi(ACTION_STOP, 1))"))
        assertTrue(service.contains("setOnClickPendingIntent(R.id.notification_settings, settingsPi())"))
        assertTrue(service.contains("setTextViewText(R.id.notification_pause, if (paused) \"▶ Resume\" else \"❚❚ Pause\")"))
        assertFalse("Avoid duplicate uncentered native action rows", service.contains("Notification.Action.Builder("))
    }
}
