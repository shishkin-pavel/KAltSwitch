package com.shish.kaltswitch.config

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
 * Selector for how a max-size value is interpreted: as a fraction of the
 * containing screen's `visibleFrame` ([Percent]) or as an absolute pixel/point
 * count ([Dp]). Stored alongside both candidate values so toggling between
 * modes preserves whatever was last entered for each.
 */
@Serializable
enum class MaxSizeMode { Percent, Dp }

/**
 * Switcher behaviour knobs. Surfaced in the inspector's Settings panel and
 * read live by [com.shish.kaltswitch.switcher.SwitcherController].
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
    /** Whether [maxWidthPercent] or [maxWidthDp] is the active cap. Both
     *  values are kept so toggling the mode doesn't lose the user's
     *  previous fine-tuning. */
    val maxWidthMode: MaxSizeMode = MaxSizeMode.Percent,
    /** Fraction (0..1) of the session screen's `visibleFrame.width` used
     *  as the panel's max width when [maxWidthMode] = [MaxSizeMode.Percent]. */
    val maxWidthPercent: Double = 0.9,
    /** Absolute width in points (= dp on macOS) used as the panel's max
     *  width when [maxWidthMode] = [MaxSizeMode.Dp]. */
    val maxWidthDp: Double = 1200.0,
)

/**
 * Clamp values that feed into coroutine `delay(...)` calls so a hand-edited
 * config or a buggy settings UI can't poison the switcher's runtime path.
 * `delay(...)` allows zero (returns immediately), so most fields are simply
 * coerced non-negative — but `repeatIntervalMs == 0` would spin a tight
 * coroutine loop, so we keep that one ≥ 1 ms. Max-width values are clamped
 * to ranges that the settings sliders also enforce — keeps a hand-edited
 * config from producing a tiny or absurdly wide panel.
 */
fun SwitcherSettings.sanitized(): SwitcherSettings = copy(
    showDelayMs = showDelayMs.coerceAtLeast(0L),
    previewDelayMs = previewDelayMs.coerceAtLeast(0L),
    repeatInitialDelayMs = repeatInitialDelayMs.coerceAtLeast(0L),
    repeatIntervalMs = repeatIntervalMs.coerceAtLeast(1L),
    maxWidthPercent = maxWidthPercent.coerceIn(0.3, 1.0),
    maxWidthDp = maxWidthDp.coerceIn(400.0, 3000.0),
)

/**
 * Persisted user configuration. Versioned so we can migrate gracefully if the
 * schema changes — older configs missing fields will get defaults via
 * `ignoreUnknownKeys` / kotlinx-serialization defaults.
 */
@Serializable
data class AppConfig(
    val schemaVersion: Int = 3,
    val filters: FilteringRules = FilteringRules(),
    /** Window position + height + the *settings-only* width — the width
     *  the window collapses to when the inspector is hidden. Resizing
     *  while the inspector is visible changes [inspectorWidth] instead. */
    val windowFrame: WindowFrame? = null,
    /** Width added to [windowFrame.width] when the inspector pane is shown.
     *  Toggling the inspector grows/shrinks the window by exactly this
     *  amount, instantly — no animation. */
    val inspectorWidth: Double = 480.0,
    val switcher: SwitcherSettings = SwitcherSettings(),
    /** Whether the right-side inspector panel is visible. When false the
     *  sidebar (Settings + Filters) takes the full window width and the
     *  window title drops "Inspector". */
    val inspectorVisible: Boolean = true,
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
