package com.shish.kaltswitch.config

import com.shish.kaltswitch.model.BadgeRules
import com.shish.kaltswitch.model.FilteringRules
import com.shish.kaltswitch.model.PinningRules
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * User's choice for the highlight / selection colour. `UseSystem` defers
 * to `NSColor.controlAccentColor` (Swift refreshes the value on
 * `NSSystemColorsDidChangeNotification`); `Custom` carries an
 * `0xAARRGGBB` hex.
 *
 * Up through v6 `Custom` stored a 24-bit RGB long (alpha implicit FF).
 * v7 widens to ARGB so the picker's alpha slider applies to the accent
 * too. The JSON field name stays `"rgb"` for back-compat (kotlinx
 * `@SerialName`), and `WorldStore.applyConfig` upgrades legacy values
 * whose alpha-byte is zero — every shipped config falls into that
 * bucket because the v6 default was `0xFFC107` (alpha = 00 when
 * interpreted as ARGB), and `WorldStore.applyConfig` forces alpha to
 * FF in that case so the highlight doesn't disappear on first load.
 * The cost is that a *new* user who deliberately picks alpha = 0 has
 * the value re-asserted to opaque on the next load — fully transparent
 * accent is functionally invisible, so this is the correct trade.
 */
@Serializable
sealed interface AccentColorChoice {
    @Serializable
    @SerialName("system")
    object UseSystem : AccentColorChoice

    @Serializable
    @SerialName("custom")
    data class Custom(@SerialName("rgb") val argb: Long) : AccentColorChoice
}

/**
 * Bottom-left origin and size of a window. macOS native uses bottom-left
 * (Cocoa flipped-from-screen) so saving these doubles round-trips through
 * `NSWindow.frame` without conversion.
 */
@Serializable
data class WindowFrame(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

/**
 * How the switcher's per-row width cap is interpreted: as a fraction of
 * the session screen's `visibleFrame.width` ([Percent]) or as a hard cap
 * on the number of icon cells per row ([MaxIconsPerRow]).
 *
 * The two values are stored alongside each other so flipping the mode
 * preserves whatever the user dialled in for each. `MaxIconsPerRow`
 * feeds `FlowRow.maxItemsInEachRow` directly — wider windows then leave
 * trailing whitespace rather than packing extra cells.
 */
@Serializable
enum class MaxSizeMode { Percent, MaxIconsPerRow }

/**
 * Which screen the switcher panel opens on.
 *
 * * [MouseScreen] — display containing the cursor at session start (default;
 *   matches alt-tab-macos and Spotlight).
 * * [ActiveWindowScreen] — display containing the frontmost app's focused
 *   window. Falls back to [MouseScreen] if AX can't read the focused frame
 *   (no window, no AX permission, off-screen window).
 * * [MainScreen] — `NSScreen.main` (the screen with the active menu bar).
 */
@Serializable
enum class SwitcherPlacement { MouseScreen, ActiveWindowScreen, MainScreen }

/**
 * Switcher behaviour knobs. Surfaced in the Settings window's General tab
 * and read live by [com.shish.kaltswitch.switcher.SwitcherController].
 *
 * Modifier choice (cmd vs alt) is intentionally absent — changing it requires
 * re-registering the global Carbon hotkey and updating the panel's cmd-release
 * detector, which is a separate post-MVP task.
 */
@Serializable
data class SwitcherSettings(
    val showDelayMs: Long = 20L,
    val previewDelayMs: Long = 250L,
    /** Hover-and-hold raises the cursor's window behind the panel after
     *  [previewDelayMs]. On by default — see decisions.md §9 for why this
     *  switched on after iter14's live snapshot tightened the gating. */
    val previewEnabled: Boolean = true,
    /** How long to keep the shortcut held before auto-advancing through
     *  elements. A short tap (release before this delay) advances exactly
     *  once. Default tuned to be longer than typical OS keyboard repeat
     *  initial-delay so quick taps stay one-shot. */
    val repeatInitialDelayMs: Long = 400L,
    /** Step interval once auto-advance is engaged. */
    val repeatIntervalMs: Long = 120L,
    /** Whether [maxWidthPercent] or [maxIconsPerRow] is the active cap. */
    val maxWidthMode: MaxSizeMode = MaxSizeMode.Percent,
    /** Fraction (0..1) of the session screen's `visibleFrame.width` used
     *  as the panel's max width when [maxWidthMode] = [MaxSizeMode.Percent]. */
    val maxWidthPercent: Double = 0.9,
    /** Hard cap on the number of icon cells per row, honoured when
     *  [maxWidthMode] = [MaxSizeMode.MaxIconsPerRow]. Plumbed straight to
     *  `FlowRow.maxItemsInEachRow`. */
    val maxIconsPerRow: Int = 8,
    /** Hover-and-hold delay before a truncated selected window-row's title
     *  expands rightward to show the full text. Lets the user navigate
     *  past long rows without them flickering open. Symmetric collapse is
     *  immediate — only expansion is delayed. */
    val selectionExpandDelayMs: Long = 250L,
    /** Scale factor (percent) applied to the switcher overlay's app
     *  icon and the surrounding `AppCell` box. 100 = default; range
     *  50..200 (clamped in [sanitized]). Text (app name, window titles)
     *  and panel-level paddings deliberately stay unscaled.
     *
     *  When [flexibleCellSize] is on, this is the **upper bound** of the
     *  range the runtime is allowed to pick from; the lower bound is
     *  [minCellSizePercent]. */
    val cellSizePercent: Int = 100,
    /** When `true` and [maxWidthMode] = [MaxSizeMode.Percent], the runtime
     *  auto-shrinks the cell scale toward [minCellSizePercent] to fit more
     *  apps per row inside the [maxWidthPercent] cap. The picker chooses
     *  the **largest** scale in `[minCellSizePercent, cellSizePercent]`
     *  that still fits the desired per-row count — see
     *  `pickFlexibleCellScale`.
     *
     *  Intentionally ignored in [MaxSizeMode.MaxIconsPerRow] mode: there
     *  the per-row count is the user's direct input and a dynamic shrink
     *  would just contradict it. The settings UI greys both the toggle
     *  and the slider in that mode (but keeps the values), so flipping
     *  back to Percent restores them. */
    val flexibleCellSize: Boolean = false,
    /** Floor for the dynamic cell scale picker when [flexibleCellSize] is
     *  on. Same percent unit as [cellSizePercent]; [sanitized] clamps to
     *  `50..cellSizePercent` so it can never exceed the upper bound. */
    val minCellSizePercent: Int = 70,
    /** Icon-glyph size expressed as a **percentage of the cell's max
     *  inner width** (i.e. of the area between the cell's horizontal
     *  paddings, at the cell's widest). 100 = fills the cell width
     *  exactly. Range 20..100 (clamped in [sanitized]); default 67 ≈
     *  the historical 80 dp glyph inside a 120 dp inner cell, which is
     *  the visual the app shipped with before this knob existed.
     *
     *  Specifying the icon size as a fraction of the cell guarantees the
     *  icon never exceeds its cell — the previous free-multiplier design
     *  could push the icon past the cell's `widthIn(max=132*cellScale)`
     *  clamp, which left the cell stretched vertically without growing
     *  horizontally (the unbounded `Modifier.size(...)` set the height
     *  to the requested icon size even after the width was clamped). */
    val iconSizePercent: Int = 67,
    /** Which display the switcher opens on. See [SwitcherPlacement]. */
    val windowPlacement: SwitcherPlacement = SwitcherPlacement.MouseScreen,
)

/**
 * Clamp values that feed into coroutine `delay(...)` calls so a hand-edited
 * config or a buggy settings UI can't poison the switcher's runtime path.
 * `delay(...)` allows zero (returns immediately), so most fields are simply
 * coerced non-negative — but `repeatIntervalMs == 0` would spin a tight
 * coroutine loop, so we keep that one ≥ 1 ms. Width values are clamped
 * to ranges the settings sliders also enforce — keeps a hand-edited
 * config from producing an unusable panel.
 */
fun SwitcherSettings.sanitized(): SwitcherSettings {
    val clampedCellSize = cellSizePercent.coerceIn(50, 200)
    return copy(
        showDelayMs = showDelayMs.coerceAtLeast(0L),
        previewDelayMs = previewDelayMs.coerceAtLeast(0L),
        repeatInitialDelayMs = repeatInitialDelayMs.coerceAtLeast(0L),
        repeatIntervalMs = repeatIntervalMs.coerceAtLeast(1L),
        maxWidthPercent = maxWidthPercent.coerceIn(0.3, 1.0),
        maxIconsPerRow = maxIconsPerRow.coerceIn(1, 50),
        selectionExpandDelayMs = selectionExpandDelayMs.coerceAtLeast(0L),
        cellSizePercent = clampedCellSize,
        // Min is bounded above by the upper bound so the range
        // `[minCellSizePercent, cellSizePercent]` is always non-empty;
        // a hand-edited config with min > cell-size collapses to a
        // single-point range at cell-size (i.e. flexible is effectively
        // a no-op until the user re-dials min downward).
        minCellSizePercent = minCellSizePercent.coerceIn(50, clampedCellSize),
        iconSizePercent = iconSizePercent.coerceIn(20, 100),
    )
}

/**
 * Persisted user configuration. Versioned so we can migrate gracefully if
 * the schema changes — older configs missing fields will get defaults via
 * `ignoreUnknownKeys` / kotlinx-serialization defaults. v4 split the
 * single-window `windowFrame` / `inspectorWidth` / `inspectorVisible`
 * triple into per-window frames for the now-separate Settings and
 * Inspector windows, and replaced the px-cap mode with an
 * icons-per-row cap; the old fields are no longer read. v5 adds the
 * title-matching badge rules (Settings → Badges tab). v6 surfaces the
 * switcher-overlay panel + demote-block colours. v7 widens
 * `AccentColorChoice.Custom` from RGB to ARGB. v8 adds the pinning rules
 * (Settings → Pinning tab) — re-parent matching windows under the most
 * recently activated root of the same app. v9 adds [loggingEnabled], a
 * master switch for the diagnostic file at `~/Library/Logs/KAltSwitch.log`.
 */
@Serializable
data class AppConfig(
    val schemaVersion: Int = 9,
    val filters: FilteringRules = FilteringRules(),
    val badges: BadgeRules = BadgeRules(),
    val pinning: PinningRules = PinningRules(),
    /** Settings window position + size. `null` until the first move/resize. */
    val settingsWindowFrame: WindowFrame? = null,
    /** Inspector window position + size. `null` until the first move/resize. */
    val inspectorWindowFrame: WindowFrame? = null,
    val switcher: SwitcherSettings = SwitcherSettings(),
    /** Whether the menubar status item is installed. When false the user
     *  reaches Settings via Dock-icon click / Spotlight-relaunch (which
     *  trigger `applicationShouldHandleReopen`). */
    val showMenubarIcon: Boolean = true,
    /** Whether macOS auto-launches the app at user login (via SMAppService). */
    val launchAtLogin: Boolean = false,
    /** When true, the inspector and switcher hide windows that aren't on the
     *  current Mission Control space. Default false → show windows from
     *  every space (the alt-tab-macos default). */
    val currentSpaceOnly: Boolean = false,
    /** Master switch for the diagnostic file at `~/Library/Logs/KAltSwitch.log`.
     *  Default `true` so first-launch and crash investigations have data. When
     *  flipped off, [com.shish.kaltswitch.log.log] becomes a no-op; the file
     *  still receives the SESSION START banner and stdout/stderr redirect so
     *  any later toggle-on falls into a coherent stream. */
    val loggingEnabled: Boolean = true,
    /** Highlight colour. Default is the warm yellow-orange the app shipped
     *  with; toggling to [AccentColorChoice.UseSystem] mirrors the macOS
     *  control-accent setting in real time. ARGB-packed since v7 — the
     *  picker's alpha slider applies. */
    val accentColor: AccentColorChoice = AccentColorChoice.Custom(0xFFFFC107),
    /** ARGB-packed (`0xAARRGGBB`) backdrop of the switcher overlay's
     *  rounded plate. Default matches the originally-hardcoded
     *  `Color(0xFF1B1B1F)` — opaque, very dark blueish-black. Alpha
     *  technically supported but values below ~0xE0 wash out the white-on-
     *  dark text rendered over it; the UI doesn't expose alpha for this
     *  field for that reason. */
    val switcherPanelBgArgb: Long = 0xFF1B1B1F,
    /** ARGB-packed tint laid over the panel plate behind demoted apps and
     *  behind demoted windows inside an app cell. Default `0x33000000` =
     *  20% black; alpha is meaningful (the underlying plate shows through),
     *  so the picker exposes the alpha slider for this one. */
    val switcherDemoteBgArgb: Long = 0x33000000,
)

/**
 * Single shared JSON instance — pretty-printed so the file is hand-editable.
 *
 * `classDiscriminator = "kind"` keys polymorphic predicate variants by their
 * `@SerialName` (e.g. `"kind": "bundleId"`). Default `"type"` was avoided so
 * we don't collide with future fields a user might want to call `type`.
 */
val configJson: Json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "kind"
}
