package com.bcam.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BCamPageTest {
    @Test fun mainSettingsShowsIconAndAppBranding() {
        assertEquals("Settings", BCamPage.SETTINGS.title)
        assertTrue(BCamPage.SETTINGS.showsSettingsIcon)
        assertTrue(BCamPage.SETTINGS.showsAppBranding)
        assertTrue(BCamPage.SETTINGS.isSettingsPage)
    }

    @Test fun subpagesUseOneTitleAndReturnToSettings() {
        for (page in listOf(BCamPage.RECORDING, BCamPage.AUDIO_FILES)) {
            assertTrue(page.isSettingsPage)
            assertFalse(page.showsSettingsIcon)
            assertTrue(page.showsAppBranding)
            assertEquals(BCamPage.SETTINGS, page.backDestination())
        }
        assertEquals("Recording", BCamPage.RECORDING.title)
        assertEquals("Audio & files", BCamPage.AUDIO_FILES.title)
    }

    @Test fun updaterHasHostOwnedTitleWithoutExtraBranding() {
        assertEquals("About & updates", BCamPage.UPDATES.title)
        assertTrue(BCamPage.UPDATES.isSettingsPage)
        assertTrue(BCamPage.UPDATES.showsAppBranding)
        assertFalse(BCamPage.UPDATES.showsSettingsIcon)
        assertEquals(BCamPage.SETTINGS, BCamPage.UPDATES.backDestination())
    }

    @Test fun mainNavigationHasHomeAndNestedSettings() {
        assertEquals("Home", BCamPage.HOME.title)
        assertFalse(BCamPage.HOME.isSettingsPage)
        assertTrue(BCamPage.HOME.showsAppBranding)
        assertTrue(BCamPage.RECORDING.isSettingsPage)
        assertTrue(BCamPage.AUDIO_FILES.isSettingsPage)
        assertTrue(BCamPage.UPDATES.isSettingsPage)
    }

    @Test fun openingSettingsStopsAndSavesActiveRecording() {
        assertEquals(RecordService.ACTION_STOP, stopActionWhenOpeningSettings(true))
        assertNotEquals(RecordService.ACTION_DISCARD_CURRENT, stopActionWhenOpeningSettings(true))
    }

    @Test fun openingSettingsDoesNotStartAServiceWhenIdle() {
        assertNull(stopActionWhenOpeningSettings(false))
    }
}
