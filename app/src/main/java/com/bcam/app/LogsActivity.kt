package com.bcam.app

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

/** Hosted by the main Settings page, with one header and the N2K02Theme. */
@Composable
internal fun BCamLogsPage(modifier: Modifier = Modifier, onBack: () -> Unit) {
    N2K02UnifiedLogsPage(modifier = modifier, appName = "BCam", onBack = onBack)
}
