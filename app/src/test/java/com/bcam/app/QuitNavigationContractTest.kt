package com.bcam.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protect the bottom Quit menu and its app-owned shutdown behavior. */
class QuitNavigationContractTest {
    @Test fun quitStaysBesideSettingsAndOldControlsDoNotReturn() {
        val relative = "app/src/main/java/com/bcam/app/BCamComposeUi.kt"
        val src = listOf(File(relative), File("../$relative"))
            .firstOrNull { it.isFile }?.readText() ?: error("Missing $relative")
        assertTrue("Missing label = { Text(Settings) }", src.contains("label = { Text(\"Settings\") }"))
        assertTrue("Missing label = { Text(Quit) }", src.contains("label = { Text(\"Quit\") }"))
        assertTrue("Missing fun quitBCam()", src.contains("fun quitBCam()"))
        assertTrue("Missing RecordService.ACTION_STOP", src.contains("RecordService.ACTION_STOP"))
        assertTrue("Missing activity.finishAndRemoveTask()", src.contains("activity.finishAndRemoveTask()"))
        assertFalse("Legacy close modal must not return", src.contains("quitDialog"))
        assertFalse("Legacy close modal must not return", src.contains("Close settings?"))
        assertFalse("Legacy close modal must not return", src.contains("headlineContent = { Text(\"Close settings\") }"))
        assertTrue(
            "Quit must follow Settings in the bottom menu",
            src.indexOf("label = { Text(\"Quit\") }") > src.indexOf("label = { Text(\"Settings\") }"),
        )
    }
}
