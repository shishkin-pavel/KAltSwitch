package com.shish.kaltswitch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
@Composable
fun SwitcherOverlay(
    ui: SwitcherUiState,
    iconsByPid: Map<Int, ByteArray>,
    onNavigate: (SwitcherEvent) -> Unit,
    onEsc: () -> Unit,
    onShortcut: (SwitcherEntry) -> Unit,
    onPointAt: (appIndex: Int, windowIndex: Int?) -> Unit,
    onCommit: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { ev -> handleKey(ev, onNavigate, onEsc, onShortcut) },
        contentAlignment = Alignment.Center,
    ) {
        SwitcherPanel(ui, iconsByPid, onPointAt, onCommit)
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
 *  for users who want to step around without re-tapping the modifier-key. */
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
                .background(Color(0xCC1B1B1F))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                entries.forEachIndexed { appIndex, entry ->
                    if (appIndex == withWindowsCount && withWindowsCount > 0 && withWindowsCount < entries.size) {
                        Spacer(
                            Modifier
                                .width(1.dp)
                                .height(96.dp)
                                .background(Color(0x33FFFFFF))
                        )
                    }
                    AppCell(
                        name = entry.app.name,
                        pid = entry.app.pid,
                        iconBytes = iconsByPid[entry.app.pid],
                        windows = entry.windows,
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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun AppCell(
    name: String,
    pid: Int,
    iconBytes: ByteArray?,
    windows: List<com.shish.kaltswitch.model.Window>,
    isSelected: Boolean,
    selectedWindowIndex: Int,
    onHoverApp: () -> Unit,
    onHoverWindow: (Int) -> Unit,
    onClickApp: () -> Unit,
    onClickWindow: (Int) -> Unit,
) {
    val borderColor = if (isSelected) Color(0xFFFFC107) else Color.Transparent
    val nameColor = if (isSelected) Color.White else Color(0xFFCCCCCC)
    // Hover/click handlers stay on the cell-level Column so the entire app
    // cell (icon + name + window list) is one click target. Per-window rows
    // override the windowIndex via their own handlers.
    Column(
        Modifier
            .widthIn(min = 80.dp, max = 120.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(2.dp, borderColor, RoundedCornerShape(10.dp))
            .onPointerEvent(PointerEventType.Enter) { onHoverApp() }
            .pointerInput(Unit) { detectTapGestures(onTap = { onClickApp() }) }
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AppIconBox(pid = pid, iconBytes = iconBytes, name = name)
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
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                windows.forEachIndexed { i, w ->
                    WindowTitleRow(
                        title = w.title.ifBlank { "(untitled)" },
                        isActive = isSelected && i == selectedWindowIndex,
                        onHover = { onHoverWindow(i) },
                        onClick = { onClickWindow(i) },
                    )
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
    onHover: () -> Unit,
    onClick: () -> Unit,
) {
    val bg = if (isActive) Color(0xFFFFC107) else Color.Transparent
    val fg = if (isActive) Color.Black else Color(0xFFBBBBBB)
    Box(
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
    ) {
        Text(
            title,
            color = fg,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
