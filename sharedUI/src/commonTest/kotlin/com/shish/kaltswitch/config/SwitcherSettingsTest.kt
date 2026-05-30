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
    fun sanitized_clampsMinCellSizeIntoTheCellSizeUpperBound() {
        // Hand-edited config: minCellSizePercent > cellSizePercent
        // collapses to a single-point range at the upper bound, so
        // the flexible picker becomes a no-op until the user dials
        // min downward. Verifies the "min ≤ max" invariant the
        // overlay relies on.
        val s = SwitcherSettings(
            cellSizePercent = 120,
            minCellSizePercent = 200,
        ).sanitized()
        assertEquals(120, s.cellSizePercent)
        assertEquals(120, s.minCellSizePercent)
    }

    @Test
    fun sanitized_clampsCellSizeBeforeRespectingMinUpperBound() {
        // Out-of-range cellSizePercent (300) gets clamped to 200; the
        // min then clamps against the *post-clamp* upper bound, not
        // the original. 250 -> 200 (because cell-size collapsed to 200).
        val s = SwitcherSettings(
            cellSizePercent = 300,
            minCellSizePercent = 250,
        ).sanitized()
        assertEquals(200, s.cellSizePercent)
        assertEquals(200, s.minCellSizePercent)
    }

    @Test
    fun sanitized_keepsValidMinAndMaxInRange() {
        val s = SwitcherSettings(
            cellSizePercent = 110,
            minCellSizePercent = 70,
            flexibleCellSize = true,
        ).sanitized()
        assertEquals(110, s.cellSizePercent)
        assertEquals(70, s.minCellSizePercent)
        assertEquals(true, s.flexibleCellSize)
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
