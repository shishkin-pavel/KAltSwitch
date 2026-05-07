package com.shish.kaltswitch.config

import com.shish.kaltswitch.model.BadgeRules
import com.shish.kaltswitch.model.FilteringRules
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * User's choice for the highlight / selection colour. `UseSystem` defers
 * to `NSColor.controlAccentColor` (Swift refreshes the value on
 * `NSSystemColorsDidChangeNotification`); `Custom` carries an opaque
 * 0xRRGGBB hex (alpha is implicit 0xFF — there's no use case for a
 * translucent accent yet).
 */
@Serializable
sealed interface AccentColorChoice {
    @Serializable
    @SerialName("system")
    object UseSystem : AccentColorChoice

    @Serializable
    @SerialName("custom")
    data class Custom(val rgb: Long) : AccentColorChoice
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
     *  and panel-level paddings deliberately stay unscaled. */
    val cellSizePercent: Int = 100,
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
fun SwitcherSettings.sanitized(): SwitcherSettings = copy(
    showDelayMs = showDelayMs.coerceAtLeast(0L),
    previewDelayMs = previewDelayMs.coerceAtLeast(0L),
    repeatInitialDelayMs = repeatInitialDelayMs.coerceAtLeast(0L),
    repeatIntervalMs = repeatIntervalMs.coerceAtLeast(1L),
    maxWidthPercent = maxWidthPercent.coerceIn(0.3, 1.0),
    maxIconsPerRow = maxIconsPerRow.coerceIn(1, 50),
    selectionExpandDelayMs = selectionExpandDelayMs.coerceAtLeast(0L),
    cellSizePercent = cellSizePercent.coerceIn(50, 200),
)

/**
 * Persisted user configuration. Versioned so we can migrate gracefully if
 * the schema changes — older configs missing fields will get defaults via
 * `ignoreUnknownKeys` / kotlinx-serialization defaults. v4 split the
 * single-window `windowFrame` / `inspectorWidth` / `inspectorVisible`
 * triple into per-window frames for the now-separate Settings and
 * Inspector windows, and replaced the px-cap mode with an
 * icons-per-row cap; the old fields are no longer read. v5 adds the
 * title-matching badge rules (Settings → Badges tab).
 */
@Serializable
data class AppConfig(
    val schemaVersion: Int = 5,
    val filters: FilteringRules = FilteringRules(),
    val badges: BadgeRules = BadgeRules(),
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
    /** Highlight colour. Default is the warm yellow-orange the app shipped
     *  with; toggling to [AccentColorChoice.UseSystem] mirrors the macOS
     *  control-accent setting in real time. */
    val accentColor: AccentColorChoice = AccentColorChoice.Custom(0xFFC107),
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
