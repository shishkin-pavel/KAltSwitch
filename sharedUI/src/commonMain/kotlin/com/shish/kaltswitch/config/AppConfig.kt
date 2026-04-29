package com.shish.kaltswitch.config

import com.shish.kaltswitch.model.FilteringRules
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
    val previewEnabled: Boolean = false,
    /** How long to keep the shortcut held before auto-advancing through
     *  elements. A short tap (release before this delay) advances exactly
     *  once. Default tuned to be longer than typical OS keyboard repeat
     *  initial-delay so quick taps stay one-shot. */
    val repeatInitialDelayMs: Long = 400L,
    /** Step interval once auto-advance is engaged. */
    val repeatIntervalMs: Long = 120L,
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
