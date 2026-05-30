package com.shish.kaltswitch.config

import kotlin.math.floor

/** Switcher cell `widthIn.max` at scale = 1, in dp. Mirrors the literal
 *  in `SwitcherOverlay.kt`'s `AppCell` (`widthIn(min=92*s, max=132*s)`);
 *  the planner reasons against the upper bound because that's the
 *  worst-case width a cell can actually occupy when its content is
 *  wide enough to push to the max. */
const val CELL_MAX_WIDTH_DP: Float = 132f

/** Horizontal gap between cells inside the switcher's per-row `Row`,
 *  in dp. Mirrors `Arrangement.spacedBy(6.dp, ...)` in `SwitcherPanel`. */
const val CELL_HORIZONTAL_GAP_DP: Float = 6f

/** Total horizontal padding the switcher panel's outer Box adds inside
 *  the user's [SwitcherSettings.maxWidthPercent] cap, in dp. Mirrors
 *  `.padding(horizontal = 16.dp, ...)` in `SwitcherPanel`. */
const val PANEL_HORIZONTAL_PADDING_TOTAL_DP: Float = 32f

/**
 * The switcher panel's per-row plan: how many app cells go on a row and
 * at what visual scale. Both fields drive `SwitcherPanel` — the scale
 * goes through `LocalIconCellScale` to size each cell's `widthIn(min =
 * 92·s, max = 132·s)`, the count chunks the entries into explicit `Row`s.
 *
 * A `cellsPerRow` of zero is the empty-entries case; callers must skip
 * rendering rather than feed it to `.chunked(0)`.
 */
data class CellLayoutPlan(val cellsPerRow: Int, val scale: Float)

/**
 * Plan the switcher panel's row layout.
 *
 * The algorithm in plain language:
 *
 *  1. **Fewest rows possible.** At the smallest scale the user allows,
 *     a single row holds `fitPerRow(cellArea, minScale)` cells worst-
 *     case. Dividing entries by that — `⌈entries / fitAtMin⌉` — gives
 *     the *minimum achievable* row count.
 *  2. **Cost at max scale.** Same idea at `maxScale`. If the row count
 *     is the same at both ends of the range, shrinking can't peel off
 *     a row, so it's pointless — return `maxScale` with the fit count.
 *  3. **Distribute evenly.** When the min-rows count IS smaller, that's
 *     our target. The cells-per-row needed to hit it is `⌈entries /
 *     minRows⌉` — last row may be under-filled (down to a single cell)
 *     and that's fine.
 *  4. **Largest scale fitting the distribution.** Solve the per-row fit
 *     inequality for `s` at the chosen `cellsPerRow`, clamp into
 *     `[minScale, maxScale]`. Biggest visible cells subject to the
 *     fewer-rows goal.
 *
 * **Non-flex callers**: pass `minScale == maxScale`. The min-rows path
 * collapses to the same number as the max-rows path, step 2 returns,
 * and the result is just "as many cells as fit at maxScale".
 *
 * **Per-row fit formula** (referenced from [fitPerRow]):
 *   N cells + (N−1) gaps must fit cellArea:
 *     N·W + (N−1)·g ≤ cellArea
 *   ⇔ N·(W + g) ≤ cellArea + g
 *   ⇔ N ≤ (cellArea + g) / (W + g)
 *
 *   The `+ g` in the numerator is exactly what compensates for the row
 *   having one fewer gap than cells (N−1, not N). Looks suspicious at
 *   a glance, is correct — N=1 gives 1 ≤ (cellArea + g) / (W + g),
 *   i.e. W ≤ cellArea, exactly right for a one-cell row with no gaps.
 *
 * Edge cases (`entries ≤ 0`, `cellArea ≤ 0`, inverted scale range)
 * return a safe single-row plan at `maxScale` rather than throwing.
 */
fun planCellLayout(
    cellAreaDp: Float,
    entriesCount: Int,
    minScale: Float,
    maxScale: Float,
): CellLayoutPlan {
    if (entriesCount <= 0 || cellAreaDp <= 0f || minScale > maxScale) {
        return CellLayoutPlan(cellsPerRow = entriesCount.coerceAtLeast(0), scale = maxScale)
    }

    val fitAtMin = fitPerRow(cellAreaDp, minScale)
    val fitAtMax = fitPerRow(cellAreaDp, maxScale)
    val minRows = ceilDiv(entriesCount, fitAtMin)
    val rowsAtMax = ceilDiv(entriesCount, fitAtMax)

    // Shrinking only "matters" if it strips off at least one row. If
    // the wrap count is identical at both ends of the configured range,
    // the user gains nothing visually from going smaller.
    if (minRows >= rowsAtMax) {
        return CellLayoutPlan(
            cellsPerRow = fitAtMax.coerceAtMost(entriesCount),
            scale = maxScale,
        )
    }

    // Distribute evenly into the smallest row count we can hit. Last
    // row may be under-filled (even with a single cell).
    val cellsPerRow = ceilDiv(entriesCount, minRows)
    val scale =
        ((cellAreaDp - CELL_HORIZONTAL_GAP_DP * (cellsPerRow - 1)) /
            (CELL_MAX_WIDTH_DP * cellsPerRow))
            .coerceIn(minScale, maxScale)
    return CellLayoutPlan(cellsPerRow = cellsPerRow, scale = scale)
}

/**
 * Largest `N` such that `N` cells + `(N − 1)` gaps fit `cellArea` at the
 * given [scale]. See [planCellLayout]'s doc for the formula derivation
 * and why the `+ CELL_HORIZONTAL_GAP_DP` in the numerator is the
 * "one fewer gap than cells" correction (not a mistake).
 *
 * Coerced ≥ 1 so a too-narrow panel still yields a non-degenerate
 * one-cell-per-row plan — the panel will visually overflow, but the
 * caller doesn't divide by zero or hit `.chunked(0)`.
 */
fun fitPerRow(cellAreaDp: Float, scale: Float): Int = floor(
    (cellAreaDp + CELL_HORIZONTAL_GAP_DP) /
        (CELL_MAX_WIDTH_DP * scale + CELL_HORIZONTAL_GAP_DP)
).toInt().coerceAtLeast(1)

private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b
