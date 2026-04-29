package com.shish.kaltswitch.config

import com.shish.kaltswitch.model.Filters
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
    val schemaVersion: Int = 1,
    val filters: Filters = Filters(),
    /** Window frame to restore when the inspector pane is visible —
     *  the wider, "with-inspector" layout. */
    val inspectorFrame: WindowFrame? = null,
    /** Window frame to restore when the inspector pane is hidden —
     *  the narrower, "settings-only" layout. */
    val settingsFrame: WindowFrame? = null,
    val switcher: SwitcherSettings = SwitcherSettings(),
    /** Whether the right-side inspector panel is visible. When false the
     *  sidebar (Settings + Filters) takes the full window width and the
     *  window title drops "Inspector". */
    val inspectorVisible: Boolean = true,
)

/** Single shared JSON instance — pretty-printed so the file is hand-editable. */
val configJson: Json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}
