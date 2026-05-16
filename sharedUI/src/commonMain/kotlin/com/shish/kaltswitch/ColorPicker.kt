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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * Settings-row pairing: a label, a tappable colour swatch, and a fully
 * editable hex field side by side. Tapping the swatch toggles an inline
 * panel containing the HSV+alpha sliders and one editable hex field per
 * channel ("a", "r", "g", "b"). Both the collapsed hex field and the
 * inline picker emit through the same [onChange], so external state
 * stays the single source of truth.
 *
 * The picker is permanently alpha-aware now — there is no `showAlpha`
 * knob. Call sites that store opaque-only values (the accent up through
 * v6 was 24-bit RGB) need to widen their storage to ARGB first; see
 * [AccentColorChoice.Custom]'s KDoc for the migration story.
 */
@Composable
fun ColorSwatchRow(
    label: String,
    argb: Long,
    onChange: (Long) -> Unit,
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
                            .border(0.5.dp, AppPalette.groupBorder, RoundedCornerShape(4.dp)),
                    )
                }
                OverwriteHexField(
                    value = formatArgbHex(argb),
                    length = 8,
                    onValidChange = { hex -> hex.toLongOrNull(16)?.let(onChange) },
                    modifier = Modifier.width(108.dp),
                )
            }
        }
        if (expanded) {
            Box(
                Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                ColorPicker(argb = argb, onChange = onChange)
            }
        }
    }
}

/**
 * Compose-native HSV+alpha colour picker. Saturation-value plane, hue
 * track, alpha track, and four per-channel hex fields ("a", "r", "g",
 * "b") — all driven from a single `0xAARRGGBB` Long.
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
    modifier: Modifier = Modifier,
) {
    val initial = remember { argbToHsva(argb) }
    var h by remember { mutableStateOf(initial.h) }
    var s by remember { mutableStateOf(initial.s) }
    var v by remember { mutableStateOf(initial.v) }
    var a by remember { mutableStateOf(initial.a) }

    LaunchedEffect(argb) {
        if (hsvaToArgb(h, s, v, a) != argb) {
            val incoming = argbToHsva(argb)
            // Keep the local hue when the incoming colour has saturation 0
            // (greyscale carries no hue) — otherwise dragging S to 0 and
            // back would reset to red.
            if (incoming.s > 0f) h = incoming.h
            s = incoming.s
            v = incoming.v
            a = incoming.a
        }
    }

    fun emit() {
        onChange(hsvaToArgb(h, s, v, a))
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SvSquare(hue = h, s = s, v = v) { ns, nv ->
            s = ns; v = nv; emit()
        }
        HueSlider(hue = h) { nh ->
            h = nh; emit()
        }
        AlphaSlider(hue = h, s = s, v = v, alpha = a) { na ->
            a = na; emit()
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // ARGB byte order: alpha, red, green, blue.
            ChannelField("a", ((argb ushr 24) and 0xFFL).toInt()) { v ->
                onChange((argb and 0x00FFFFFFL) or (v.toLong() shl 24))
            }
            ChannelField("r", ((argb ushr 16) and 0xFFL).toInt()) { v ->
                onChange((argb and 0xFF00FFFFL) or (v.toLong() shl 16))
            }
            ChannelField("g", ((argb ushr 8) and 0xFFL).toInt()) { v ->
                onChange((argb and 0xFFFF00FFL) or (v.toLong() shl 8))
            }
            ChannelField("b", (argb and 0xFFL).toInt()) { v ->
                onChange((argb and 0xFFFFFF00L) or v.toLong())
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
                Brush.horizontalGradient(listOf(Color.White, Color.Transparent)),
            ),
        )
        // Value layer: top stays bright, bottom fades to black.
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black)),
            ),
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
                .border(0.5.dp, AppPalette.groupBorder, RoundedCornerShape(trackHeight / 2)),
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
                .border(0.5.dp, AppPalette.groupBorder, RoundedCornerShape(trackHeight / 2)),
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
            .border(0.5.dp, Color.Black.copy(alpha = 0.55f), CircleShape),
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
            .border(0.5.dp, Color.Black.copy(alpha = 0.55f), CircleShape),
    )
}

/**
 * One labelled byte-wide hex field (e.g. `a: FF`). Sits in the row of
 * four channel inputs the expanded picker renders under its sliders.
 */
@Composable
private fun ChannelField(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        NativeText(
            "$label:",
            fontSize = 12.sp,
            color = AppPalette.textSecondary,
        )
        OverwriteHexField(
            value = value.toString(16).padStart(2, '0').uppercase(),
            length = 2,
            onValidChange = { hex -> hex.toIntOrNull(16)?.let(onChange) },
            modifier = Modifier.width(34.dp),
        )
    }
}

/**
 * Hex text field that behaves like the keyboard's Insert key is on:
 * typing a character at the cursor *replaces* the character there
 * rather than shifting the existing chars right. The field stays
 * exactly [length] chars at all times; backspace and delete move the
 * cursor without changing text (otherwise the field would shrink and
 * we'd need a separate "now what" state).
 *
 * Built on top of [BasicTextField] with a [TextFieldValue] so we can
 * inspect both the old and new cursor positions on every change.
 * Plain `BasicTextField(value: String, ...)` doesn't surface selection,
 * which makes the insert→overwrite conversion impossible to do in
 * `onValueChange` alone.
 */
@Composable
private fun OverwriteHexField(
    value: String,
    length: Int,
    onValidChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var tfv by remember(length) {
        mutableStateOf(TextFieldValue(value, TextRange(0)))
    }
    // External resync: when the parent pushes a new `value` (e.g. SV
    // square dragged → ARGB recomputed → hex restringified), update
    // our text without trampling the cursor unless we have to.
    LaunchedEffect(value) {
        if (tfv.text != value) {
            val cursor = tfv.selection.start.coerceIn(0, value.length)
            tfv = TextFieldValue(value, TextRange(cursor))
        }
    }
    val pal = AppPalette
    Box(
        modifier
            .heightIn(min = 22.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(pal.controlFill)
            .border(0.5.dp, pal.groupBorder, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        BasicTextField(
            value = tfv,
            onValueChange = { newTfv ->
                val rewritten = overwriteHex(
                    oldText = tfv.text,
                    oldSelection = tfv.selection,
                    newText = newTfv.text,
                    newSelection = newTfv.selection,
                    length = length,
                )
                tfv = rewritten
                if (rewritten.text != value && rewritten.text.length == length) {
                    onValidChange(rewritten.text)
                }
            },
            singleLine = true,
            cursorBrush = SolidColor(pal.textPrimary),
            textStyle = TextStyle(
                color = pal.textPrimary,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Pure logic for [OverwriteHexField]. Given the field's prior text +
 * selection and the new text + selection that `BasicTextField` is
 * proposing, compute the [TextFieldValue] we actually want to commit.
 *
 * Cases handled:
 *  - text unchanged → caret move only, accept newSelection.
 *  - oldSelection had a range (user replaced a span) → splice the
 *    typed chars in over the start of the span, keep the rest of old
 *    text after, so the total stays [length] long.
 *  - collapsed selection + insert (newText longer) → splice the typed
 *    chars in over the chars at/after the caret, dropping the same
 *    number of old chars to keep the field [length] long.
 *  - collapsed selection + delete (newText shorter) → keep old text,
 *    move the caret per newSelection (so backspace looks like the
 *    caret stepped left without erasing anything).
 *
 * Non-hex characters are filtered out before splicing — pasting "blue"
 * effectively types nothing and leaves the field untouched.
 */
private fun overwriteHex(
    oldText: String,
    oldSelection: TextRange,
    newText: String,
    newSelection: TextRange,
    length: Int,
): TextFieldValue {
    if (oldText == newText) {
        return TextFieldValue(oldText, newSelection)
    }
    fun String.hexUp(): String = filter { c ->
        c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
    }.uppercase()

    val oldStart = oldSelection.start.coerceAtMost(oldText.length)
    val oldEnd = oldSelection.end.coerceAtMost(oldText.length)

    if (oldStart != oldEnd) {
        // The user had a span selected and BasicTextField just replaced it
        // with `inserted`. Splice `inserted` over the chars at [start..),
        // keeping the trailing portion of oldText so total length stays
        // pinned at `length`.
        val insertedEnd = (newSelection.start).coerceAtLeast(oldStart)
        val inserted = newText.substring(oldStart, insertedEnd.coerceAtMost(newText.length)).hexUp()
        val before = oldText.substring(0, oldStart)
        val tailStart = (oldStart + inserted.length).coerceAtMost(oldText.length)
        val after = oldText.substring(tailStart)
        val combined = (before + inserted + after).take(length)
        val final = if (combined.length < length) {
            combined.padEnd(length, '0')
        } else combined
        val cursor = (oldStart + inserted.length).coerceIn(0, length)
        return TextFieldValue(final, TextRange(cursor))
    }

    val delta = newText.length - oldText.length
    if (delta > 0) {
        // Insert: splice inserted chars over the chars at/after the caret.
        val inserted = newText.substring(oldStart, oldStart + delta).hexUp()
        if (inserted.isEmpty()) {
            return TextFieldValue(oldText, oldSelection)
        }
        val before = oldText.substring(0, oldStart)
        val tailStart = (oldStart + inserted.length).coerceAtMost(oldText.length)
        val after = oldText.substring(tailStart)
        val combined = (before + inserted + after).take(length)
        val final = if (combined.length < length) {
            combined.padEnd(length, '0')
        } else combined
        val cursor = (oldStart + inserted.length).coerceIn(0, length)
        return TextFieldValue(final, TextRange(cursor))
    }
    if (delta < 0) {
        // Backspace / Delete. Preserve text length; just adopt the new
        // caret position so the cursor visually steps.
        val cursor = newSelection.start.coerceIn(0, oldText.length)
        return TextFieldValue(oldText, TextRange(cursor))
    }
    // Same length, different bytes — e.g. an IME replaced a digit.
    val sanitized = newText.hexUp().take(length).padEnd(length, '0')
    return TextFieldValue(sanitized, newSelection)
}

private fun formatArgbHex(argb: Long): String =
    argb.toString(16).padStart(8, '0').uppercase()

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
