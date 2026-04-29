package com.shish.kaltswitch.switcher

import com.shish.kaltswitch.model.SwitcherCursor
import com.shish.kaltswitch.model.SwitcherEntry
import com.shish.kaltswitch.model.SwitcherEvent
import com.shish.kaltswitch.model.SwitcherSnapshot
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
    private val showDelayMs: Long = 20L,
    private val previewDelayMs: Long = 250L,
    /** Off for MVP — see "Preview-raise on selection" in
     *  docs/window-state-attributes.md §8. The full code path stays compiled
     *  and tested so we can re-enable post-MVP without resurrecting it. */
    private val previewEnabled: Boolean = false,
) {
    var onRaiseWindow: ((pid: Int, windowId: WindowId) -> Unit)? = null
    var onCommitActivation: ((pid: Int, windowId: WindowId?) -> Unit)? = null

    private val _ui = MutableStateFlow<SwitcherUiState?>(null)
    val ui: StateFlow<SwitcherUiState?> = _ui.asStateFlow()

    private var showJob: Job? = null
    private var previewJob: Job? = null

    /**
     * Hotkey press from the platform layer.
     *
     * `reverse == true` means the shift-modified variant (cmd+shift+tab,
     * cmd+shift+`). From a closed state, opens the session with the default
     * cursor and immediately steps backwards once. While the session is open,
     * advances in the reverse direction.
     */
    fun onShortcut(entry: SwitcherEntry, reverse: Boolean = false) {
        val current = _ui.value
        if (current == null) {
            openSession(entry)
            if (reverse) {
                navigate(reverseEventFor(entry))
            }
        } else {
            val event = if (reverse) reverseEventFor(entry) else forwardEventFor(entry)
            navigate(event)
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
        closeSession()
        val app = cur.state.selectedAppEntry?.app
        val window = cur.state.selectedWindow
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
        _ui.value = null
        store.setSwitcherActive(false)
    }
}
