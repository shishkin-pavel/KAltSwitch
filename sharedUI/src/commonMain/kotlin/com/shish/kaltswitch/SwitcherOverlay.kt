package com.shish.kaltswitch

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shish.kaltswitch.icon.rememberAppIcon
import com.shish.kaltswitch.model.SwitcherAction
import com.shish.kaltswitch.model.SwitcherEntry
import com.shish.kaltswitch.model.SwitcherEvent
import com.shish.kaltswitch.model.SwitcherState
import com.shish.kaltswitch.model.WindowId
import com.shish.kaltswitch.switcher.SwitcherUiState

/**
 * Switcher overlay. Renders the active session as a horizontal row of app cells with
 * vertical window-title columns. Apps without windows are rendered after a vertical
 * separator. Selection is reflected by a coloured outline + tinted text.
 *
 * Captures keyboard navigation: tab/shift-tab and ←/→ move between apps;
 * `/shift-` and ↑/↓ move between windows; Esc cancels.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SwitcherOverlay(
    ui: SwitcherUiState,
    iconsByPid: Map<Int, ByteArray>,
    onNavigate: (SwitcherEvent) -> Unit,
    onEsc: () -> Unit,
    onShortcut: (SwitcherEntry) -> Unit,
    onPointAt: (appIndex: Int, windowIndex: Int?) -> Unit,
    onPointerMoved: () -> Unit,
    onPanelSize: (Float, Float) -> Unit,
    onCommit: () -> Unit,
    onAction: (SwitcherAction) -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { ev -> handleKey(ev, onNavigate, onEsc, onShortcut, onAction) }
            // Real pointer-move events flip the controller's stationary-mouse
            // gate. Without this, hover-Enter events fired purely because
            // the panel just appeared under a stationary mouse would yank
            // the keyboard-selected default cursor away.
            .onPointerEvent(PointerEventType.Move) { onPointerMoved() },
        contentAlignment = Alignment.Center,
    ) {
        SwitcherPanel(ui, iconsByPid, onPointAt, onCommit, onPanelSize)
    }
}

/** Translates a single [KeyEvent] into the appropriate controller call.
 *  Returns true if the event was consumed.
 *
 *  Tab and grave (`/~`) are deliberately NOT handled here even though the
 *  panel has keyboard focus: cmd+tab / cmd+grave are registered as global
 *  Carbon hotkeys and that's the canonical entry point. macOS keyboard
 *  auto-repeat for the cmd+tab combo can leak plain `tab` keyDown events
 *  through to Compose despite Carbon claiming the hotkey, and acting on
 *  them here causes the cursor to skip several elements per actual press
 *  (each repeat advances once). Arrow keys and Esc remain — they aren't
 *  Carbon-registered, and arrow navigation is the common keyboard fallback
 *  for users who want to step around without re-tapping the modifier-key.
 *
 *  Action keys (Q/W/M/H/F) MUST be consumed (return true) regardless of
 *  whether they fire — without that, `cmd+Q` flows through to NSApp's
 *  main menu and **terminates KAltSwitch itself** (we have a "Quit
 *  KAltSwitch" item bound to cmd+Q). Same hazard for cmd+W (Close),
 *  cmd+M (Minimize), cmd+H (Hide app). cmd+F is harmless on its own
 *  (no main-menu binding) but consume it for symmetry. */
private fun handleKey(
    ev: KeyEvent,
    onNavigate: (SwitcherEvent) -> Unit,
    onEsc: () -> Unit,
    onShortcut: (SwitcherEntry) -> Unit,
    onAction: (SwitcherAction) -> Unit,
): Boolean {
    if (ev.type != KeyEventType.KeyDown) return false
    return when (ev.key) {
        Key.Escape -> { onEsc(); true }
        Key.DirectionRight -> { onNavigate(SwitcherEvent.NextApp); true }
        Key.DirectionLeft -> { onNavigate(SwitcherEvent.PrevApp); true }
        Key.DirectionDown -> { onNavigate(SwitcherEvent.NextWindow); true }
        Key.DirectionUp -> { onNavigate(SwitcherEvent.PrevWindow); true }
        Key.Q -> { onAction(SwitcherAction.QuitApp); true }
        Key.W -> { onAction(SwitcherAction.CloseWindow); true }
        Key.M -> { onAction(SwitcherAction.ToggleMinimize); true }
        Key.H -> { onAction(SwitcherAction.ToggleHide); true }
        Key.F -> { onAction(SwitcherAction.ToggleFullscreen); true }
        else -> false
    }
}

/**
 * Tiny circular badge rendered top-right of a cell or row. The colour
 * mirrors the macOS "traffic light" associations users already know:
 *   - yellow ≈ minimize
 *   - blue/grey ≈ hidden (no native traffic-light, but consistent with
 *     menubar "hidden" indicators)
 *   - green ≈ fullscreen / maximize
 *
 * Symbols are unicode glyphs rather than icon assets to keep the overlay
 * dependency-free; the dot, dash and corner brackets are recognisable
 * enough at the 14 dp size used here.
 */
@Composable
private fun StatusBadge(
    glyph: String,
    background: Color,
    contentColor: Color = Color.Black,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(14.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            color = contentColor,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

private val HiddenBadgeColor = Color(0xFFAFAFB7)        // muted grey-blue
private val MinimizedBadgeColor = Color(0xFFFFBD2E)     // macOS yellow traffic-light
private val FullscreenBadgeColor = Color(0xFF28C940)    // macOS green traffic-light

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SwitcherPanel(
    ui: SwitcherUiState,
    iconsByPid: Map<Int, ByteArray>,
    onPointAt: (appIndex: Int, windowIndex: Int?) -> Unit,
    onCommit: () -> Unit,
    onPanelSize: (Float, Float) -> Unit,
) {
    val state = ui.state
    val entries = state.snapshot.all
    if (entries.isEmpty()) return

    val withWindowsCount = state.snapshot.withWindows.size
    val density = LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Cap the panel at the available NSPanel width minus a comfortable
        // margin so its rounded corners don't kiss the screen edges. FlowRow
        // wraps within this width, the surrounding bg Box wraps to the actual
        // content extent, and the rest of the NSPanel stays fully transparent.
        val maxPanelWidth = (maxWidth - 80.dp).coerceAtLeast(240.dp)
        Box(
            Modifier
                .widthIn(max = maxPanelWidth)
                .clip(RoundedCornerShape(16.dp))
                // Faint dark tint over the NSVisualEffectView blur Swift
                // installs underneath. Low alpha so the blurred backdrop
                // dominates; doubles as a fallback when the user has
                // "Reduce transparency" enabled in Accessibility (which
                // turns NSVisualEffectView into a solid-colour fill).
                .background(Color(0x661B1B1F))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(16.dp))
                // Smoothly grow/shrink the panel envelope when an app or
                // window appears/disappears mid-session. NSVisualEffectView
                // tracks via the existing `onPanelSize` callback below — it
                // re-fires on every layout pass, so the blur backdrop's
                // rounded mask stays in lockstep with the animation.
                .animateContentSize(animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing))
                // Push the box's measured size out to Swift so it can drop
                // an NSVisualEffectView underneath, exactly matching the
                // visible rounded rectangle. dp not px — Swift wants
                // points-coordinates for window math.
                .onSizeChanged { size ->
                    with(density) {
                        onPanelSize(size.width.toDp().value, size.height.toDp().value)
                    }
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            FlowRow(
                modifier = Modifier
                    // FlowRow's measured size shrinks when an AppCell unmounts
                    // and grows when one appears. Animate both — the parent
                    // Box's animateContentSize handles the overall envelope,
                    // this one keeps the row layout itself fluid mid-flow.
                    .animateContentSize(animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing)),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                entries.forEachIndexed { appIndex, entry ->
                    if (appIndex == withWindowsCount && withWindowsCount > 0 && withWindowsCount < entries.size) {
                        // Vertical separator between Show and Demote app
                        // buckets. Coloured with the accent so it reads as
                        // a deliberate boundary, not visual noise.
                        Spacer(
                            Modifier
                                .width(1.dp)
                                .height(96.dp)
                                .background(AccentColor)
                        )
                    }
                    // `key` ties the cell to its app's pid so Compose treats
                    // the same app moving in `entries` as a position change,
                    // not unmount + remount — preserving icon caching and
                    // any future per-cell transition state.
                    androidx.compose.runtime.key(entry.app.pid) {
                        AppCell(
                            name = entry.app.name,
                            pid = entry.app.pid,
                            iconBytes = iconsByPid[entry.app.pid],
                            isHidden = entry.app.isHidden,
                            windows = entry.windows,
                            shownWindowCount = entry.shownWindowCount,
                            isSelected = appIndex == state.cursor.appIndex,
                            selectedWindowIndex = if (appIndex == state.cursor.appIndex) state.cursor.windowIndex else -1,
                            onHoverApp = { onPointAt(appIndex, null) },
                            onHoverWindow = { wi -> onPointAt(appIndex, wi) },
                            onClickApp = { onPointAt(appIndex, null); onCommit() },
                            onClickWindow = { wi -> onPointAt(appIndex, wi); onCommit() },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun AppCell(
    name: String,
    pid: Int,
    iconBytes: ByteArray?,
    isHidden: Boolean,
    windows: List<com.shish.kaltswitch.model.Window>,
    shownWindowCount: Int,
    isSelected: Boolean,
    selectedWindowIndex: Int,
    onHoverApp: () -> Unit,
    onHoverWindow: (Int) -> Unit,
    onClickApp: () -> Unit,
    onClickWindow: (Int) -> Unit,
) {
    val borderColor = if (isSelected) AccentColor else Color.Transparent
    val nameColor = if (isSelected) Color.White else Color(0xFFCCCCCC)
    // Hover/click handlers stay on the cell-level Column so the entire app
    // cell (icon + name + window list) is one click target. Per-window rows
    // override the windowIndex via their own handlers.
    Column(
        Modifier
            .widthIn(min = 80.dp, max = 120.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(2.dp, borderColor, RoundedCornerShape(10.dp))
            // Smoothly resize the cell when its window list changes (e.g.
            // user closes one of the windows of this app while the switcher
            // is open). Same easing/duration as the panel envelope so the
            // motions don't desync visually.
            .animateContentSize(animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing))
            .onPointerEvent(PointerEventType.Enter) { onHoverApp() }
            .pointerInput(Unit) { detectTapGestures(onTap = { onClickApp() }) }
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Stack the hidden-status badge on top-right of the icon. Box uses
        // the icon's intrinsic size; the badge is positioned with
        // Alignment.TopEnd and a small `offset` so it slightly overhangs
        // the icon's rounded corner — readable but not cropped.
        //
        // `offset()` accepts negatives; `padding()` does NOT. An earlier
        // attempt with `Modifier.padding(top = (-2).dp, end = (-2).dp)`
        // crashed at the moment the badge first composed (i.e. the very
        // first time the user hid an app via cmd+H), via Compose's
        // `PaddingElement.<init>` precondition check.
        Box(contentAlignment = Alignment.TopEnd) {
            AppIconBox(pid = pid, iconBytes = iconBytes, name = name)
            if (isHidden) {
                StatusBadge(
                    glyph = "−",
                    background = HiddenBadgeColor,
                    contentColor = Color.White,
                    modifier = Modifier.offset(x = 4.dp, y = (-4).dp),
                )
            }
        }
        Text(
            name,
            color = nameColor,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        if (windows.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    // When a window of this app closes, the row unmounts and
                    // the column reflows. animateContentSize on the column
                    // gives the surviving rows a smooth shift instead of an
                    // instant jump.
                    .animateContentSize(animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing)),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                windows.forEachIndexed { i, w ->
                    if (i == shownWindowCount && shownWindowCount > 0 && shownWindowCount < windows.size) {
                        // Horizontal hairline between Show and Demote
                        // windows of the same app. Same accent colour as the
                        // app-bucket separator above for visual consistency.
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(AccentColor)
                        )
                    }
                    androidx.compose.runtime.key(w.id) {
                        WindowTitleRow(
                            title = effectiveWindowTitle(w.title, name),
                            isActive = isSelected && i == selectedWindowIndex,
                            isMinimized = w.isMinimized,
                            isFullscreen = w.isFullscreen,
                            onHover = { onHoverWindow(i) },
                            onClick = { onClickWindow(i) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppIconBox(pid: Int, iconBytes: ByteArray?, name: String) {
    val icon: ImageBitmap? = rememberAppIcon(pid, iconBytes)
    Box(
        Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x22FFFFFF)),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            androidx.compose.foundation.Image(
                bitmap = icon,
                contentDescription = name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(4.dp),
            )
        } else {
            Text(
                name.take(1).uppercase(),
                color = Color(0xFFEEEEEE),
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun WindowTitleRow(
    title: String,
    isActive: Boolean,
    isMinimized: Boolean,
    isFullscreen: Boolean,
    onHover: () -> Unit,
    onClick: () -> Unit,
) {
    val bg = if (isActive) AccentColor else Color.Transparent
    val fg = if (isActive) Color.Black else Color(0xFFBBBBBB)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            // Per-row hover/click handlers run BEFORE the parent cell's,
            // so pointing at a specific window row updates windowIndex
            // instead of resetting to the cell-level default.
            .onPointerEvent(PointerEventType.Enter) { onHover() }
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) }
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            title,
            color = fg,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = true),
        )
        // Trailing status badge. macOS doesn't simultaneously minimize +
        // fullscreen a window (minimized takes precedence visually if both
        // ever showed up), so we render at most one.
        if (isMinimized) {
            StatusBadge(glyph = "−", background = MinimizedBadgeColor)
        } else if (isFullscreen) {
            StatusBadge(glyph = "⤢", background = FullscreenBadgeColor)
        }
    }
}
