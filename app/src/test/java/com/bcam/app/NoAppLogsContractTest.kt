package com.bcam.app
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
class NoAppLogsContractTest {
    private fun src(p: String) = listOf(File(p), File("../$p")).first { it.isFile }.readText()
    @Test fun noViewerInSettings() {
        assertFalse(src("app/src/main/java/com/bcam/app/BCamPage.kt").contains("LOGS"))
        assertFalse(src("app/src/main/java/com/bcam/app/BCamComposeUi.kt").contains("BCamLogsPage"))
    }
    @Test fun logcatOnly() {
        val log = src("app/src/main/java/com/bcam/app/AppLog.kt")
        assertTrue(log.contains("Log.d("))
        assertFalse(log.contains("appendText("))
        assertFalse(src("app/src/main/AndroidManifest.xml").contains("CrashJournalProvider"))
    }
}
