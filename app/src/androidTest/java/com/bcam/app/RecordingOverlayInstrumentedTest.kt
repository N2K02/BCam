package com.bcam.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies real Android SharedPreferences persist the optional floating controls. */
@RunWith(AndroidJUnit4::class)
class RecordingOverlayInstrumentedTest {
    @Test fun overlayPreferenceSurvivesSaveAndReload() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = VideoSettings.load(context)
        try {
            original.copy(overlayEnabled = false).save(context)
            assertFalse(VideoSettings.load(context).overlayEnabled)
            original.copy(overlayEnabled = true).save(context)
            assertTrue(VideoSettings.load(context).overlayEnabled)
        } finally {
            original.save(context)
        }
    }
}
