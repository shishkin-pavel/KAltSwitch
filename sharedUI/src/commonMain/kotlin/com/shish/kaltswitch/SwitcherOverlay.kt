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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shish.kaltswitch.config.MaxSizeMode
import com.shish.kaltswitch.config.SwitcherSettings
import com.shish.kaltswitch.icon.rememberAppIcon
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
    switcherSettings: SwitcherSettings,
    onNavigate: (SwitcherEvent) -> Unit,
    onEsc: () -> Unit,
    onShortcut: (SwitcherEntry) -> Unit,
    onPointAt: (appIndex: Int, windowIndex: Int?) -> Unit,
    onPointerMoved: () -> Unit,
    onPanelSize: (Float, Float) -> Unit,
    onCommit: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    // The outer Box fills the *entire Compose scene*, which since iter48
    // is sized to the captured screen's visibleFrame and pinned to that
    // screen rect via Swift updating the Compose-host NSView's
    // frame.origin on every NSPanel `setFrame`. That means Compose's
    // coordinate system is screen-stable: the NSPanel can resize and
    // reposition freely (animation, recentering, etc.) without dragging
    // Compose content along — so the brief gap between AppKit moving
    // the window's left edge and Compose rendering at the new
    // panel-relative position no longer manifests as a visible left/right
    // shimmer.
    //
    // No contentAlignment on this Box — the inner positioning Box
    // handles its own placement via `align(TopCenter)` + a captured
    // top-y offset (see below).
    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { ev -> handleKey(ev, onNavigate, onEsc, onShortcut) }
            // Real pointer-move events flip the controller's stationary-mouse
            // gate. Without this, hover-Enter events fired purely because
            // the panel just appeared under a stationary mouse would yank
            // the keyboard-selected default cursor away.
            .onPointerEvent(PointerEventType.Move) { onPointerMoved() },
    ) {
        // Outer LookaheadScope: gives the wrapper Box's `Modifier.layout`
        // access to `IntrinsicMeasureScope.isLookingAhead`, which is how
        // it distinguishes the once-per-content-change *target* size
        // (reported to Swift via onPanelSize) from the per-frame
        // *animated* size from animateContentSize on the visible Box
        // inside SwitcherPanel. Cell-level animateBounds uses a
        // *different*, inner LookaheadScope wrapping FlowRow — see
        // SwitcherPanel.
        LookaheadScope {
            // Captured at the first lookahead measurement, this Y is
            // where the panel's top edge sits in scene coordinates for
            // the rest of the session. It is set so the panel is
            // initially centered vertically on the captured screen
            // (= the user-visible "session start: panel centered"
            // behaviour), but stays *fixed* across mid-session
            // content-size changes — the panel grows downward and
            // shrinks upward toward this anchor, never re-centering.
            // Compose's `Modifier.offset { … }` re-reads this state
            // during placement only, so updating the value doesn't
            // invalidate composition; it just re-places the wrapper.
            var capturedTopY by remember { mutableStateOf<Int?>(null) }
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(0, capturedTopY ?: 0) }
                    .layout { measurable, constraints ->
                        // Resolve user's max-width setting against the
                        // current scene width (= screen width since
                        // iter48). Pre-iter48 the cap was effectively
                        // applied via the NSPanel size, which bounded
                        // FlowRow's parent constraint; now that the
                        // Compose host spans the full screen, we have
                        // to enforce the cap here on the wrapper.
                        val sceneWidth = constraints.maxWidth
                        val sceneHeight = constraints.maxHeight
                        val capPx = if (switcherSettings.maxWidthMode == MaxSizeMode.Percent) {
                            (sceneWidth * switcherSettings.maxWidthPercent).toInt()
                        } else {
                            switcherSettings.maxWidthDp.toFloat().dp.toPx().toInt()
                        }
                        val effectiveMaxWidth = capPx.coerceIn(0, sceneWidth)
                        // Override parent maxHeight so FlowRow can wrap
                        // to extra rows without being clamped (same as
                        // iter45). MaxWidth is the user-configured cap,
                        // so FlowRow wraps within the user's preferred
                        // width and onPanelSize reports the cap-respecting
                        // measured size to Swift.
                        val placeable = measurable.measure(
                            constraints.copy(
                                maxWidth = effectiveMaxWidth,
                                maxHeight = Constraints.Infinity,
                            )
                        )
                        // Only the *target* (lookahead) size goes to
                        // Swift; per-frame animated sizes from
                        // animateContentSize on the visible Box stay
                        // local. On the very first lookahead, capture
                        // the centered top-y so subsequent content-size
                        // changes anchor to the same scene Y.
                        if (isLookingAhead) {
                            if (capturedTopY == null) {
                                capturedTopY = ((sceneHeight - placeable.height) / 2)
                                    .coerceAtLeast(0)
                            }
                            onPanelSize(
                                placeable.width.toDp().value,
                                placeable.height.toDp().value,
                            )
                        }
                        layout(placeable.width, placeable.height) {
                            placeable.place(0, 0)
                        }
                    }
            ) {
                SwitcherPanel(ui, iconsByPid, onPointAt, onCommit)
            }
        }
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
 *  cmd+Q / cmd+W / cmd+M / cmd+H / cmd+F (the [SwitcherAction] bindings)
 *  are intentionally absent here — they're intercepted earlier on the
 *  Swift side via `SwitcherOverlayWindow.performKeyEquivalent(with:)`,
 *  which dispatches to `controller.onAction`. Doing it Swift-side is
 *  load-bearing: those cmd-key combos hit NSApp.mainMenu's
 *  performKeyEquivalent path BEFORE the keyDown reaches Compose, and
 *  the main menu would happily terminate KAltSwitch on cmd+Q before
 *  any Compose handler could intercept. The window-level override
 *  pre-empts the main menu. */
private fun handleKey(
    ev: KeyEvent,
    onNavigate: (SwitcherEvent) -> Unit,
    onEsc: () -> Unit,
    onShortcut: (SwitcherEntry) -> Unit,
): Boolean {
    if (ev.type != KeyEventType.KeyDown) return false
    return when (ev.key) {
        Key.Escape -> { onEsc(); true }
        Key.DirectionRight -> { onNavigate(SwitcherEvent.NextApp); true }
        Key.DirectionLeft -> { onNavigate(SwitcherEvent.PrevApp); true }
        Key.DirectionDown -> { onNavigate(SwitcherEvent.NextWindow); true }
        Key.DirectionUp -> { onNavigate(SwitcherEvent.PrevWindow); true }
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
 * a partially-demoted app. A faint *black* tint over the panel's
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
) {
    val state = ui.state
    val entries = state.snapshot.all
    if (entries.isEmpty()) return

    val withWindowsCount = state.snapshot.withWindows.size

    // The lookahead-driven onPanelSize report and the unbounded-maxHeight
    // override now live on the wrapper Box in SwitcherOverlay (so the
    // captured top-y centring math has access to the pre-animation
    // target size). Here we keep just the *visual* backdrop:
    // animateContentSize gives a 200 ms expand/contract on logical
    // size changes; clip + background + border draw the rounded plate.
    Box(
        Modifier
            .animateContentSize(animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing))
            .clip(RoundedCornerShape(16.dp))
            // Opaque dark plate. Originally a low-alpha tint over an
            // NSVisualEffectView blur; the blur was removed because
            // we couldn't keep an `NSVisualEffectView` visually in
            // sync with the dynamic panel-resize animation without
            // rendering bugs (see docs/blur-attempts.md).
            .background(Color(0xFF1B1B1F))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(16.dp))
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
            // Inner LookaheadScope tracks cell bounds *relative to
            // FlowRow*. This is deliberately separate from the outer
            // LookaheadScope (around SwitcherPanel) used by the
            // visible Box's `Modifier.layout` for size reporting. Why
            // two scopes:
            //
            // The outer scope's coordinate system is rooted at the
            // SwitcherOverlay outer Box (fillMaxSize NSPanel). When
            // Compose's two-pass session-start measurement happens —
            // first at the ~90%-screen panel size, then at the
            // shrunken content size after Swift's setContentSize —
            // the visible Box's TopCenter-aligned position within
            // the outer Box shifts horizontally (the centering offset
            // changes as the outer Box shrinks). Cells using the
            // outer scope would inherit that shift and animate
            // sliding right-to-left for ~200 ms on every session
            // open. Pinning cell bounds to *inner* coordinates (= a
            // FlowRow-rooted scope) keeps cell positions invariant
            // across this two-pass dance, so animateBounds only
            // animates the cell motion we actually want — reorders
            // and bucket transitions mid-session.
            LookaheadScope {
                val tileMotion = remember {
                    BoundsTransform { _, _ ->
                        tween(durationMillis = 200, easing = FastOutSlowInEasing)
                    }
                }
                // One movable cell per pid. Without this the cell composable
                // is torn down at the show-bucket call site and rebuilt at
                // the demote-bucket call site (or vice versa) when the
                // filter rules promote/demote an app mid-session — the
                // composable parents are different (outer FlowRow vs the
                // inner FlowRow inside the demote backdrop Box), so a
                // plain `key(pid)` can't preserve identity across that
                // boundary. animateBounds tracks position state on the
                // cell's Modifier.Node, which dies with the cell instance,
                // so a fresh instance has no "from" bounds and the bucket
                // transition lands instantly. movableContentOf moves the
                // entire layout subtree between call sites so the
                // animateBounds node — and its remembered "from" bounds —
                // travels with the cell, producing a real fly animation.
                val cellsByPid = remember { mutableMapOf<Int, @Composable (AppCellArgs) -> Unit>() }
                val livePids = entries.mapTo(HashSet()) { it.app.pid }
                cellsByPid.keys.retainAll(livePids)
                for (pid in livePids) {
                    cellsByPid.getOrPut(pid) {
                        movableContentOf<AppCellArgs> { args ->
                            AppCell(
                                modifier = Modifier.animateBounds(
                                    lookaheadScope = this@LookaheadScope,
                                    boundsTransform = tileMotion,
                                ),
                                name = args.name,
                                pid = args.pid,
                                iconBytes = args.iconBytes,
                                isHidden = args.isHidden,
                                isDemoted = args.isDemoted,
                                windows = args.windows,
                                shownWindowCount = args.shownWindowCount,
                                isSelected = args.isSelected,
                                selectedWindowIndex = args.selectedWindowIndex,
                                onHoverApp = args.onHoverApp,
                                onHoverWindow = args.onHoverWindow,
                                onClickApp = args.onClickApp,
                                onClickWindow = args.onClickWindow,
                            )
                        }
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    showEntries.forEachIndexed { showIndex, entry ->
                        cellsByPid.getValue(entry.app.pid)(
                            cellArgs(
                                entry = entry,
                                iconBytes = iconsByPid[entry.app.pid],
                                isDemoted = false,
                                appIndex = showIndex,
                                cursorAppIndex = state.cursor.appIndex,
                                cursorWindowIndex = state.cursor.windowIndex,
                                onPointAt = onPointAt,
                                onCommit = onCommit,
                            )
                        )
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
                                    cellsByPid.getValue(entry.app.pid)(
                                        cellArgs(
                                            entry = entry,
                                            iconBytes = iconsByPid[entry.app.pid],
                                            isDemoted = true,
                                            appIndex = withWindowsCount + demoteIndex,
                                            cursorAppIndex = state.cursor.appIndex,
                                            cursorWindowIndex = state.cursor.windowIndex,
                                            onPointAt = onPointAt,
                                            onCommit = onCommit,
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
}

private data class AppCellArgs(
    val name: String,
    val pid: Int,
    val iconBytes: ByteArray?,
    val isHidden: Boolean,
    val isDemoted: Boolean,
    val windows: List<com.shish.kaltswitch.model.Window>,
    val shownWindowCount: Int,
    val isSelected: Boolean,
    val selectedWindowIndex: Int,
    val onHoverApp: () -> Unit,
    val onHoverWindow: (Int) -> Unit,
    val onClickApp: () -> Unit,
    val onClickWindow: (Int) -> Unit,
)

private fun cellArgs(
    entry: com.shish.kaltswitch.model.AppEntry,
    iconBytes: ByteArray?,
    isDemoted: Boolean,
    appIndex: Int,
    cursorAppIndex: Int,
    cursorWindowIndex: Int,
    onPointAt: (appIndex: Int, windowIndex: Int?) -> Unit,
    onCommit: () -> Unit,
): AppCellArgs {
    val isSelected = appIndex == cursorAppIndex
    return AppCellArgs(
        name = entry.app.name,
        pid = entry.app.pid,
        iconBytes = iconBytes,
        isHidden = entry.app.isHidden,
        isDemoted = isDemoted,
        windows = entry.windows,
        shownWindowCount = entry.shownWindowCount,
        isSelected = isSelected,
        selectedWindowIndex = if (isSelected) cursorWindowIndex else -1,
        onHoverApp = { onPointAt(appIndex, null) },
        onHoverWindow = { wi -> onPointAt(appIndex, wi) },
        onClickApp = { onPointAt(appIndex, null); onCommit() },
        onClickWindow = { wi -> onPointAt(appIndex, wi); onCommit() },
    )
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
private data class WindowRowArgs(
    val title: String,
    val isActive: Boolean,
    val isMinimized: Boolean,
    val isFullscreen: Boolean,
    val isDemoted: Boolean,
    val onHover: () -> Unit,
    val onClick: () -> Unit,
)

private fun windowRowArgs(
    w: com.shish.kaltswitch.model.Window,
    appName: String,
    fullIndex: Int,
    isAppSelected: Boolean,
    selectedWindowIndex: Int,
    isDemoted: Boolean,
    onHoverWindow: (Int) -> Unit,
    onClickWindow: (Int) -> Unit,
): WindowRowArgs = WindowRowArgs(
    title = effectiveWindowTitle(w.title, appName),
    isActive = isAppSelected && fullIndex == selectedWindowIndex,
    isMinimized = w.isMinimized,
    isFullscreen = w.isFullscreen,
    isDemoted = isDemoted,
    onHover = { onHoverWindow(fullIndex) },
    onClick = { onClickWindow(fullIndex) },
)

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
    // Cell-local LookaheadScope: window-row animateBounds tracks
    // positions in coordinates rooted at THIS WindowList, not the
    // outer FlowRow scope used by AppCell. That isolation is
    // load-bearing — when the cell itself moves between active and
    // demote buckets via its per-pid movableContent (see
    // SwitcherPanel), the cell's contents (= this WindowList) ride
    // along as a unit. If window rows shared the FlowRow-level
    // scope, each row would also see the cell-level move as a
    // bounds delta and fire its own animation on top of the cell's,
    // producing visual stacking.
    LookaheadScope {
        val rowMotion = remember {
            BoundsTransform { _, _ ->
                tween(durationMillis = 200, easing = FastOutSlowInEasing)
            }
        }
        // Per-windowId movable, mirroring the per-pid movable on
        // cells. A window switching between this app's show and
        // demote sub-buckets is the same identity-across-different-
        // parent problem: showWindows lives in the leading Column,
        // demoteWindows lives in the grey-backdrop Column. Without
        // movableContent, a row reclassified mid-session is torn
        // down at the old call site and rebuilt at the new — its
        // animateBounds Modifier.Node dies, so the bucket transition
        // lands instantly. movableContentOf moves the row's layout
        // subtree (and the animateBounds state) between Column
        // parents, animating the move within this cell.
        val rowsByWid = remember { mutableMapOf<WindowId, @Composable (WindowRowArgs) -> Unit>() }
        val liveWids = windows.mapTo(HashSet()) { it.id }
        rowsByWid.keys.retainAll(liveWids)
        for (wid in liveWids) {
            rowsByWid.getOrPut(wid) {
                movableContentOf<WindowRowArgs> { args ->
                    WindowTitleRow(
                        modifier = Modifier.animateBounds(
                            lookaheadScope = this@LookaheadScope,
                            boundsTransform = rowMotion,
                        ),
                        title = args.title,
                        isActive = args.isActive,
                        isMinimized = args.isMinimized,
                        isFullscreen = args.isFullscreen,
                        isDemoted = args.isDemoted,
                        onHover = args.onHover,
                        onClick = args.onClick,
                    )
                }
            }
        }
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (showWindows.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    showWindows.forEachIndexed { i, w ->
                        rowsByWid.getValue(w.id)(
                            windowRowArgs(
                                w = w,
                                appName = appName,
                                fullIndex = i,
                                isAppSelected = isAppSelected,
                                selectedWindowIndex = selectedWindowIndex,
                                isDemoted = false,
                                onHoverWindow = onHoverWindow,
                                onClickWindow = onClickWindow,
                            )
                        )
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
                        rowsByWid.getValue(w.id)(
                            windowRowArgs(
                                w = w,
                                appName = appName,
                                fullIndex = fullIndex,
                                isAppSelected = isAppSelected,
                                selectedWindowIndex = selectedWindowIndex,
                                isDemoted = true,
                                onHoverWindow = onHoverWindow,
                                onClickWindow = onClickWindow,
                            )
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
        // No background — sits directly on the panel plate. The Color(0x22FFFFFF)
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
    modifier: Modifier = Modifier,
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
        modifier
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
