package com.bcam.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The AAYouTube Logs contract across N2K02 apps. */
class UnifiedLogsContractTest {
    @Test fun commonLogActionsAndResponsivePaging() {
        val path = "app/src/main/java/com/bcam/app/UnifiedLogsPage.kt"
        val text = listOf(File(path), File("../$path"))
            .firstOrNull { it.isFile }?.readText() ?: error("Missing $path")
        assertTrue(text.contains("Switch(checked = enabled"))
        assertTrue(text.contains("followLatest"))
        assertTrue(text.contains("onSizeChanged { viewport = it }"))
        assertTrue(text.contains("maxHeight < 430.dp"))
        assertTrue(text.contains("Text(\"Prev\","))
        assertTrue(text.contains("Text(\"Next\","))
        assertTrue(text.contains("Text(\"Copy\","))
        assertTrue(text.contains("Text(\"Clear all\","))
        assertTrue(text.contains("AppLog.clear()"))
        assertTrue(text.contains("clearExtra?.invoke()"))
        assertTrue(text.contains("Icons.Filled.ContentCopy"))
        assertTrue(text.contains("Icons.Filled.DeleteSweep"))
        assertTrue(text.contains("Icons.AutoMirrored.Filled.KeyboardArrowLeft"))
        assertTrue(text.contains("Icons.AutoMirrored.Filled.KeyboardArrowRight"))
        assertTrue(text.contains("horizontalArrangement = Arrangement.SpaceBetween"))
        assertFalse(text.contains("Text(\"Back\")"))
        assertFalse(text.contains("onBack: () -> Unit"))
    }
}
