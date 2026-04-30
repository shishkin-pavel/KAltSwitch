package com.shish.kaltswitch.config

import com.shish.kaltswitch.store.WorldStore
import kotlin.test.Test
import kotlin.test.assertEquals

class SwitcherSettingsTest {

    @Test
    fun sanitized_clampsNegativeAndZeroIntervalValues() {
        val settings = SwitcherSettings(
            showDelayMs = -20,
            previewDelayMs = -250,
            previewEnabled = true,
            repeatInitialDelayMs = -400,
            repeatIntervalMs = 0,
        )

        assertEquals(
            SwitcherSettings(
                showDelayMs = 0,
                previewDelayMs = 0,
                previewEnabled = true,
                repeatInitialDelayMs = 0,
                repeatIntervalMs = 1,
            ),
            settings.sanitized(),
        )
    }

    @Test
    fun sanitized_preservesValidValues() {
        val settings = SwitcherSettings(
            showDelayMs = 10,
            previewDelayMs = 20,
            previewEnabled = true,
            repeatInitialDelayMs = 30,
            repeatIntervalMs = 40,
        )

        assertEquals(settings, settings.sanitized())
    }

    @Test
    fun storeBoundary_appliesSanitisationOnSet() {
        // Both config-load (`applyConfig`) and the settings UI go through
        // `setSwitcherSettings`; the store must be the choke-point.
        val store = WorldStore()
        store.setSwitcherSettings(
            SwitcherSettings(
                showDelayMs = -1,
                previewDelayMs = -1,
                repeatInitialDelayMs = -1,
                repeatIntervalMs = 0,
            )
        )
        val s = store.switcherSettings.value
        assertEquals(0L, s.showDelayMs)
        assertEquals(0L, s.previewDelayMs)
        assertEquals(0L, s.repeatInitialDelayMs)
        assertEquals(1L, s.repeatIntervalMs)
    }
}
