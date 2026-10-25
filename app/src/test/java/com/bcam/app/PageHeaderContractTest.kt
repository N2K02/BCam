package com.bcam.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lightweight source-level regression guard for the mRadio header contract.
 * Visual layout and playback behavior still require Android UI validation.
 */
class PageHeaderContractTest {
    private fun source(): String {
        val relative = "com/bcam/app/BCamComposeUi.kt"
        return listOf(File("src/main/java", relative), File("app/src/main/java", relative))
            .firstOrNull { it.isFile }?.readText()
            ?: error("Missing app UI source: $relative")
    }

    @Test fun pageHeaderHasLeadingIconAndRightSideAppName() {
        val text = source()
        assertTrue(text.contains("topBar = {"))
        val header = text.substringAfter("topBar = {")
            .substringBefore("bottomBar = {")
        assertTrue("Missing page icon in header", header.contains("Icons.Rounded.Settings"))
        assertTrue("Missing application name in header", header.contains("Text(\"BCam\""))
    }

    @Test fun appTitleIsNotProvidedBySharedUpdater() {
        val header = source().substringAfter("topBar = {")
            .substringBefore("bottomBar = {")
        assertFalse("App header should not duplicate updater title", header.contains("N2K02UpdateTitle("))
    }
}
