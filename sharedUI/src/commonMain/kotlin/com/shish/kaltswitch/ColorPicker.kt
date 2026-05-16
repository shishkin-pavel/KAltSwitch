package com.shish.kaltswitch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * Compose-native HSV(+A) colour picker. Saturation-value plane, hue
 * track, optional alpha track, and a hex text field — all driven from a
 * single `0xAARRGGBB` Long. Matches the rest of the AppKit-mimicking
 * controls in look (rounded corners, hairline border, the same thumb
 * style as [NativeSlider]).
 *
 * Hue is held in *local* state, not derived from [argb], so saturation
 * crossing zero (which kills the hue component of an RGB colour) doesn't
 * snap the picker back to red. [argb] is the source of truth on input
 * (external mutation reseeds local state); the picker's emissions go
 * through [onChange] and don't bounce back through the LaunchedEffect
 * because the equality check rejects identical round-trips.
 */
@Composable
fun ColorPicker(
    argb: Long,
    onChange: (Long) -> Unit,
    showAlpha: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val initial = remember { argbToHsva(argb) }
    var h by remember { mutableStateOf(initial.h) }
    var s by remember { mutableStateOf(initial.s) }
    var v by remember { mutableStateOf(initial.v) }
    var a by remember { mutableStateOf(if (showAlpha) initial.a else 1f) }

    LaunchedEffect(argb) {
        if (hsvaToArgb(h, s, v, a) != argb) {
            val incoming = argbToHsva(argb)
            // Keep the local hue when the incoming colour has saturation 0
            // (greyscale carries no hue) — otherwise dragging S to 0 and
            // back would reset to red.
            if (incoming.s > 0f) h = incoming.h
            s = incoming.s
            v = incoming.v
            a = if (showAlpha) incoming.a else 1f
        }
    }

    fun emit() {
        onChange(hsvaToArgb(h, s, v, if (showAlpha) a else 1f))
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SvSquare(hue = h, s = s, v = v) { ns, nv ->
            s = ns; v = nv; emit()
        }
        HueSlider(hue = h) { nh ->
            h = nh; emit()
        }
        if (showAlpha) {
            AlphaSlider(hue = h, s = s, v = v, alpha = a) { na ->
                a = na; emit()
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ColorSwatch(argb = hsvaToArgb(h, s, v, if (showAlpha) a else 1f), size = 28.dp)
            Box(Modifier.width(if (showAlpha) 110.dp else 90.dp)) {
                HexField(
                    argb = hsvaToArgb(h, s, v, if (showAlpha) a else 1f),
                    showAlpha = showAlpha,
                    onChange = onChange,
                )
            }
        }
    }
}

/**
 * Settings-row pairing: a label, a tappable colour swatch, and the hex
 * text alongside it. Tapping the swatch toggles an inline panel
 * containing [ColorPicker]. Both the swatch and the inline picker emit
 * through the same [onChange] so external state stays the single source
 * of truth.
 */
@Composable
fun ColorSwatchRow(
    label: String,
    argb: Long,
    onChange: (Long) -> Unit,
    showAlpha: Boolean = true,
) {
    var expanded by rememberSaveable(label) { mutableStateOf(false) }
    Column {
        NativeRow(label = label) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .width(28.dp)
                        .height(20.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { expanded = !expanded },
                ) {
                    Box(Modifier.matchParentSize().drawBehind { drawCheckerboard() })
                    Box(Modifier.matchParentSize().background(argbToColor(argb)))
                    Box(
                        Modifier
                            .matchParentSize()
                            .border(0.5.dp, AppPalette.groupBorder, RoundedCornerShape(4.dp))
                    )
                }
                NativeText(
                    formatArgbHex(argb, showAlpha),
                    fontSize = 12.sp,
                    color = AppPalette.textSecondary,
                )
            }
        }
        if (expanded) {
            Box(
                Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                ColorPicker(
                    argb = argb,
                    onChange = onChange,
                    showAlpha = showAlpha,
                )
            }
        }
    }
}

// ─────────────────────────── Picker pieces ───────────────────────────

@Composable
private fun SvSquare(
    hue: Float,
    s: Float,
    v: Float,
    onSvChange: (Float, Float) -> Unit,
) {
    val density = LocalDensity.current
    val widthDp = 240.dp
    val heightDp = 140.dp
    val pureHue = argbToColor(hsvaToArgb(hue, 1f, 1f, 1f))
    BoxWithConstraints(
        Modifier
            .size(widthDp, heightDp)
            .clip(RoundedCornerShape(4.dp))
            .background(pureHue)
            .border(0.5.dp, AppPalette.groupBorder, RoundedCornerShape(4.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val widthPx = size.width.toFloat().coerceAtLeast(1f)
                    val heightPx = size.height.toFloat().coerceAtLeast(1f)
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fun report(p: Offset) {
                        val ns = (p.x / widthPx).coerceIn(0f, 1f)
                        val nv = (1f - p.y / heightPx).coerceIn(0f, 1f)
                        onSvChange(ns, nv)
                    }
                    report(down.position); down.consume()
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull() ?: break
                        if (!ch.pressed) break
                        report(ch.position); ch.consume()
                    }
                }
            },
    ) {
        // Saturation 0 → white at the left edge, fading to pure hue.
        Box(
            Modifier.matchParentSize().background(
                Brush.horizontalGradient(listOf(Color.White, Color.Transparent))
            )
        )
        // Value layer: top stays bright, bottom fades to black.
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black))
            )
        )
        val indicatorXDp = with(density) { (s * maxWidth.toPx()).toDp() }
        val indicatorYDp = with(density) { ((1f - v) * maxHeight.toPx()).toDp() }
        ColorIndicator(offsetXDp = indicatorXDp, offsetYDp = indicatorYDp)
    }
}

@Composable
private fun HueSlider(hue: Float, onChange: (Float) -> Unit) {
    val density = LocalDensity.current
    val trackHeight = 14.dp
    BoxWithConstraints(
        Modifier
            .size(width = 240.dp, height = 22.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val widthPx = size.width.toFloat().coerceAtLeast(1f)
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fun report(x: Float) {
                        val frac = (x / widthPx).coerceIn(0f, 1f)
                        // 359.99 instead of 360 keeps hue=0 (red) when the
                        // user drags to the far right rather than wrapping
                        // around — the cyclic representation is fine
                        // internally but the indicator-positioning math
                        // uses `hue/360` which would land on 0 at exactly
                        // 360, snapping the thumb left visually.
                        onChange((frac * 359.99f).coerceIn(0f, 359.99f))
                    }
                    report(down.position.x); down.consume()
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull() ?: break
                        if (!ch.pressed) break
                        report(ch.position.x); ch.consume()
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(RoundedCornerShape(trackHeight / 2))
                .background(Brush.horizontalGradient(HueStops))
                .border(0.5.dp, AppPalette.groupBorder, RoundedCornerShape(trackHeight / 2))
        )
        val thumbXDp = with(density) { (hue / 360f * maxWidth.toPx()).toDp() }
        SliderThumb(centerXDp = thumbXDp, fill = argbToColor(hsvaToArgb(hue, 1f, 1f, 1f)))
    }
}

@Composable
private fun AlphaSlider(hue: Float, s: Float, v: Float, alpha: Float, onChange: (Float) -> Unit) {
    val density = LocalDensity.current
    val trackHeight = 14.dp
    val opaque = argbToColor(hsvaToArgb(hue, s, v, 1f))
    val clear = opaque.copy(alpha = 0f)
    BoxWithConstraints(
        Modifier
            .size(width = 240.dp, height = 22.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val widthPx = size.width.toFloat().coerceAtLeast(1f)
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fun report(x: Float) {
                        onChange((x / widthPx).coerceIn(0f, 1f))
                    }
                    report(down.position.x); down.consume()
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull() ?: break
                        if (!ch.pressed) break
                        report(ch.position.x); ch.consume()
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(RoundedCornerShape(trackHeight / 2))
                .drawBehind { drawCheckerboard() }
                .background(Brush.horizontalGradient(listOf(clear, opaque)))
                .border(0.5.dp, AppPalette.groupBorder, RoundedCornerShape(trackHeight / 2))
        )
        val thumbXDp = with(density) { (alpha * maxWidth.toPx()).toDp() }
        SliderThumb(centerXDp = thumbXDp, fill = opaque.copy(alpha = alpha))
    }
}

@Composable
private fun ColorIndicator(offsetXDp: Dp, offsetYDp: Dp) {
    val ringDp = 14.dp
    val half = ringDp / 2
    Box(
        Modifier
            .offset(x = offsetXDp - half, y = offsetYDp - half)
            .size(ringDp)
            .clip(CircleShape)
            .border(2.dp, Color.White, CircleShape)
            .border(0.5.dp, Color.Black.copy(alpha = 0.55f), CircleShape)
    )
}

@Composable
private fun SliderThumb(centerXDp: Dp, fill: Color) {
    val sizeDp = 16.dp
    Box(
        Modifier
            .offset(x = centerXDp - sizeDp / 2)
            .size(sizeDp)
            .clip(CircleShape)
            .drawBehind { drawCheckerboard() }
            .background(fill)
            .border(2.dp, Color.White, CircleShape)
            .border(0.5.dp, Color.Black.copy(alpha = 0.55f), CircleShape)
    )
}

@Composable
private fun ColorSwatch(argb: Long, size: Dp = 24.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(4.dp))
            .border(0.5.dp, AppPalette.groupBorder, RoundedCornerShape(4.dp)),
    ) {
        Box(Modifier.matchParentSize().drawBehind { drawCheckerboard() })
        Box(Modifier.matchParentSize().background(argbToColor(argb)))
    }
}

@Composable
private fun HexField(argb: Long, showAlpha: Boolean, onChange: (Long) -> Unit) {
    NativeTextField(
        value = formatArgbHex(argb, showAlpha),
        onValueChange = { raw ->
            val want = if (showAlpha) 8 else 6
            val cleaned = raw.trimStart('#').take(want).uppercase()
            if (cleaned.length == want) {
                cleaned.toLongOrNull(16)?.let { parsed ->
                    val final = if (showAlpha) parsed else (0xFF000000L or parsed)
                    onChange(final)
                }
            }
        },
    )
}

private fun formatArgbHex(argb: Long, showAlpha: Boolean): String {
    return if (showAlpha) {
        argb.toString(16).padStart(8, '0').uppercase()
    } else {
        (argb and 0xFFFFFFL).toString(16).padStart(6, '0').uppercase()
    }
}

// ─────────────────────────── Drawing helpers ───────────────────────────

/** Two-tone 6 dp checker laid behind anything translucent so the alpha
 *  reads. Drawn via `drawBehind` rather than baked into the track widget
 *  because the same pattern is reused under the alpha track, the picker
 *  swatch, and the slider thumb — all different sizes / shapes — and a
 *  shared `DrawScope` extension keeps that consistent. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCheckerboard() {
    val cellPx = 6.dp.toPx()
    val cols = (size.width / cellPx).toInt() + 1
    val rows = (size.height / cellPx).toInt() + 1
    val light = Color(0xFFE6E6E6)
    val dark = Color(0xFFB8B8B8)
    for (r in 0..rows) {
        for (c in 0..cols) {
            val tone = if ((r + c) % 2 == 0) light else dark
            drawRect(
                color = tone,
                topLeft = Offset(c * cellPx, r * cellPx),
                size = Size(cellPx, cellPx),
            )
        }
    }
}

// ─────────────────────────── HSV / ARGB conversion ───────────────────────────

private data class Hsva(val h: Float, val s: Float, val v: Float, val a: Float)

/** ARGB Long → HSVA. Hue in degrees `[0, 360)`; saturation, value, alpha
 *  in `[0, 1]`. Greyscale colours (delta = 0) report hue = 0 — callers
 *  that care should hold hue in their own state. */
private fun argbToHsva(argb: Long): Hsva {
    val a = ((argb shr 24) and 0xFFL).toInt() / 255f
    val r = ((argb shr 16) and 0xFFL).toInt() / 255f
    val g = ((argb shr 8) and 0xFFL).toInt() / 255f
    val b = (argb and 0xFFL).toInt() / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val hRaw = when {
        delta == 0f -> 0f
        max == r -> ((g - b) / delta) * 60f
        max == g -> ((b - r) / delta) * 60f + 120f
        else -> ((r - g) / delta) * 60f + 240f
    }
    val h = if (hRaw < 0f) hRaw + 360f else hRaw
    val sat = if (max == 0f) 0f else delta / max
    return Hsva(h, sat, max, a)
}

/** Inverse of [argbToHsva]. Rounds each channel to the nearest byte. */
private fun hsvaToArgb(h: Float, s: Float, v: Float, a: Float): Long {
    val hh = (h.coerceIn(0f, 359.99f)) / 60f
    val c = v * s
    val x = c * (1f - abs(hh % 2f - 1f))
    val (r1, g1, b1) = when {
        hh < 1f -> Triple(c, x, 0f)
        hh < 2f -> Triple(x, c, 0f)
        hh < 3f -> Triple(0f, c, x)
        hh < 4f -> Triple(0f, x, c)
        hh < 5f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = v - c
    val r = ((r1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255).toLong()
    val g = ((g1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255).toLong()
    val b = ((b1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255).toLong()
    val aI = (a * 255f + 0.5f).toInt().coerceIn(0, 255).toLong()
    return (aI shl 24) or (r shl 16) or (g shl 8) or b
}

private val HueStops = listOf(
    Color.Red,
    Color.Yellow,
    Color.Green,
    Color.Cyan,
    Color.Blue,
    Color.Magenta,
    Color.Red,
)
