package com.bcam.app

/** Settings navigation and app bar metadata; matches mRadio's one-header pattern. */
internal enum class BCamPage(val title: String) {
    HOME("Home"),
    SETTINGS("Settings"),
    RECORDING("Recording"),
    AUDIO_FILES("Audio & files"),
    LOGS("Logs"),
    UPDATES("About & updates");

    val isSettingsPage: Boolean get() = this != HOME
    val showsSettingsIcon: Boolean get() = this == SETTINGS
    val showsAppBranding: Boolean get() = true

    fun backDestination(): BCamPage = SETTINGS
}

/** Request a safe stop (save current video), not a discard, upon entering Settings. */
internal fun stopActionWhenOpeningSettings(isRecording: Boolean): String? =
    if (isRecording) RecordService.ACTION_STOP else null
