package com.shish.kaltswitch.config

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests run against [planCellLayout] directly with `cellAreaDp` values
 * — the panel padding subtraction belongs to the caller (SwitcherOverlay)
 * so it's not in scope here. `cellArea = 968` corresponds to a 1000 dp
 * panel cap minus the 32 dp panel padding (16+16); kept as a recognisable
 * "round number after subtraction" for quick mental verification.
 */
class CellSizingTest {

    private fun approxScale(expected: Float, plan: CellLayoutPlan, eps: Float = 0.005f) {
        assertTrue(
            abs(expected - plan.scale) <= eps,
            "scale: expected ≈ $expected, got ${plan.scale} (Δ = ${expected - plan.scale})",
        )
    }

    @Test
    fun returnsMaxScale_whenCellAreaZero() {
        val plan = planCellLayout(cellAreaDp = 0f, entriesCount = 5, minScale = 0.5f, maxScale = 1.0f)
        approxScale(1.0f, plan)
    }

    @Test
    fun returnsMaxScale_whenEntriesEmpty() {
        val plan = planCellLayout(cellAreaDp = 968f, entriesCount = 0, minScale = 0.7f, maxScale = 1.5f)
        approxScale(1.5f, plan)
        assertEquals(0, plan.cellsPerRow)
    }

    @Test
    fun returnsMaxScale_whenInvertedRange() {
        // Defensive: a stale config blob from disk could lie. The
        // planner must never let an inverted range crash the caller.
        val plan = planCellLayout(cellAreaDp = 968f, entriesCount = 8, minScale = 1.2f, maxScale = 1.0f)
        approxScale(1.0f, plan)
    }

    @Test
    fun fewEntriesFitAtMax_picksMaxScaleAndAllOneRow() {
        // 3 cells at scale = 1 take 3·132 + 2·6 = 408 dp — plenty of
        // room in 968. Already one row at max; shrinking can't go
        // below 1 row, so stay at max scale.
        val plan = planCellLayout(cellAreaDp = 968f, entriesCount = 3, minScale = 0.5f, maxScale = 1.0f)
        approxScale(1.0f, plan)
        assertEquals(3, plan.cellsPerRow)
    }

    @Test
    fun noShrink_whenMinScaleStillWrapsToSameRowCount() {
        // 20 entries, range [0.7, 1.0]:
        //   fitAtMax = ⌊(968+6) / (132+6)⌋     = 7 → rows = 3
        //   fitAtMin = ⌊(968+6) / (132·0.7+6)⌋ = 9 → rows = 3
        // Same row count both ends → no shrink. cellsPerRow = fitAtMax.
        val plan = planCellLayout(cellAreaDp = 968f, entriesCount = 20, minScale = 0.7f, maxScale = 1.0f)
        approxScale(1.0f, plan)
        assertEquals(7, plan.cellsPerRow)
    }

    @Test
    fun shrinks_whenItStripsAtLeastOneRow() {
        // 20 entries, range [0.5, 1.0]:
        //   fitAtMax = 7  → rows = 3
        //   fitAtMin = ⌊(968+6) / (66+6)⌋ = 13 → rows = 2
        // 2 < 3 ⇒ shrink. Target = ⌈20/2⌉ = 10 cells/row, even split
        // (10, 10) — no remainder this time.
        //   scale = (968 − 9·6) / (10·132) = 914 / 1320 ≈ 0.6924.
        val plan = planCellLayout(cellAreaDp = 968f, entriesCount = 20, minScale = 0.5f, maxScale = 1.0f)
        approxScale(0.6924f, plan)
        assertEquals(10, plan.cellsPerRow)
    }

    @Test
    fun shrinksTwoToOneRow_whenPossible() {
        // 10 entries, range [0.5, 1.0]:
        //   fitAtMax = 7  → rows = 2
        //   fitAtMin = 13 → rows = 1
        // Shrink to 1 row; cellsPerRow = 10.
        val plan = planCellLayout(cellAreaDp = 968f, entriesCount = 10, minScale = 0.5f, maxScale = 1.0f)
        approxScale(0.6924f, plan)
        assertEquals(10, plan.cellsPerRow)
    }

    @Test
    fun shrinksOnlyAsFarAsNecessary_forSmallerEntryCount() {
        // 8 entries, range [0.5, 1.0]:
        //   fitAtMax = 7  → rows = 2
        //   fitAtMin = 13 → rows = 1
        // 1 row; cellsPerRow = 8. The planner picks the **largest**
        // scale fitting 8 cells, not the smallest — `cellSizePercent`
        // is the visual preference, we only stray from it as far as
        // the row-reduction goal demands.
        //   scale = (968 − 7·6) / (8·132) = 926 / 1056 ≈ 0.8769.
        val plan = planCellLayout(cellAreaDp = 968f, entriesCount = 8, minScale = 0.5f, maxScale = 1.0f)
        approxScale(0.8769f, plan)
        assertEquals(8, plan.cellsPerRow)
    }

    @Test
    fun shrinksFromMaxScale_whenMaxIsLarge() {
        // 12 entries, range [0.5, 1.5]:
        //   fitAtMax (1.5) = ⌊974 / 204⌋ = 4 → rows = 3
        //   fitAtMin (0.5) = 13            → rows = 1
        // Shrink to 1 row; cellsPerRow = 12.
        //   scale = (968 − 11·6) / (12·132) = 902 / 1584 ≈ 0.5695.
        val plan = planCellLayout(cellAreaDp = 968f, entriesCount = 12, minScale = 0.5f, maxScale = 1.5f)
        approxScale(0.5695f, plan)
        assertEquals(12, plan.cellsPerRow)
    }

    @Test
    fun unevenDistribution_lastRowMayBeUnderFilled() {
        // 7 entries, range [0.5, 1.0]:
        //   fitAtMax = 7  → rows = 1
        //   fitAtMin = 13 → rows = 1
        // Already 1 row → no shrink, return maxScale + 7.
        val plan = planCellLayout(cellAreaDp = 968f, entriesCount = 7, minScale = 0.5f, maxScale = 1.0f)
        approxScale(1.0f, plan)
        assertEquals(7, plan.cellsPerRow)
    }

    @Test
    fun threeRowsToTwo_evenSplitMayLeaveLastShort() {
        // 30 entries, range [0.5, 1.0]:
        //   fitAtMax = 7  → rows = 5
        //   fitAtMin = 13 → rows = 3 (13·2 = 26 < 30)
        // Target 3 rows; cellsPerRow = ⌈30/3⌉ = 10 (rows of 10, 10, 10).
        //   scale ≈ 0.6924.
        val plan = planCellLayout(cellAreaDp = 968f, entriesCount = 30, minScale = 0.5f, maxScale = 1.0f)
        approxScale(0.6924f, plan)
        assertEquals(10, plan.cellsPerRow)
    }

    @Test
    fun unevenLastRow_singleCell() {
        // 7 entries with a narrower panel:
        //   cellArea = 350 dp, fitAtMax (1.0) = ⌊356/138⌋ = 2 → rows = 4
        //   fitAtMin (0.5) = ⌊356/72⌋        = 4         → rows = 2
        // Target 2 rows; cellsPerRow = ⌈7/2⌉ = 4. Distribution (4, 3) —
        // the last row is under-filled by one cell, exactly the case
        // the spec called out as expected behaviour.
        val plan = planCellLayout(cellAreaDp = 350f, entriesCount = 7, minScale = 0.5f, maxScale = 1.0f)
        assertEquals(4, plan.cellsPerRow)
        // scale = (350 − 3·6) / (4·132) = 332 / 528 ≈ 0.6288.
        approxScale(0.6288f, plan)
    }

    @Test
    fun nonFlexShortcut_minScaleEqualsMaxScale() {
        // Non-flex callers pass minScale == maxScale; the planner
        // collapses to "as many cells as fit at maxScale".
        val plan = planCellLayout(cellAreaDp = 968f, entriesCount = 20, minScale = 1.0f, maxScale = 1.0f)
        approxScale(1.0f, plan)
        assertEquals(7, plan.cellsPerRow)
    }

    @Test
    fun chosenScale_isAlwaysInsideTheConfiguredRange() {
        for (n in 1..40) {
            val plan = planCellLayout(
                cellAreaDp = 1168f,           // 1200 cap − 32 panel padding
                entriesCount = n,
                minScale = 0.6f,
                maxScale = 1.4f,
            )
            assertTrue(plan.scale in 0.6f..1.4f, "scale escaped range at n=$n: $plan")
            assertTrue(plan.cellsPerRow >= 1, "cellsPerRow degenerate at n=$n: $plan")
        }
    }
}
