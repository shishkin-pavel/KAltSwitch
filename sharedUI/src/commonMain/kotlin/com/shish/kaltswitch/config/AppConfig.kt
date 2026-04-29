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
    val inspectorFrame: WindowFrame? = null,
    val switcher: SwitcherSettings = SwitcherSettings(),
)

/** Single shared JSON instance — pretty-printed so the file is hand-editable. */
val configJson: Json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}
