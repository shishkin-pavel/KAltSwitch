package com.shish.kaltswitch

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.animateBounds
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.layout.LookaheadScope
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
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

/**
 * Backdrop tint for *demoted* apps and windows — apps the inspector's
 * filter rules sent to the secondary bucket, plus the trailing windows of
 * a partially-demoted app. A faint *black* tint over the panel's blurred
 * background reads as a recessed/sunken plate (vs the previous 0x1FFFFFFF
 * "raised" feel which had poor text contrast). Adjacent demoted cells'
 * backdrops touch (via FlowRow `spacedBy(0.dp)` + outer-clip-then-bg
 * modifier order) and share rounded corners only on the block's outer
 * edges (first/last demoted cell), so a sequence reads as one visual block.
 */
private val DemoteBackdropColor = Color(0x33000000)

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
            // Split entries into the show bucket (rendered as direct
            // children of the outer FlowRow) and the demote bucket
            // (wrapped in a single grey-backdrop Box). Wrapping all
            // demoted cells in ONE Box gives them a uniform background
            // height regardless of any single cell's content height — a
            // demoted app *with* windows is taller than its windowless
            // siblings, but the Box's bg fills the row's max height so
            // shorter cells no longer leave a transparent gap below them.
            val showEntries = entries.subList(0, withWindowsCount.coerceAtMost(entries.size))
            val demoteEntries = if (withWindowsCount < entries.size) {
                entries.subList(withWindowsCount, entries.size)
            } else emptyList()
            // LookaheadScope + Modifier.animateBounds on each AppCell makes
            // tile *movement* (positional reorder, neighbour shifts when a
            // sibling appears/disappears, show ↔ demote bucket transitions
            // when filters change live) slide instead of snap. Without
            // lookahead, FlowRow's standard layout pass commits final
            // positions immediately and animateContentSize alone handles
            // only size changes. With lookahead the cell's "before" and
            // "after" bounds are both known and the modifier interpolates.
            LookaheadScope {
            FlowRow(
                modifier = Modifier
                    // FlowRow's measured size shrinks when an AppCell unmounts
                    // and grows when one appears. Animate both — the parent
                    // Box's animateContentSize handles the overall envelope,
                    // this one keeps the row layout itself fluid mid-flow.
                    .animateContentSize(animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing)),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val tileMotion = BoundsTransform { _, _ ->
                    tween(durationMillis = 200, easing = FastOutSlowInEasing)
                }
                showEntries.forEachIndexed { showIndex, entry ->
                    val appIndex = showIndex
                    androidx.compose.runtime.key(entry.app.pid) {
                        AppCell(
                            modifier = Modifier.animateBounds(
                                lookaheadScope = this@LookaheadScope,
                                boundsTransform = tileMotion,
                            ),
                            name = entry.app.name,
                            pid = entry.app.pid,
                            iconBytes = iconsByPid[entry.app.pid],
                            isHidden = entry.app.isHidden,
                            isDemoted = false,
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
                if (demoteEntries.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(DemoteBackdropColor)
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            demoteEntries.forEachIndexed { demoteIndex, entry ->
                                val appIndex = withWindowsCount + demoteIndex
                                androidx.compose.runtime.key(entry.app.pid) {
                                    AppCell(
                                        modifier = Modifier.animateBounds(
                                            lookaheadScope = this@LookaheadScope,
                                            boundsTransform = tileMotion,
                                        ),
                                        name = entry.app.name,
                                        pid = entry.app.pid,
                                        iconBytes = iconsByPid[entry.app.pid],
                                        isHidden = entry.app.isHidden,
                                        isDemoted = true,
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
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun AppCell(
    modifier: Modifier = Modifier,
    name: String,
    pid: Int,
    iconBytes: ByteArray?,
    isHidden: Boolean,
    isDemoted: Boolean,
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
    // The demote cue is carried by the parent Box's backdrop + the icon
    // desaturation; the cell's text colour matches the normal-row colour
    // so it stays legible against the darker backdrop.
    val nameColor = if (isSelected) Color.White else Color(0xFFCCCCCC)
    // The demote backdrop now lives on the parent Box (not per-cell), so
    // the cell's modifier chain is just the inner clip + selection
    // border + interaction. Doing the bg per-cell broke under varying
    // cell heights — a demoted app with windows is taller than its
    // windowless siblings; per-cell bg leaves a transparent strip below
    // the shorter cells. The Box-wrapper fills uniform height across the
    // whole demote section.
    Column(
        modifier
            .widthIn(min = 92.dp, max = 132.dp)
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
            AppIconBox(pid = pid, iconBytes = iconBytes, name = name, isDemoted = isDemoted)
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
            // Center on the icon's column. Without this the text would
            // ellipsis from the right edge under the cell's content
            // arrangement, looking off-balance under a centered icon.
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (windows.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            WindowList(
                appName = name,
                windows = windows,
                shownWindowCount = shownWindowCount,
                isAppSelected = isSelected,
                selectedWindowIndex = selectedWindowIndex,
                onHoverWindow = onHoverWindow,
                onClickWindow = onClickWindow,
            )
        }
    }
}

/**
 * Two-container window list: the leading `shownWindowCount` rows render
 * directly on the cell background; the trailing rows are wrapped in a
 * grey-backed Column so the demote bucket reads as a unified block (the
 * same role the old colour-accent hairline served, but visually softer
 * and consistent with the app-level demote backdrop).
 *
 * Per-window callbacks receive the **full** index in the original
 * `windows` list — the split is purely visual; the controller still
 * navigates `windows[i]`.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun WindowList(
    appName: String,
    windows: List<com.shish.kaltswitch.model.Window>,
    shownWindowCount: Int,
    isAppSelected: Boolean,
    selectedWindowIndex: Int,
    onHoverWindow: (Int) -> Unit,
    onClickWindow: (Int) -> Unit,
) {
    val showWindows = if (shownWindowCount <= 0) emptyList() else windows.take(shownWindowCount)
    val demoteWindows = if (shownWindowCount >= windows.size) emptyList() else windows.drop(shownWindowCount)
    Column(
        // animateContentSize on the outer wrapper so removing/adding rows
        // (or moving a row from the show bucket into demote when filters
        // change live) reflows smoothly.
        Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing)),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (showWindows.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                showWindows.forEachIndexed { i, w ->
                    androidx.compose.runtime.key(w.id) {
                        WindowTitleRow(
                            title = effectiveWindowTitle(w.title, appName),
                            isActive = isAppSelected && i == selectedWindowIndex,
                            isMinimized = w.isMinimized,
                            isFullscreen = w.isFullscreen,
                            isDemoted = false,
                            onHover = { onHoverWindow(i) },
                            onClick = { onClickWindow(i) },
                        )
                    }
                }
            }
        }
        if (demoteWindows.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(DemoteBackdropColor)
                    .padding(horizontal = 2.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                demoteWindows.forEachIndexed { i, w ->
                    val fullIndex = i + shownWindowCount
                    androidx.compose.runtime.key(w.id) {
                        WindowTitleRow(
                            title = effectiveWindowTitle(w.title, appName),
                            isActive = isAppSelected && fullIndex == selectedWindowIndex,
                            isMinimized = w.isMinimized,
                            isFullscreen = w.isFullscreen,
                            isDemoted = true,
                            onHover = { onHoverWindow(fullIndex) },
                            onClick = { onClickWindow(fullIndex) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppIconBox(pid: Int, iconBytes: ByteArray?, name: String, isDemoted: Boolean) {
    val icon: ImageBitmap? = rememberAppIcon(pid, iconBytes)
    // Light desaturation for demoted icons — keeps app branding identifiable
    // (full grayscale erases too much information when there are 30 demoted
    // apps in a row) while still clearly tagging the bucket. The matrix is
    // remembered per-flag so we don't reallocate it every recomposition.
    val demoteColorFilter = remember(isDemoted) {
        if (isDemoted) {
            ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.3f) })
        } else null
    }
    Box(
        Modifier
            // 80dp (was 64dp). The icon is the strongest visual anchor for
            // app identification, especially in a row of unfamiliar apps;
            // bumping size is the cheapest readability win.
            .size(80.dp),
        // No background — sits directly on the panel's blur. The Color(0x22FFFFFF)
        // backplate the previous version had created a subtle "icon tray"
        // effect that diluted the icon's own design (especially for icons
        // with their own circular backgrounds, like Safari).
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            androidx.compose.foundation.Image(
                bitmap = icon,
                contentDescription = name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                colorFilter = demoteColorFilter,
            )
        } else {
            Text(
                name.take(1).uppercase(),
                // Slightly dimmer when demoted but still readable on the
                // darker backdrop. The desaturation matrix doesn't apply
                // to this fallback Text path.
                color = if (isDemoted) Color(0xFFCCCCCC) else Color(0xFFEEEEEE),
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp,
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
    isDemoted: Boolean,
    onHover: () -> Unit,
    onClick: () -> Unit,
) {
    val bg = if (isActive) AccentColor else Color.Transparent
    val fg = when {
        isActive -> Color.Black
        // Demoted titles share the normal-row colour; the demote cue
        // is the backdrop, not the text dim — the previous 0x8A was
        // unreadable against any backdrop tint.
        else -> Color(0xFFBBBBBB)
    }
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
