package com.bcam.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guard the mRadio-aligned Settings layout. Home and app functionality stay independent. */
class SettingsConsistencyContractTest {
    @Test fun settingsUsesConsistentRowsAndUpdatePlacement() {
        val relative = "app/src/main/java/com/bcam/app/BCamComposeUi.kt"
        val source = listOf(File(relative), File("../$relative"))
            .firstOrNull { it.isFile }?.readText() ?: error("Missing $relative")
        val menu = source.substringAfter("else if (page == BCamPage.SETTINGS) {", "")
        assertTrue("Settings menu section must exist", menu.isNotBlank())
        assertTrue("Settings must have mRadio-style section heading", menu.contains("Configure your camera"))
        assertTrue("Settings should have aligned ListItems", menu.contains("headlineContent = { Text(destination.title) }"))
        assertTrue("Updates must remain available", menu.contains("headlineContent = { Text(\"About & updates\") }"))
        val divider = menu.indexOf("HorizontalDivider()")
        val updates = menu.indexOf("headlineContent = { Text(\"About & updates\") }")
        assertTrue("Divider must precede final About & updates section", divider >= 0 && updates > divider)
    }
}
