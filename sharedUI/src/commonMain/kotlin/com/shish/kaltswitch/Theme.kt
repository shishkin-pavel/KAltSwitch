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

// ──────────────────────────── AppKit-mimicking palette ────────────────────────────

/**
 * Static colour set tuned to look like a stock macOS settings window in
 * either Light or Dark appearance. Hand-picked from sampling Settings.app
 * — the system colours aren't directly exposable through the Compose
 * Multiplatform skiko backend without going through `NSColor`/AppKit, and
 * a fixed palette is good enough for our uses (Settings + Inspector windows
 * only; the switcher overlay keeps its own dark cinematic look).
 */
data class AppPaletteColors(
    val isDark: Boolean,
    /** Window backdrop. */
    val windowBg: Color,
    /** Group-box / card backdrop laid on the window. */
    val groupBg: Color,
    /** Hairline separator between rows. */
    val divider: Color,
    /** 1 dp inset border for group boxes. */
    val groupBorder: Color,
    /** Primary text — labels, headings. */
    val textPrimary: Color,
    /** Secondary text — value tags, hints, disabled labels. */
    val textSecondary: Color,
    /** Inactive control track / unselected pill bg. */
    val controlTrack: Color,
    /** Selected segment / pressed button backdrop. */
    val controlSelected: Color,
    /** Plain control fill (e.g. an unchecked switch's pill). */
    val controlFill: Color,
    /** Tab bar / toolbar backdrop, slightly different from windowBg so the
     *  tab strip reads as a separate band. */
    val toolbarBg: Color,
)

private val LightPalette = AppPaletteColors(
    isDark = false,
    windowBg = Color(0xFFECECEC),
    groupBg = Color(0xFFF7F7F7),
    divider = Color(0xFFD4D4D4),
    groupBorder = Color(0xFFD8D8D8),
    textPrimary = Color(0xFF1D1D1F),
    textSecondary = Color(0xFF6C6C6E),
    controlTrack = Color(0xFFD9D9D9),
    controlSelected = Color(0xFFFFFFFF),
    controlFill = Color(0xFFFFFFFF),
    toolbarBg = Color(0xFFE6E6E6),
)

private val DarkPalette = AppPaletteColors(
    isDark = true,
    windowBg = Color(0xFF1E1E1E),
    groupBg = Color(0xFF2A2A2A),
    divider = Color(0xFF3A3A3A),
    groupBorder = Color(0xFF3A3A3A),
    textPrimary = Color(0xFFF2F2F7),
    textSecondary = Color(0xFF8E8E93),
    controlTrack = Color(0xFF3A3A3C),
    controlSelected = Color(0xFF4A4A4C),
    controlFill = Color(0xFF3A3A3C),
    toolbarBg = Color(0xFF252525),
)

val LocalAppPalette = compositionLocalOf { LightPalette }

val AppPalette: AppPaletteColors
    @Composable
    @ReadOnlyComposable
    get() = LocalAppPalette.current

@Composable
fun ProvideAppPalette(isDark: Boolean, content: @Composable () -> Unit) {
    val pal = if (isDark) DarkPalette else LightPalette
    CompositionLocalProvider(LocalAppPalette provides pal, content = content)
}
