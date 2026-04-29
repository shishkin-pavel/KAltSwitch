package com.shish.kaltswitch.switcher

import com.shish.kaltswitch.model.SwitcherCursor
import com.shish.kaltswitch.model.SwitcherEntry
import com.shish.kaltswitch.model.SwitcherEvent
import com.shish.kaltswitch.model.SwitcherState
import com.shish.kaltswitch.model.WindowId
import com.shish.kaltswitch.model.apply
import com.shish.kaltswitch.model.filteredSwitcherSnapshot
import com.shish.kaltswitch.model.openSwitcher
import com.shish.kaltswitch.store.WorldStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Live UI state of the switcher session. `null` from [SwitcherController.ui] means the
 * switcher is closed. `visible == false` means the modifier is held but `showDelay`
 * has not elapsed yet — internal pending state, the panel does not render.
 */
data class SwitcherUiState(
    val state: SwitcherState,
    val visible: Boolean,
    val previewedWindowId: WindowId?,
)

/**
 * Owns the per-session lifecycle of the switcher: open/navigate/preview/commit/cancel,
 * driven by raw input events from the platform layer (`onShortcut`, `onModifierReleased`,
 * `onEsc`, `onNavigate`).
 *
 * Pure logic — no Compose, no AppKit. Time-based behaviour (`showDelay`, `previewDelay`)
 * uses kotlinx-coroutines, which makes this fully testable from `commonTest` via
 * `runTest` + `TestScope`.
 *
 * The platform layer wires up:
 *   - [onRaiseWindow] — preview-raise without polluting activation history
 *     (e.g. `AXUIElementPerformAction(window, kAXRaiseAction)`);
 *   - [onCommitActivation] — final activation on cmd-release
 *     (`NSRunningApplication.activate` + `kAXMain` + `kAXRaise`).
 *
 * Both callbacks are nullable so tests can leave them unset.
 */
class SwitcherController(
    private val store: WorldStore,
    private val scope: CoroutineScope,
) {
    // Live-read settings from the store so changes in the inspector's
    // Settings panel take effect on the next session start without
    // restarting the controller. The current values are sampled at
    // session-start (showDelay) and at every cursor change (previewDelay /
    // previewEnabled), so an in-flight session keeps its initial cadence.
    private val showDelayMs: Long get() = store.switcherSettings.value.showDelayMs
    private val previewDelayMs: Long get() = store.switcherSettings.value.previewDelayMs

    /** Off for MVP by default — see "Preview-raise on selection" in
     *  docs/window-state-attributes.md §8. The full code path stays compiled
     *  and tested; the inspector exposes a toggle so users can opt in. */
    private val previewEnabled: Boolean get() = store.switcherSettings.value.previewEnabled
    var onRaiseWindow: ((pid: Int, windowId: WindowId) -> Unit)? = null
    var onCommitActivation: ((pid: Int, windowId: WindowId?) -> Unit)? = null

    private val _ui = MutableStateFlow<SwitcherUiState?>(null)
    val ui: StateFlow<SwitcherUiState?> = _ui.asStateFlow()

    private var showJob: Job? = null
    private var previewJob: Job? = null

    /**
     * Auto-advance ("running") job. Started on the first hotkey press and
     * scheduled to fire `repeatInitialDelayMs` later, then advance every
     * `repeatIntervalMs` until the user releases the alt-key (tab/grave) or
     * the session ends. Cancelled on each fresh press so a quick re-tap
     * doesn't immediately enter run-mode mid-cooldown.
     */
    private var pressJob: Job? = null

    /**
     * Which (entry, reverse) combo is currently being held, or `null` if
     * none. We can't tell from a Carbon `RegisterEventHotKey` fire alone
     * whether it's a fresh press or OS keyboard auto-repeat — both show up
     * identically. The platform layer informs us via [onShortcutKeyReleased]
     * when keyUp fires on the panel (NSPanel.sendEvent — not gated by Compose
     * focus or AX permission).
     *
     * Tracking the combo (rather than a plain bool) so adding shift mid-hold
     * (cmd+tab → cmd+shift+tab) or switching key (cmd+tab → cmd+\` while tab
     * still held) registers as a fresh press, not as auto-repeat of the
     * previous combo.
     */
    private var heldShortcut: Pair<SwitcherEntry, Boolean>? = null

    /**
     * Hotkey press from the platform layer.
     *
     * `reverse == true` means the shift-modified variant (cmd+shift+tab,
     * cmd+shift+`). From a closed state, the cursor lands on the *last*
     * element in recency (skip current going backwards):
     *  - `cmd+shift+tab` → app[size-1] (least-recently-used app).
     *  - `cmd+shift+\``  → window[size-1] of current app.
     * While the session is already open, the shift variant just navigates
     * one step backwards.
     *
     * Each fresh press also arms the auto-advance press job (see
     * [heldShortcut]). Subsequent fires from OS keyboard auto-repeat are
     * ignored — they all look identical to fresh presses at the Carbon API
     * level, so we differentiate via the keyUp signal from the panel.
     */
    fun onShortcut(entry: SwitcherEntry, reverse: Boolean = false) {
        val combo = entry to reverse
        if (heldShortcut == combo) {
            println("[ctl] shortcut entry=$entry reverse=$reverse SKIPPED (auto-repeat)")
            return  // OS auto-repeat — pressJob drives navigation.
        }
        heldShortcut = combo

        val current = _ui.value
        if (current == null) {
            openSession(entry)
            if (reverse) {
                placeOnLastInRecency(entry)
            }
        } else {
            val event = if (reverse) reverseEventFor(entry) else forwardEventFor(entry)
            navigate(event)
        }
        val cursor = _ui.value?.state?.cursor
        println("[ctl] shortcut entry=$entry reverse=$reverse cursor=$cursor")
        startPressJob(entry, reverse)
    }

    /**
     * Override the just-opened session's default cursor to point at the
     * *last* element in recency. For `App` entry that means the
     * least-recently-used app (`app[size-1]`); for `Window` entry, the
     * least-recently-used window of the current app.
     */
    private fun placeOnLastInRecency(entry: SwitcherEntry) {
        val cur = _ui.value ?: return
        val items = cur.state.snapshot.all
        if (items.isEmpty()) return
        val lastCursor = when (entry) {
            SwitcherEntry.App -> SwitcherCursor(
                appIndex = items.lastIndex,
                windowIndex = 0,
            )
            SwitcherEntry.Window -> {
                val windows = items[0].windows
                SwitcherCursor(
                    appIndex = 0,
                    windowIndex = (windows.size - 1).coerceAtLeast(0),
                )
            }
        }
        _ui.value = cur.copy(state = cur.state.copy(cursor = lastCursor), previewedWindowId = null)
    }

    /**
     * Platform-layer signal that the alt-key (tab/grave) was released while
     * the modifier (cmd) is still held. Stops auto-advancing; the session
     * stays open so the user can keep navigating with mouse / arrows / re-press
     * before committing on cmd-release.
     */
    fun onShortcutKeyReleased() {
        val wasRunning = pressJob != null
        heldShortcut = null
        pressJob?.cancel(); pressJob = null
        println("[ctl] shortcut-key released wasRunning=$wasRunning cursor=${_ui.value?.state?.cursor}")
    }

    private fun startPressJob(entry: SwitcherEntry, reverse: Boolean) {
        val event = if (reverse) reverseEventFor(entry) else forwardEventFor(entry)
        pressJob?.cancel()
        pressJob = scope.launch {
            // Snapshot settings at job start; mid-flight changes apply on the
            // next press cycle, not within this one.
            val initialDelay = store.switcherSettings.value.repeatInitialDelayMs
            val interval = store.switcherSettings.value.repeatIntervalMs
            delay(initialDelay)
            while (true) {
                navigate(event)
                println("[ctl] press-tick event=$event cursor=${_ui.value?.state?.cursor}")
                delay(interval)
            }
        }
    }

    private fun forwardEventFor(entry: SwitcherEntry): SwitcherEvent = when (entry) {
        SwitcherEntry.App -> SwitcherEvent.NextApp
        SwitcherEntry.Window -> SwitcherEvent.NextWindow
    }

    private fun reverseEventFor(entry: SwitcherEntry): SwitcherEvent = when (entry) {
        SwitcherEntry.App -> SwitcherEvent.PrevApp
        SwitcherEntry.Window -> SwitcherEvent.PrevWindow
    }

    fun onNavigate(event: SwitcherEvent) {
        if (_ui.value == null) return
        navigate(event)
    }

    /**
     * Mouse-driven cursor move from the overlay UI. `windowIndex == null` means
     * "the user is pointing at the app cell as a whole" — keep the existing
     * window index if we're still on the same app, otherwise reset to 0
     * (newest window of the newly-pointed app), matching the keyboard NextApp
     * behaviour.
     *
     * Out-of-range coordinates are silently ignored — easier than asking
     * callers to validate against a snapshot they don't own.
     */
    fun onPointAt(appIndex: Int, windowIndex: Int? = null) {
        val cur = _ui.value ?: return
        val items = cur.state.snapshot.all
        if (appIndex !in items.indices) return
        val windows = items[appIndex].windows
        val resolvedWindowIndex = when {
            windows.isEmpty() -> 0
            windowIndex != null -> windowIndex.coerceIn(0, windows.size - 1)
            appIndex == cur.state.cursor.appIndex -> cur.state.cursor.windowIndex.coerceIn(0, windows.size - 1)
            else -> 0
        }
        val nextCursor = SwitcherCursor(appIndex, resolvedWindowIndex)
        if (nextCursor == cur.state.cursor) return
        _ui.value = cur.copy(state = cur.state.copy(cursor = nextCursor), previewedWindowId = null)
        schedulePreview()
    }

    /** Click-to-commit from the overlay UI. Same end state as cmd-release. */
    fun onCommit() {
        val cur = _ui.value ?: return
        commit(cur)
    }

    fun onModifierReleased() {
        val cur = _ui.value ?: return
        commit(cur)
    }

    fun onEsc() {
        if (_ui.value == null) return
        cancel()
    }

    private fun openSession(entry: SwitcherEntry) {
        val world = store.state.value
        val filters = store.filters.value
        val snapshot = world.filteredSwitcherSnapshot(filters)
        val state = openSwitcher(snapshot, entry)
        val appOrder = snapshot.all.map { it.app.pid to it.app.name }
        println("[ctl] openSession entry=$entry defaultCursor=${state.cursor} apps=$appOrder")
        _ui.value = SwitcherUiState(state, visible = false, previewedWindowId = null)
        store.setSwitcherActive(true)

        showJob?.cancel()
        showJob = scope.launch {
            delay(showDelayMs)
            val cur = _ui.value ?: return@launch
            _ui.value = cur.copy(visible = true)
            schedulePreview()
        }
    }

    private fun navigate(event: SwitcherEvent) {
        val cur = _ui.value ?: return
        val nextState = cur.state.apply(event)
        if (nextState == cur.state) return
        // Cursor moved → previous preview-raise is no longer relevant.
        _ui.value = cur.copy(state = nextState, previewedWindowId = null)
        schedulePreview()
    }

    private fun schedulePreview() {
        previewJob?.cancel()
        previewJob = null
        if (!previewEnabled) return
        previewJob = scope.launch {
            delay(previewDelayMs)
            val cur = _ui.value ?: return@launch
            if (!cur.visible) return@launch  // still inside showDelay
            val sel = cur.state.selectedWindow ?: return@launch
            onRaiseWindow?.invoke(sel.pid, sel.id)
            _ui.value = cur.copy(previewedWindowId = sel.id)
        }
    }

    private fun commit(cur: SwitcherUiState) {
        val app = cur.state.selectedAppEntry?.app
        val window = cur.state.selectedWindow
        println("[ctl] commit cursor=${cur.state.cursor} app=${app?.pid}/${app?.name} window=${window?.id}/${window?.title}")
        closeSession()
        if (app != null) {
            // Record the user's intent into the activation log *synchronously*
            // instead of waiting for the AX/NSWorkspace echo. macOS doesn't
            // always fire `kAXFocusedWindowChanged` after a CGS-direct focus
            // call (esp. for window-only switches inside one app), so the
            // echo isn't a reliable trigger. Recording here guarantees the
            // inspector's row order moves on every commit; later AX echoes
            // (when they do arrive) dedupe naturally — `appOrder()` walks
            // newest-first and emits each pid once.
            store.recordActivation(app.pid, window?.id)
            onCommitActivation?.invoke(app.pid, window?.id)
        }
    }

    private fun cancel() {
        closeSession()
    }

    /** Tear down everything that constitutes a "live session": UI state, pending
     *  timers, and the [WorldStore.switcherActive] gate. After this returns,
     *  AX/NSWorkspace activation events flow into the store again — which is
     *  exactly what we want for the commit's own echo. */
    private fun closeSession() {
        showJob?.cancel(); showJob = null
        previewJob?.cancel(); previewJob = null
        pressJob?.cancel(); pressJob = null
        // Clear the held combo so the next cmd+tab is recognised as a fresh
        // press; otherwise a stale value would mask the first onShortcut
        // after commit/cancel and the user would see no advance.
        heldShortcut = null
        _ui.value = null
        store.setSwitcherActive(false)
    }
}
