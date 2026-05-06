package com.shish.kaltswitch

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Lightweight AppKit-mimicking controls. Each composable is a thin Compose
 * implementation that visually resembles its `NSSwitch` / `NSSlider` /
 * `NSGroupBox` counterpart in stock macOS Settings.app — palette comes
 * from [LocalAppPalette]. None of these are full re-implementations:
 * keyboard navigation, accessibility tree, auto-repeat-on-press, etc.,
 * are deliberately out of scope. The bar is "looks native at a glance",
 * not "is a drop-in NSControl substitute".
 */

@Composable
fun NativeText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = AppPalette.textPrimary,
    fontSize: androidx.compose.ui.unit.TextUnit = 13.sp,
    fontWeight: FontWeight = FontWeight.Normal,
) {
    androidx.compose.foundation.text.BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(color = color, fontSize = fontSize, fontWeight = fontWeight),
    )
}

/**
 * Single setting row: trailing-aligned label on the left, control on the
 * right, both vertically centred. Mimics the `Form` row layout AppKit /
 * SwiftUI use in System Settings — the label column hugs natural width,
 * the control column takes the remaining space.
 */
@Composable
fun NativeRow(
    label: String,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    control: @Composable () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 28.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            Modifier.width(140.dp),
            horizontalAlignment = Alignment.End,
        ) {
            NativeText(label, fontSize = 13.sp)
            if (secondary != null) {
                NativeText(
                    secondary,
                    fontSize = 11.sp,
                    color = AppPalette.textSecondary,
                )
            }
        }
        Box(
            Modifier.fillMaxWidth(),
            contentAlignment = Alignment.CenterStart,
        ) { control() }
    }
}

/**
 * Native-style group box with optional title. Renders a soft 6 dp rounded
 * rect with a hairline border. The `Settings.app` look uses this as the
 * primary container for sets of related rows.
 */
@Composable
fun NativeGroupBox(
    modifier: Modifier = Modifier,
    title: String? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (title != null) {
            NativeText(
                title,
                color = AppPalette.textSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(AppPalette.groupBg)
                .border(1.dp, AppPalette.groupBorder, RoundedCornerShape(8.dp))
                .padding(contentPadding),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
        }
    }
}

/** Hairline divider between rows inside a group box. */
@Composable
fun NativeRowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 152.dp)
            .height(1.dp)
            .background(AppPalette.divider),
    )
}

/**
 * Pill-shaped toggle that visually matches `NSSwitch`. Track tints with
 * the accent on, fades to the palette's control track off; the thumb is
 * a white circle that slides 16 dp. No animation easing — straight
 * tween — to keep things lightweight.
 */
@Composable
fun NativeToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val trackOff = AppPalette.controlTrack
    val track by animateColorAsState(
        targetValue = if (checked) AccentColor else trackOff,
        label = "switch-track",
    )
    val offset by animateDpAsState(
        targetValue = if (checked) 16.dp else 0.dp,
        label = "switch-thumb",
    )
    Box(
        modifier
            .width(36.dp)
            .height(20.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(track)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onCheckedChange(!checked) },
    ) {
        Box(
            Modifier
                .padding(2.dp)
                .offset(x = offset)
                .size(16.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

/**
 * Slim slider with a minimal thumb. Matches the look of `NSSlider` more
 * than Material's `Slider`: 4 dp track, 14 dp thumb, accent tint on the
 * filled portion. Continuous output via [onValueChange]; no tick marks.
 */
@Composable
fun NativeSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val thumbDp = 14.dp
    val trackHeightDp = 4.dp
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .heightIn(min = 22.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val thumbPx = with(density) { thumbDp.toPx() }
        val usable = (widthPx - thumbPx).coerceAtLeast(1f)
        val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.0001f)
        val frac = ((value - valueRange.start) / span).coerceIn(0f, 1f)
        val thumbOffsetPx = frac * usable

        fun reportFromOffset(px: Float) {
            val clamped = px.coerceIn(0f, usable)
            val newFrac = clamped / usable
            onValueChange(valueRange.start + newFrac * span)
        }

        // Background track.
        Box(
            Modifier
                .fillMaxWidth()
                .height(trackHeightDp)
                .clip(RoundedCornerShape(2.dp))
                .background(AppPalette.controlTrack),
        )
        // Filled portion.
        Box(
            Modifier
                .width(with(density) { (thumbPx / 2 + thumbOffsetPx).toDp() })
                .height(trackHeightDp)
                .clip(RoundedCornerShape(2.dp))
                .background(AccentColor),
        )
        // Thumb. Drag horizontally OR tap-anywhere on the row.
        var dragX by remember { mutableStateOf<Float?>(null) }
        Box(
            Modifier
                .offset(x = with(density) { thumbOffsetPx.toDp() })
                .size(thumbDp)
                .clip(CircleShape)
                .background(Color.White)
                .border(0.5.dp, AppPalette.groupBorder, CircleShape)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { offsetIn ->
                            dragX = thumbOffsetPx + offsetIn.x
                            reportFromOffset(dragX!!)
                        },
                        onDragEnd = { dragX = null },
                        onDragCancel = { dragX = null },
                    ) { _, drag ->
                        val cur = (dragX ?: thumbOffsetPx) + drag
                        dragX = cur
                        reportFromOffset(cur)
                    }
                },
        )
        // Tap-on-track to jump.
        Box(
            Modifier
                .fillMaxWidth()
                .height(22.dp)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull() ?: continue
                            if (ch.pressed && ch.previousPressed != ch.pressed) {
                                reportFromOffset(ch.position.x - thumbPx / 2)
                            }
                        }
                    }
                },
        )
    }
}

/**
 * macOS-style segmented tab bar — 1-of-N selection, used at the top of
 * the Settings window for the General / Rules switch. Visually a soft
 * pill with the selected segment raised on the palette's control-selected
 * fill.
 */
@Composable
fun NativeTabBar(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(AppPalette.controlTrack)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val bg by animateColorAsState(
                if (selected) AppPalette.controlSelected else Color.Transparent,
                label = "tab-bg-$index",
            )
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(bg)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(index) }
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                NativeText(
                    label,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/**
 * Plain push-button that mimics `NSButton(.recessed)` — small, white-ish
 * fill, hairline border, accent text on click. Used for Add / Delete /
 * Reorder actions in the Rules tab.
 */
@Composable
fun NativeButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Boolean = false,
) {
    val bg = if (accent) AccentColor else AppPalette.controlFill
    val fg = if (accent) Color.White else AppPalette.textPrimary
    Box(
        modifier
            .heightIn(min = 22.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(bg.copy(alpha = if (enabled) 1f else 0.5f))
            .border(0.5.dp, AppPalette.groupBorder, RoundedCornerShape(5.dp))
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(horizontal = 10.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        NativeText(label, color = fg)
    }
}

/**
 * Read-only / minimally-decorated text-input box. AppKit-equivalent:
 * `NSTextField.bezeled`. Thin 0.5 dp border, white fill (palette-aware),
 * 13 sp text. `singleLine = true` so newlines are silently dropped.
 */
@Composable
fun NativeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
) {
    val pal = AppPalette
    Box(
        modifier
            .heightIn(min = 22.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(pal.controlFill)
            .border(0.5.dp, pal.groupBorder, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        if (value.isEmpty() && placeholder != null) {
            NativeText(placeholder, color = pal.textSecondary)
        }
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = { v -> onValueChange(if (singleLine) v.replace("\n", "") else v) },
            singleLine = singleLine,
            cursorBrush = androidx.compose.ui.graphics.SolidColor(pal.textPrimary),
            textStyle = TextStyle(color = pal.textPrimary, fontSize = 13.sp),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

