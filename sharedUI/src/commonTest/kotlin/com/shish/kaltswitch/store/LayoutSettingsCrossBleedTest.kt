package com.shish.kaltswitch.store

import com.shish.kaltswitch.config.MaxSizeMode
import com.shish.kaltswitch.config.SwitcherSettings
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Mirrors the exact sequence the user reported as buggy in the Settings
 * → Layout group:
 *
 *   1. user drags `Max panel width` to 50 %
 *   2. user toggles `Flexible cell size` on
 *   3. user drags `Min cell size` to 80 %
 *
 * The bug report: after step 3, `maxWidthPercent` snaps back to the
 * default 0.9 and `flexibleCellSize` flips off. If the data layer were
 * doing this, this test would catch it. If the test passes, the bug is
 * UI-layer (stale-lambda capture, hit-testing leak, etc.) and the fix
 * has to be there.
 */
class LayoutSettingsCrossBleedTest {

    @Test
    fun maxWidthThenFlexThenMin_eachStepPreservesEarlierEdits() {
        val store = WorldStore()

        // Step 1: user drags Max panel width to 50 %. This mirrors
        // App.kt's `MaxWidthSetting.onChange` lambda body verbatim.
        run {
            val s = store.switcherSettings.value
            store.setSwitcherSettings(
                s.copy(
                    maxWidthMode = MaxSizeMode.Percent,
                    maxWidthPercent = 0.5,
                    maxIconsPerRow = s.maxIconsPerRow,
                ),
            )
        }
        assertEquals(0.5, store.switcherSettings.value.maxWidthPercent, "after step 1")

        // Step 2: user toggles Flexible on.
        run {
            val s = store.switcherSettings.value
            store.setSwitcherSettings(s.copy(flexibleCellSize = true))
        }
        assertEquals(true, store.switcherSettings.value.flexibleCellSize, "after step 2")
        assertEquals(0.5, store.switcherSettings.value.maxWidthPercent, "step 2 must not bleed onto maxWidthPercent")

        // Step 3: user drags Min cell size to 80 %.
        run {
            val s = store.switcherSettings.value
            store.setSwitcherSettings(s.copy(minCellSizePercent = 80))
        }
        val final = store.switcherSettings.value
        assertEquals(80, final.minCellSizePercent, "step 3 set min")
        assertEquals(true, final.flexibleCellSize, "step 3 must not flip flexibleCellSize")
        assertEquals(0.5, final.maxWidthPercent, "step 3 must not snap maxWidthPercent back to default")
    }

    @Test
    fun reverseOrder_minThenFlexThenMaxWidth_keepsAllEdits() {
        // Same idea, opposite order. If the bug were symmetric — both
        // sliders overwriting each other's values — this would also
        // fail.
        val store = WorldStore()

        // Enable flex first so the min-cell-size onChange's
        // `interactive` gate (in the UI) would allow the change.
        run {
            val s = store.switcherSettings.value
            store.setSwitcherSettings(s.copy(flexibleCellSize = true))
        }
        run {
            val s = store.switcherSettings.value
            store.setSwitcherSettings(s.copy(minCellSizePercent = 75))
        }
        run {
            val s = store.switcherSettings.value
            store.setSwitcherSettings(
                s.copy(
                    maxWidthMode = MaxSizeMode.Percent,
                    maxWidthPercent = 0.55,
                    maxIconsPerRow = s.maxIconsPerRow,
                ),
            )
        }
        val final = store.switcherSettings.value
        assertEquals(true, final.flexibleCellSize)
        assertEquals(75, final.minCellSizePercent)
        assertEquals(0.55, final.maxWidthPercent)
    }
}
