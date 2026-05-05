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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
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
    axTrusted: Boolean,
    onNavigate: (SwitcherEvent) -> Unit,
    onEsc: () -> Unit,
    onShortcut: (SwitcherEntry) -> Unit,
    onPointAt: (appIndex: Int, windowIndex: Int?) -> Unit,
    onPointerMoved: () -> Unit,
    onPanelSize: (Float, Float) -> Unit,
    onCommit: () -> Unit,
    onGrantAxClick: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    var panelBounds by remember { mutableStateOf<IntRect?>(null) }
    val reportPanelBounds: (IntRect?) -> Unit = { panelBounds = it }

    CompositionLocalProvider(
        LocalSelectionExpandDelayMs provides switcherSettings.selectionExpandDelayMs,
        LocalPanelBoundsInWindow provides panelBounds,
        LocalReportPanelBounds provides reportPanelBounds,
    ) {
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
            //
            // Initial pass (outer→inner) so the gate is already open by the
            // time per-cell Move handlers run on the Main pass — that's what
            // lets a wiggle inside the cell the cursor was sitting in at
            // session-open snap selection to that cell. Without Initial,
            // cell Move would call onPointAt before this handler flipped the
            // gate, and the gate-check would early-return.
            .onPointerEvent(PointerEventType.Move, PointerEventPass.Initial) { onPointerMoved() },
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
                SwitcherPanel(ui, iconsByPid, axTrusted, onPointAt, onCommit, onGrantAxClick)
            }
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
private val DockBadgeColor = Color(0xFFFF3B30)          // macOS systemRed (notification badge)

/**
 * Aligns the popup's top-left with the anchor's top-left so the
 * tooltip looks like the title row "broke out" of its cell and
 * extended rightward, rather than a separate label below. Width is
 * the natural width of the popup's content (which is the same Text
 * with no `maxLines` cap), so it extends past the cell on the right
 * by exactly the truncation overflow.
 *
 * Vertical clipping disabled by `PopupProperties(clippingEnabled = false)`
 * at the call site lets the popup extend past the panel's rounded
 * plate horizontally — important when an app's name is *very* long
 * (Slack DM with someone whose chosen name is "Frequently…", VS Code
 * workspace path with several segments, …).
 *
 * Hover bookkeeping (see `HoverableTitle`): even though this popup
 * sits on top of the original anchor, we track hover on *both* the
 * anchor and the popup and union the two — without that, the popup
 * appearing over the anchor steals pointer events, the anchor's
 * `Exit` fires, the popup hides, and we flicker.
 */
/**
 * Builds a `PopupPositionProvider` for the row-expansion popup that
 * clamps the popup's horizontal range to the visible panel plate
 * (`panelBounds`) rather than to the whole Compose-window (= screen).
 * Compose's default popup-position math operates in screen coords, but
 * the user-visible clip happens at the NSPanel boundary, so without
 * this clamp the popup is unclipped in scene coords yet visually
 * cropped at the panel's right edge for cells near that edge.
 *
 * Behaviour:
 *  - default `x = anchorBounds.left` so the popup visually extends
 *    rightward from the row;
 *  - if that would push the popup's right edge past `panelBounds.right`,
 *    shift left until it fits;
 *  - for popups wider than the panel, pin to `panelBounds.left` and let
 *    the right edge clip — at least the title's beginning stays visible.
 *
 * `panelBounds == null` (first frame, before SwitcherPanel's
 * `onGloballyPositioned` has fired) → unclamped behaviour.
 */
private fun overlayTitleTooltipPosition(panelBounds: IntRect?): PopupPositionProvider =
    object : PopupPositionProvider {
        override fun calculatePosition(
            anchorBounds: IntRect,
            windowSize: IntSize,
            layoutDirection: LayoutDirection,
            popupContentSize: IntSize,
        ): IntOffset {
            if (panelBounds == null) {
                return IntOffset(anchorBounds.left, anchorBounds.top)
            }
            val maxAllowedX = (panelBounds.right - popupContentSize.width)
                .coerceAtLeast(panelBounds.left)
            val x = anchorBounds.left
                .coerceAtMost(maxAllowedX)
                .coerceAtLeast(panelBounds.left)
            return IntOffset(x, anchorBounds.top)
        }
    }

/**
 * Compose-local for the user-tunable "selection expand delay" — how long
 * a window row needs to be `isActive` before its truncated title expands
 * into the popup. Lets `WindowTitleRow` read the value without threading
 * `SwitcherSettings` down through every cell / window-list call. Default
 * 250 ms matches `SwitcherSettings.selectionExpandDelayMs`.
 */
private val LocalSelectionExpandDelayMs = compositionLocalOf { 250L }

/**
 * The visible panel plate's bounds in Compose-window (= screen) coords.
 * Captured by `SwitcherPanel`'s outer Box via `onGloballyPositioned` and
 * read by `WindowTitleRow`'s `PopupPositionProvider` to clamp the
 * expanded-row popup so it stays within the panel — without this, the
 * popup is positioned in screen-space (Compose's scene is screen-sized
 * per the iter48 architecture) and gets clipped by the NSPanel's right
 * edge when a cell sits near the panel's right side. `null` until the
 * first layout pass populates it.
 */
private val LocalPanelBoundsInWindow = compositionLocalOf<IntRect?> { null }
private val LocalReportPanelBounds = compositionLocalOf<((IntRect?) -> Unit)?> { null }

/** Expand/collapse tween duration for the selected window-row's row
 *  expansion. Fast enough to feel like a direct response to the cursor
 *  landing on the row. */
private const val RowExpandAnimMs = 150

/**
 * Plain visual of a window row — accent bg / padding / title + status
 * badge — without any pointer handlers or fill modifiers. Both the
 * inline (always-rendered) row and the expansion popup compose this
 * same function with different sizing strategies, so the two visuals
 * are guaranteed to match pixel-for-pixel.
 *
 * `titleMaxLines` / `titleOverflow` switch between the inline form
 * (1 / Ellipsis, with `Modifier.weight(1f)` on the title so it shrinks
 * to fit the cell) and the popup form (1 / Visible, no weight so the
 * title keeps its natural width and the row sizes around it).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun WindowRowVisual(
    modifier: Modifier = Modifier,
    title: String,
    isActive: Boolean,
    isMinimized: Boolean,
    isFullscreen: Boolean,
    isDemoted: Boolean,
    titleMaxLines: Int,
    titleOverflow: TextOverflow,
    onTextLayout: ((androidx.compose.ui.text.TextLayoutResult) -> Unit)? = null,
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
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            title,
            color = fg,
            style = MaterialTheme.typography.bodySmall,
            maxLines = titleMaxLines,
            overflow = titleOverflow,
            onTextLayout = { layout -> onTextLayout?.invoke(layout) },
            // Visible-overflow form (popup) wants natural width so the row
            // sizes around the title; ellipsis form (inline) needs a weight
            // so Compose has a bound to ellipsise against.
            modifier = if (titleOverflow == TextOverflow.Visible) {
                Modifier
            } else {
                Modifier.weight(1f, fill = true)
            },
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

/**
 * Pill-shaped notification badge mirroring the macOS Dock-tile look:
 * red rounded rect with white bold text, sized to its content. Driven by
 * `App.badgeText` which is sourced from the Dock's `AXStatusLabel` per item.
 * The min width keeps single-character badges (`5`, `•`) from collapsing
 * to a sliver, while padding lets longer strings (`12`, `99+`) breathe.
 */
@Composable
private fun DockBadge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .widthIn(min = 18.dp)
            .height(16.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(DockBadgeColor)
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

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

@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun SwitcherPanel(
    ui: SwitcherUiState,
    iconsByPid: Map<Int, ByteArray>,
    axTrusted: Boolean,
    onPointAt: (appIndex: Int, windowIndex: Int?) -> Unit,
    onCommit: () -> Unit,
    onGrantAxClick: () -> Unit,
) {
    val state = ui.state
    val entries = state.snapshot.all
    // Without AX trust the FlowRow may legitimately be empty (NSWorkspace
    // still lists apps but window data is hidden); we still want the panel
    // to render so the banner is visible.
    if (entries.isEmpty() && axTrusted) return

    val withWindowsCount = state.snapshot.withWindows.size
    val reportPanelBounds = LocalReportPanelBounds.current

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
            .padding(horizontal = 16.dp, vertical = 12.dp)
            // Capture the visible plate's bounds (after all sizing
            // modifiers) in window coords so the row-expansion popup can
            // clamp itself to the panel rather than the whole screen.
            // Fires on every layout pass — cheap state update; the
            // PopupPositionProvider captures the value at the moment its
            // remember key changes.
            .onGloballyPositioned { coords ->
                val pos = coords.positionInWindow()
                reportPanelBounds?.invoke(
                    IntRect(
                        left = pos.x.toInt(),
                        top = pos.y.toInt(),
                        right = pos.x.toInt() + coords.size.width,
                        bottom = pos.y.toInt() + coords.size.height,
                    )
                )
            },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!axTrusted) {
                AxPermissionBanner(
                    onGrantClick = onGrantAxClick,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
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
                                badgeText = args.badgeText,
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
}

/**
 * Warning banner rendered above the app row when the process is not AX-trusted.
 * Without AX, we can only enumerate apps via NSWorkspace — windows, focus and
 * raise/close actions are unavailable, so the switcher is degraded. The banner
 * leads with the action (a macOS push-button "Enable Accessibility permissions")
 * followed by a short rationale; clicking the button delegates to
 * `requestAxPermission()` which triggers the system prompt and deep-links to
 * Settings → Privacy → Accessibility.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun AxPermissionBanner(onGrantClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF3A2C0A))
            .border(1.dp, Color(0x66FFC107), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MacButton(
            label = "Enable Accessibility permissions",
            onClick = onGrantClick,
        )
        Text(
            "to allow viewing and managing apps' windows",
            color = Color(0xFFFFD668),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * Compact macOS-style push button: small corner radius, light fill, dark
 * text, subtle border. Sits on the warning-amber banner background; the
 * light fill provides the contrast a real NSButton would get from its
 * window's default surface.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MacButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(Color(0xFFEDEDEF))
            .border(1.dp, Color(0x33000000), RoundedCornerShape(5.dp))
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = Color(0xFF1A1A1A),
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

private data class AppCellArgs(
    val name: String,
    val pid: Int,
    val iconBytes: ByteArray?,
    val isHidden: Boolean,
    val isDemoted: Boolean,
    val badgeText: String?,
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
        badgeText = entry.app.badgeText,
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
    badgeText: String?,
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
            // Move alongside Enter so a wiggle inside the cell the cursor
            // was already sitting in at session-open registers as hover.
            // Enter alone misses that case — the cursor never crossed the
            // cell boundary. onPointAt early-returns when the cursor would
            // be unchanged, so subsequent Moves inside the cell are cheap.
            .onPointerEvent(PointerEventType.Move) { onHoverApp() }
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
        // Two badges can sit at the icon's top-right at once:
        //   - dock badge ("5", "•", "999+") when the app's Dock tile has an
        //     `AXStatusLabel` set — typically incoming-attention counters
        //     from Mail / Slack / iMessage / Telegram. macOS draws this in
        //     the same spot, so users already read it as "attention here";
        //   - the hidden-status pictogram ("−") for cmd+H'd apps, kept on
        //     for parity with the inspector.
        // When both are set we stack them: dock badge overhangs the corner
        // (offset y = -4), hidden badge sits just below (offset y = +14)
        // so neither is occluded.
        Box(contentAlignment = Alignment.TopEnd) {
            AppIconBox(pid = pid, iconBytes = iconBytes, name = name, isDemoted = isDemoted)
            if (badgeText != null) {
                DockBadge(
                    text = badgeText,
                    modifier = Modifier.offset(x = 6.dp, y = (-4).dp),
                )
            }
            if (isHidden) {
                StatusBadge(
                    glyph = "−",
                    background = HiddenBadgeColor,
                    contentColor = Color.White,
                    modifier = Modifier.offset(
                        x = 4.dp,
                        y = if (badgeText != null) 14.dp else (-4).dp,
                    ),
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
            textAlign = TextAlign.Center,
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
    var truncated by remember(title) { mutableStateOf(false) }
    var anchorSizePx by remember { mutableStateOf(IntSize.Zero) }
    var anchorWindowX by remember { mutableStateOf<Int?>(null) }
    var popupWindowX by remember { mutableStateOf<Int?>(null) }
    val expandDelayMs = LocalSelectionExpandDelayMs.current
    val density = LocalDensity.current

    // Mouse-on-overflow gating. Three flags:
    //   * popupOverflowHovered — live: cursor is currently in either
    //     overflow region (left of cell or right of cell within popup).
    //   * overflowSuppress — latched: cursor *did* enter overflow while
    //     the gate was open. Stays latched until the row stops being the
    //     controller's selected row, so the cursor leaving the overflow
    //     during the collapse tween can't immediately re-mount the popup
    //     mid-animation. Resets on `!isActive` and on a fresh inline-row
    //     Enter so mousing back onto the original row re-arms expansion.
    //   * overflowGateOpen — only true once the popup-expand animation
    //     has finished. Layout-driven Enter events fired *during* the
    //     animation (the popup just grew leftward over a neighbouring
    //     cell where the cursor was parked, etc.) don't count — without
    //     this gate, keyboard nav with the cursor parked on any cell that
    //     ends up under the popup's overflow region would immediately
    //     latch overflowSuppress and collapse the popup.
    //
    // Crucially the gate is *only* "cursor in overflow", not "cursor in
    // original bounds". Keyboard navigation moves `isActive` without any
    // mouse events, and a positive-signal gate (require hover to expand)
    // would block keyboard-driven expansion. Negative-signal gating lets
    // keyboard nav expand by default and only suppresses when the user
    // moves the mouse into the expanded region post-animation.
    var popupOverflowHovered by remember { mutableStateOf(false) }
    var overflowSuppress by remember { mutableStateOf(false) }
    var overflowGateOpen by remember { mutableStateOf(false) }

    LaunchedEffect(isActive) {
        if (!isActive) {
            popupOverflowHovered = false
            overflowSuppress = false
        }
    }
    LaunchedEffect(popupOverflowHovered, overflowGateOpen) {
        if (popupOverflowHovered && overflowGateOpen) overflowSuppress = true
    }

    val desired = isActive && truncated && !overflowSuppress

    // Two-stage state machine so the popup mounts at the *collapsed* size,
    // gets a frame, then animates open:
    //   desired ──delay──▶ mounted=true ──one-frame──▶ expanded=true
    //   ¬desired ─immediate▶ expanded=false ──animTween──▶ mounted=false
    var popupMounted by remember { mutableStateOf(false) }
    var popupExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(desired, expandDelayMs) {
        if (desired) {
            delay(expandDelayMs)
            popupMounted = true
            overflowGateOpen = false
            // Yield one frame so the popup composes once at anchor width
            // before we ask animateContentSize to grow it. Without this
            // the very first composition would already be at natural
            // width and the expand animation would skip.
            delay(16L)
            popupExpanded = true
            // Wait for the expand tween to settle so layout-driven Enter
            // events on the leftBox / rightBox during animation are
            // ignored (cursor parked on a neighbour cell shouldn't latch
            // overflowSuppress just because the popup grew over it).
            delay(RowExpandAnimMs.toLong())
            overflowGateOpen = true
        } else {
            // No collapse animation — when the controller cursor moves
            // to a different row we want the previous row's popup gone
            // immediately rather than playing a 150 ms shrink while the
            // user is already focused on the new selection. Animating
            // the close also caused a small jitter when the popup had
            // been shifted (the position provider re-runs each frame
            // during the shrink, so the popup slid right while shrinking).
            popupExpanded = false
            popupMounted = false
            overflowGateOpen = false
        }
    }

    Box(modifier.onSizeChanged { anchorSizePx = it }) {
        WindowRowVisual(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coords ->
                    anchorWindowX = coords.positionInWindow().x.toInt()
                }
                // Per-row hover/click handlers run BEFORE the parent cell's,
                // so pointing at a specific window row updates windowIndex
                // instead of resetting to the cell-level default. Re-arm
                // expansion on every fresh Enter so mousing back onto the
                // inline row after a previous overflow-suppress dismissal
                // pops the popup again.
                .onPointerEvent(PointerEventType.Enter) {
                    overflowSuppress = false
                    onHover()
                }
                // Move alongside Enter for the stationary-cursor-at-open
                // case (see AppCell). overflowSuppress stays Enter-only —
                // re-arming on every Move would defeat the dismiss latch.
                .onPointerEvent(PointerEventType.Move) { onHover() }
                .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
            title = title,
            isActive = isActive,
            isMinimized = isMinimized,
            isFullscreen = isFullscreen,
            isDemoted = isDemoted,
            titleMaxLines = 1,
            titleOverflow = TextOverflow.Ellipsis,
            onTextLayout = { layout -> truncated = layout.hasVisualOverflow },
        )
        if (popupMounted && anchorSizePx != IntSize.Zero) {
            val anchorWidthDp = with(density) { anchorSizePx.width.toDp() }
            val panelBounds = LocalPanelBoundsInWindow.current
            val popupPositionProvider = remember(panelBounds) {
                overlayTitleTooltipPosition(panelBounds)
            }
            Popup(
                popupPositionProvider = popupPositionProvider,
                properties = PopupProperties(focusable = false, clippingEnabled = false),
            ) {
                // The width modifier flips between `width(anchorWidthDp)`
                // (collapsed) and `wrapContentWidth(unbounded)` (expanded),
                // so animateContentSize sees a real size delta in both
                // directions and tweens the row's width — including the
                // accent bg, since that's drawn at the row's measured
                // width. clipToBounds hides the still-overflowing title
                // glyphs while the row is animating from anchor → natural.
                //
                // Right-edge clipping mitigation: the position provider
                // (`overlayTitleTooltipPosition`) clamps the popup to
                // `panelBounds` — for cells near the panel's right side
                // the popup shifts left so it stops growing into the
                // clipped strip past the panel's plate.
                Box(
                    Modifier
                        .animateContentSize(
                            animationSpec = tween(
                                durationMillis = RowExpandAnimMs,
                                easing = FastOutSlowInEasing,
                            ),
                        )
                        .then(
                            if (popupExpanded) {
                                Modifier.wrapContentWidth(
                                    align = Alignment.Start,
                                    unbounded = true,
                                )
                            } else {
                                Modifier.width(anchorWidthDp)
                            },
                        )
                        .clipToBounds()
                        .onGloballyPositioned { coords ->
                            popupWindowX = coords.positionInWindow().x.toInt()
                        },
                ) {
                    WindowRowVisual(
                        // Click handler so taps on the original-bounds
                        // region (which sit beneath the Spacer in the
                        // overlay below — Spacer has no handlers, so
                        // events pass through here) and on the overflow
                        // region (handled by the overlay's Box) both
                        // commit.
                        modifier = Modifier
                            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
                        title = title,
                        isActive = isActive,
                        isMinimized = isMinimized,
                        isFullscreen = isFullscreen,
                        isDemoted = isDemoted,
                        titleMaxLines = 1,
                        titleOverflow = TextOverflow.Visible,
                    )
                    // Three-zone overlay sized to the popup via
                    // `matchParentSize`:
                    //   * leading Box covers the *left* overflow — the
                    //     region the popup grew leftward into when the
                    //     position provider shifted to keep the right
                    //     edge inside the panel. Width = `shift` (the
                    //     gap between the popup's left edge and the
                    //     original cell's left edge in window coords).
                    //     Zero when no shift occurred, so it's a harmless
                    //     no-op for cells that have rightward room.
                    //   * middle Spacer covers the original anchor
                    //     bounds — no pointer modifiers, so hover here
                    //     leaves popupOverflowHovered alone (popup
                    //     stays) and clicks fall through to the
                    //     WindowRowVisual beneath.
                    //   * trailing Box covers the *right* overflow with
                    //     `weight(1f)` so it absorbs whatever's left of
                    //     the popup width.
                    // Both overflow Boxes flip popupOverflowHovered,
                    // which (post overflowGateOpen) latches
                    // overflowSuppress and collapses the popup.
                    val shiftPx = if (anchorWindowX != null && popupWindowX != null) {
                        (anchorWindowX!! - popupWindowX!!).coerceAtLeast(0)
                    } else 0
                    val shiftDp = with(density) { shiftPx.toDp() }
                    Row(Modifier.matchParentSize()) {
                        Box(
                            Modifier
                                .width(shiftDp)
                                .fillMaxHeight()
                                .onPointerEvent(PointerEventType.Enter) {
                                    popupOverflowHovered = true
                                }
                                .onPointerEvent(PointerEventType.Exit) {
                                    popupOverflowHovered = false
                                }
                                .pointerInput(Unit) {
                                    detectTapGestures(onTap = { onClick() })
                                },
                        )
                        Spacer(Modifier.width(anchorWidthDp))
                        Box(
                            Modifier
                                .weight(1f, fill = true)
                                .fillMaxHeight()
                                .onPointerEvent(PointerEventType.Enter) {
                                    popupOverflowHovered = true
                                }
                                .onPointerEvent(PointerEventType.Exit) {
                                    popupOverflowHovered = false
                                }
                                .pointerInput(Unit) {
                                    detectTapGestures(onTap = { onClick() })
                                },
                        )
                    }
                }
            }
        }
    }
}
