package com.bcam.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source guards for the shared Logs theme, navigation, and small-screen controls. */
class LogsThemeContractTest {
    private fun source(path: String): String = listOf(File(path), File("../$path"))
        .firstOrNull { it.isFile }?.readText() ?: error("Missing $path")

    @Test fun logsIsAThemedSettingsSubpageInsteadOfAStandaloneActivity() {
        val ui = source("app/src/main/java/com/bcam/app/BCamComposeUi.kt")
        val logs = source("app/src/main/java/com/bcam/app/LogsActivity.kt")
        val manifest = source("app/src/main/AndroidManifest.xml")
        assertTrue(ui.contains("dev.n2k02.theme.N2K02Theme {"))
        assertTrue(ui.contains("page == BCamPage.LOGS"))
        assertTrue(ui.contains("page = BCamPage.LOGS"))
        assertTrue(ui.contains("BCamPage.LOGS -> Icons.Rounded.Article"))
        assertTrue(ui.contains("BCamLogsPage("))
        assertTrue(ui.contains("LaunchedEffect(activity) { AppLog.init(activity.applicationContext) }"))
        assertFalse(ui.contains("startActivity(android.content.Intent(logScreenContext"))
        assertFalse(logs.contains("class LogsActivity"))
        assertFalse(logs.contains("MaterialTheme {"))
        assertFalse(logs.contains("Scaffold("))
        assertFalse(manifest.contains("com.bcam.app.LogsActivity"))
    }

    @Test fun logsControlsAndActionsRemainReachableOnSmallScreens() {
        val logs = source("app/src/main/java/com/bcam/app/LogsActivity.kt")
        assertTrue(source("app/src/main/java/com/bcam/app/UnifiedLogsPage.kt").contains("maxHeight < 430.dp"))
        assertTrue(source("app/src/main/java/com/bcam/app/UnifiedLogsPage.kt").contains("Modifier.verticalScroll(rememberScrollState())"))
        assertTrue(source("app/src/main/java/com/bcam/app/UnifiedLogsPage.kt").contains("Modifier.weight(1f)"))
        assertTrue(source("app/src/main/java/com/bcam/app/UnifiedLogsPage.kt").contains("Icons.Filled.ContentCopy"))
        assertTrue(source("app/src/main/java/com/bcam/app/UnifiedLogsPage.kt").contains("Switch(checked = enabled"))
        assertTrue(source("app/src/main/java/com/bcam/app/UnifiedLogsPage.kt").contains("AppLog.clear()"))
        assertFalse(source("app/src/main/java/com/bcam/app/UnifiedLogsPage.kt").contains("Text(\"Back\")"))
        assertTrue(source("app/src/main/java/com/bcam/app/UnifiedLogsPage.kt").contains("MaterialTheme.colorScheme.surfaceContainer"))
        assertTrue(ui.contains("leadingContent = { Icon(Icons.Rounded.Article, null) }"))
    }
}
