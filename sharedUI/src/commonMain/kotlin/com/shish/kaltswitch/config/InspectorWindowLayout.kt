package com.shish.kaltswitch.config

/**
 * Native window layout decisions for the Settings/Inspector window.
 *
 * Swift owns `NSWindow` calls, but the math lives here so it is testable and
 * stays next to the persisted [WindowFrame] / [AppConfig] semantics that
 * "stored width = settings-only width" depends on. Callers feed in the live
 * `NSWindow.frame` (translated through a small Swift adapter) and the
 * persisted state from [WorldStore]; they get back what to write to the
 * window or to the store.
 */
data class PersistedInspectorWindowLayout(
    val settingsFrame: WindowFrame,
    val inspectorWidth: Double,
)

/**
 * Reconstruct the window's full frame from the saved [settingsFrame].
 * Adds the saved [inspectorWidth] back on if the inspector started visible.
 * Returns null when there's no saved frame yet (first launch) — Swift falls
 * back to its `NSWindow.center()` default in that case.
 */
fun restoredInspectorWindowFrame(
    settingsFrame: WindowFrame?,
    inspectorVisible: Boolean,
    inspectorWidth: Double,
): WindowFrame? = settingsFrame?.let {
    if (inspectorVisible) it.copy(width = it.width + inspectorWidth) else it
}

/**
 * Take the live window frame (which may include the inspector) and split it
 * back into the persisted shape: settings-only width + extra inspector width.
 *
 * When the inspector is visible the user's resize delta lands on the
 * inspector (the Compose sidebar's `settingsWidth` is fixed at 260dp at the
 * moment, so we keep [settingsWidth] stable and recompute [inspectorWidth]
 * from the new total). When the inspector is hidden the live width *is* the
 * settings-only width.
 */
fun persistInspectorWindowLayout(
    currentFrame: WindowFrame,
    inspectorVisible: Boolean,
    settingsWidth: Double,
    currentInspectorWidth: Double,
    minInspectorWidth: Double,
): PersistedInspectorWindowLayout =
    if (inspectorVisible) {
        PersistedInspectorWindowLayout(
            settingsFrame = currentFrame.copy(width = settingsWidth),
            inspectorWidth = (currentFrame.width - settingsWidth).coerceAtLeast(minInspectorWidth),
        )
    } else {
        PersistedInspectorWindowLayout(
            settingsFrame = currentFrame,
            inspectorWidth = currentInspectorWidth,
        )
    }

/**
 * Compute the next live window frame after the user toggles the inspector.
 * Width grows or shrinks by exactly [inspectorWidth] (so toggling is
 * lossless); origin and height stay put. Hidden direction is clamped to
 * [minSettingsWidth] in case the persisted inspectorWidth ever exceeds the
 * live window width (rare, but possible with wider screens / smaller
 * windows).
 */
fun inspectorVisibilityTargetFrame(
    currentFrame: WindowFrame,
    visible: Boolean,
    inspectorWidth: Double,
    minSettingsWidth: Double,
): WindowFrame {
    val targetWidth = if (visible) {
        currentFrame.width + inspectorWidth
    } else {
        (currentFrame.width - inspectorWidth).coerceAtLeast(minSettingsWidth)
    }
    return currentFrame.copy(width = targetWidth)
}
