package com.shish.kaltswitch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import com.shish.kaltswitch.config.AccentColorChoice

/**
 * Single source of truth for the accent / highlight colour used across the
 * inspector window and the switcher overlay. Defaults to a warm
 * yellow-orange (the "Material Yellow 500" tone the app shipped with);
 * call sites read [AccentColor] inside `@Composable` scope.
 *
 * Override at the tree root via [ProvideAccent] — typically wired to the
 * user-configurable colour in `AppConfig` plus the system accent flow that
 * Swift refreshes from `NSColor.controlAccentColor`.
 */
val LocalAccentColor = compositionLocalOf { DefaultAccent }

private val DefaultAccent: Color = Color(0xFFFFC107)

/** Convenience accessor for use inside Composables: `Modifier.background(AccentColor)`. */
val AccentColor: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalAccentColor.current

/**
 * Wrap a subtree with an explicit accent colour. Pass [color] = null to fall
 * back to the default (so `null`-aware call sites can pipe through a flow
 * that hasn't seeded yet).
 */
@Composable
fun ProvideAccent(color: Color?, content: @Composable () -> Unit) {
    val effective = color ?: DefaultAccent
    CompositionLocalProvider(LocalAccentColor provides effective, content = content)
}

/**
 * Resolve the user's [AccentColorChoice] to a concrete [Color]. `UseSystem`
 * needs the latest value Swift pushed into [systemAccentRgb]; if Swift
 * hasn't seeded yet (null), fall back to the bundled default rather than
 * flashing white.
 */
fun resolveAccent(choice: AccentColorChoice, systemAccentRgb: Long?): Color = when (choice) {
    is AccentColorChoice.Custom -> rgbToColor(choice.rgb)
    AccentColorChoice.UseSystem -> systemAccentRgb?.let(::rgbToColor) ?: DefaultAccent
}

/** 0xRRGGBB → opaque [Color]. Accepts the long encoded as `(r shl 16) or (g shl 8) or b`. */
fun rgbToColor(rgb: Long): Color {
    val r = ((rgb shr 16) and 0xFF).toInt()
    val g = ((rgb shr 8) and 0xFF).toInt()
    val b = (rgb and 0xFF).toInt()
    return Color(red = r, green = g, blue = b)
}
