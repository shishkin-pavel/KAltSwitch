package com.shish.kaltswitch.switcher

import com.shish.kaltswitch.log.log
import com.shish.kaltswitch.model.ActionScope
import com.shish.kaltswitch.model.AppEntry
import com.shish.kaltswitch.model.NavScope
import com.shish.kaltswitch.model.Pid
import com.shish.kaltswitch.model.SwitcherAction
import com.shish.kaltswitch.model.SwitcherCursor
import com.shish.kaltswitch.model.SwitcherEntry
import com.shish.kaltswitch.model.SwitcherEvent
import com.shish.kaltswitch.model.SwitcherSnapshot
import com.shish.kaltswitch.model.SwitcherState
import com.shish.kaltswitch.model.WindowId
import com.shish.kaltswitch.model.WindowTags
import com.shish.kaltswitch.model.apply
import com.shish.kaltswitch.model.filteredSwitcherSnapshot
import com.shish.kaltswitch.model.mostRecentNavigableInScope
import com.shish.kaltswitch.model.openSwitcher
import com.shish.kaltswitch.model.refreshedWith
import com.shish.kaltswitch.model.scopedApps
import com.shish.kaltswitch.model.scopedNavigable
import com.shish.kaltswitch.model.withCursor
import com.shish.kaltswitch.store.WorldStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
    var onRaiseWindow: ((pid: Pid, windowId: WindowId) -> Unit)? = null
    var onCommitActivation: ((pid: Pid, windowId: WindowId?) -> Unit)? = null

    /**
     * Wired by the platform layer to side-effect APIs:
     *   - [SwitcherAction.QuitApp] / [SwitcherAction.ToggleHide] — fire with
     *     `windowId == null` (app-level).
     *   - [SwitcherAction.CloseWindow] / [SwitcherAction.ToggleMinimize] /
     *     [SwitcherAction.ToggleFullscreen] — fire with the selected window's
     *     id; never fires for a windowless cell.
     *
     * The world will mutate as a result (e.g. window closes, app terminates),
     * the live-snapshot collector picks it up, and the cursor moves to a
     * neighbour automatically — see [SwitcherState.refreshedWith].
     */
    var onPerformAction: ((action: SwitcherAction, pid: Pid, windowId: WindowId?) -> Unit)? = null

    /**
     * Wired by the platform layer to a "raise this window in z-order
     * without changing focus" call (`kAXRaiseAction` is the canonical
     * choice). Fires on session **cancel only** (Esc / equivalent), with
     * whatever the store currently considers the focused window — that's
     * `(activeAppPid.value, activeWindowId.value)`, both kept in sync by
     * Swift's syncActiveStateFromSystem.
     *
     * The need: cmd+M restore inside the switcher unminimises a window in
     * a background app. macOS pops the now-restored window to the top of
     * the global z-order even though the user's actual focus stays where
     * it was (the panel is the key window; the underlying focused window
     * doesn't change). Once the switcher closes, focus reverts to the
     * pre-session window, but visually that window is now obscured by
     * the de-minimised one. Raising the focused window on cancel
     * realigns z-order with focus.
     *
     * Not fired on commit: the commit path's [onCommitActivation] already
     * does a full focus + raise + activate dance for the picked target.
     */
    var onRaiseFocusedWindow: ((pid: Pid, windowId: WindowId) -> Unit)? = null

    private val _ui = MutableStateFlow<SwitcherUiState?>(null)
    val ui: StateFlow<SwitcherUiState?> = _ui.asStateFlow()

    /**
     * Digit→window "bookmarks" assigned via cmd+ctrl+digit while the
     * switcher is open. Survive across sessions for the process lifetime;
     * pruned automatically whenever the live snapshot loses a tagged
     * window (see [launchSnapshotCollector]). The UI reads this to draw
     * a tag glyph on the left edge of each tagged row.
     */
    private val _tags = MutableStateFlow(WindowTags.Empty)
    val tags: StateFlow<WindowTags> = _tags.asStateFlow()

    private var showJob: Job? = null
    private var previewJob: Job? = null
    /** Long-running collector that keeps the active session's snapshot in
     *  sync with the live world. Started in [openSession], cancelled in
     *  [closeSession]. Each emission goes through [SwitcherState.refreshedWith]
     *  so the cursor identity is preserved when its target survives, or moved
     *  to a deterministic neighbour when it disappears. */
    private var snapshotJob: Job? = null

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
     * Has the user moved the mouse pointer at least once since the current
     * session opened? Until they have, [onPointAt] is ignored — the panel
     * appearing under a stationary mouse generates Compose `Enter` events
     * for whichever cell happens to be under the cursor, and without this
     * gate that hover would yank the keyboard-selected default cursor away
     * (cmd+tab landing on the third app instead of the second, cmd+\` on
     * a different app entirely).
     *
     * Reset to `false` on every [openSession]; flipped to `true` by the
     * platform layer's [onPointerMoved] when a real Move event arrives.
     */
    private var mouseInteracted: Boolean = false

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
    fun onShortcut(entry: SwitcherEntry, reverse: Boolean = false, modifierHeld: Boolean = true) {
        val combo = entry to reverse
        log("[diag-ctl] onShortcut ENTER entry=$entry reverse=$reverse modifierHeld=$modifierHeld currentSession=${_ui.value != null} held=$heldShortcut")
        if (heldShortcut == combo) {
            log("[ctl] shortcut entry=$entry reverse=$reverse SKIPPED (auto-repeat)")
            return  // OS auto-repeat — pressJob drives navigation.
        }
        heldShortcut = combo

        val current = _ui.value
        if (current == null) {
            openSession(entry)
            if (reverse) {
                placeOnLastInRecency(entry)
            }
            // The CGEventTap dispatch that delivers the modifier-release
            // and the Carbon-hotkey dispatch that delivers `onShortcut`
            // race each other onto the main thread. For sub-100 ms
            // cmd-holds the release handler runs *before* this
            // openSession — see the NO-OP branch in [onModifierReleased].
            // The session would then stay visible until the user
            // pressed Esc. Authoritative current-state probe: if cmd
            // is no longer pressed by the time this handler runs, the
            // user already committed to the shortcut's default action
            // (cmd+tab → most recent app, cmd+` → most recent window
            // of current app, shift variants in reverse). Auto-commit
            // on the default cursor that [openSession] just landed on.
            if (!modifierHeld) {
                log("[ctl] modifier already released at openSession — auto-commit on default cursor")
                _ui.value?.let { commit(it) }
                return
            }
        } else {
            // App-only mode: cmd+` can't step windows without AX. Instead it
            // commits the selected app and ends the session — the platform
            // layer then restores the native cmd+`, so the user's next press
            // (cmd still held) cycles the just-activated app's windows via
            // macOS. cmd+tab (App entry) still advances apps. See no-AX spec.
            if (!store.axTrusted.value && entry == SwitcherEntry.Window) {
                log("[ctl] cmd+grave (app-only) → commit selected app")
                _ui.value?.let { commit(it) }
                return
            }
            val event = if (reverse) reverseEventFor(entry) else forwardEventFor(entry)
            // Hot-key path → Shown scope: cmd+tab cycles only through the
            // inspector's `Show` apps, the demoted ones are skipped. Arrow
            // keys (onNavigate) use the All scope by default.
            navigate(event, NavScope.Shown)
        }
        val cursor = _ui.value?.state?.cursor
        log("[ctl] shortcut entry=$entry reverse=$reverse cursor=$cursor")
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
        val snapshot = cur.state.snapshot
        if (snapshot.all.isEmpty()) return
        // Land on the oldest entry within the hot-key path's Show scope.
        // cmd+shift+tab from closed should reach the oldest Show app, not
        // wander into the Demote bucket. Identity-based so a Show app's
        // pinned children (themselves Show) are eligible window-stops too.
        val nextState = when (entry) {
            SwitcherEntry.App -> {
                val app = snapshot.withWindows.lastOrNull() ?: return
                cur.state.copy(
                    selectedAppPid = app.app.pid,
                    selectedWindowId = app.mostRecentNavigableInScope(NavScope.Shown)?.id,
                )
            }
            SwitcherEntry.Window -> {
                val app = snapshot.withWindows.firstOrNull() ?: return
                cur.state.copy(
                    selectedAppPid = app.app.pid,
                    selectedWindowId = app.shownNavigableWindows.lastOrNull()?.id,
                )
            }
        }
        log("[ctl] placeOnLastInRecency entry=$entry identity=(${nextState.selectedAppPid},${nextState.selectedWindowId})")
        _ui.value = cur.copy(state = nextState, previewedWindowId = null)
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
        log("[ctl] shortcut-key released wasRunning=$wasRunning cursor=${_ui.value?.state?.cursor}")
    }

    private fun startPressJob(entry: SwitcherEntry, reverse: Boolean) {
        val event = if (reverse) reverseEventFor(entry) else forwardEventFor(entry)
        pressJob?.cancel()
        val initialDelay = store.switcherSettings.value.repeatInitialDelayMs
        val interval = store.switcherSettings.value.repeatIntervalMs
        log("[ctl] press-job start event=$event initialDelay=${initialDelay}ms interval=${interval}ms")
        pressJob = scope.launch {
            try {
                delay(initialDelay)
                while (true) {
                    // Auto-advance is the hot-key path → Shown scope, like
                    // the explicit cmd+tab presses that armed this job.
                    navigate(event, NavScope.Shown)
                    log("[ctl] press-tick event=$event cursor=${_ui.value?.state?.cursor}")
                    delay(interval)
                }
            } finally {
                log("[ctl] press-job end event=$event")
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

    /**
     * Called from the Compose overlay's keyboard handler (arrow keys).
     * Defaults to [NavScope.All] so power users can step into demoted
     * apps/windows that the hot-key path skips.
     */
    fun onNavigate(event: SwitcherEvent, scope: NavScope = NavScope.All) {
        if (_ui.value == null) return
        log("[ctl] onNavigate event=$event scope=$scope cursor=${_ui.value?.state?.cursor}")
        navigate(event, scope)
    }

    /**
     * Mouse-driven cursor move from the overlay UI. `windowId == null` means
     * "the user is pointing at the app cell as a whole" — keep the existing
     * window cursor if we're still on the same app, otherwise reset to the
     * first navigable window (newest window of the newly-pointed app),
     * matching the keyboard NextApp behaviour.
     *
     * Addressed by window identity rather than top-level row index so child
     * rows (pinned windows, sheets) can target themselves directly — they
     * live in the DFS-flattened `navigableWindows` list but not in the
     * top-level `windows` list, so an index-based API couldn't reach them.
     *
     * Out-of-range coordinates are silently ignored — easier than asking
     * callers to validate against a snapshot they don't own.
     */
    fun onPointAt(appIndex: Int, windowId: WindowId? = null) {
        val cur = _ui.value ?: return
        if (!mouseInteracted) {
            // Stationary-mouse hover at session-open. See [mouseInteracted].
            return
        }
        val items = cur.state.snapshot.all
        if (appIndex !in items.indices) return
        val app = items[appIndex]
        val navigable = app.navigableWindows
        val resolvedWindowIndex = when {
            navigable.isEmpty() -> 0
            windowId != null -> {
                val hit = navigable.indexOfFirst { it.id == windowId }
                // Unknown id (stale snapshot races) → app-level fallback so
                // the cursor still moves onto the right app even if the
                // exact window can no longer be located.
                if (hit >= 0) hit
                else if (appIndex == cur.state.cursor.appIndex) {
                    cur.state.cursor.windowIndex.coerceIn(0, navigable.size - 1)
                } else 0
            }
            appIndex == cur.state.cursor.appIndex -> cur.state.cursor.windowIndex.coerceIn(0, navigable.size - 1)
            else -> 0
        }
        val nextCursor = SwitcherCursor(appIndex, resolvedWindowIndex)
        if (nextCursor == cur.state.cursor) return
        log("[ctl] onPointAt appIndex=$appIndex windowId=$windowId resolved=$nextCursor")
        _ui.value = cur.copy(state = cur.state.withCursor(nextCursor), previewedWindowId = null)
        schedulePreview()
    }

    /**
     * Real `PointerEventType.Move` arrived from the overlay. Flips the
     * stationary-mouse gate so subsequent [onPointAt] calls take effect.
     * Idempotent — only the first call per session matters.
     */
    fun onPointerMoved() {
        if (mouseInteracted) return
        if (_ui.value == null) return
        mouseInteracted = true
        log("[ctl] mouse interaction began")
    }

    /** Click-to-commit from the overlay UI. Same end state as cmd-release. */
    fun onCommit() {
        val cur = _ui.value ?: return
        log("[ctl] onCommit (click) cursor=${cur.state.cursor}")
        commit(cur)
    }

    fun onModifierReleased() {
        val cur = _ui.value
        if (cur == null) {
            // Race-recovery for the "modifier release arrived before
            // openSession on main" case is handled in [onShortcut] —
            // it samples `modifierHeld` at call time and auto-commits
            // if cmd is no longer pressed. So here we just drop the
            // stale notification on the floor.
            log("[diag-ctl] onModifierReleased NO-OP (no session)")
            return
        }
        log("[ctl] onModifierReleased cursor=${cur.state.cursor}")
        commit(cur)
    }

    fun onEsc() {
        if (_ui.value == null) return
        log("[ctl] onEsc cursor=${_ui.value?.state?.cursor}")
        cancel()
    }

    /**
     * Fire a side-effecting action ([SwitcherAction]) on the currently-selected
     * target. App-level actions ([SwitcherAction.QuitApp],
     * [SwitcherAction.ToggleHide]) always fire when an app is selected;
     * window-level actions are no-ops when no specific window is selected.
     *
     * Does **not** close the session — the user is still holding cmd. The
     * world mutates as a result, the live snapshot collector picks it up,
     * and the cursor moves to a neighbour automatically if the target
     * disappears (close/quit).
     */
    fun onAction(action: SwitcherAction) {
        val cur = _ui.value ?: return
        val app = cur.state.selectedAppEntry?.app ?: return
        val window = cur.state.selectedWindow
        when (action.scope) {
            ActionScope.App -> {
                log("[ctl] action=$action pid=${app.pid}")
                onPerformAction?.invoke(action, app.pid, null)
                // ToggleHide demotes the app's windows but the app itself
                // stays in the snapshot, so identity-based refreshedWith
                // would keep the cursor stuck on the now-hidden app.
                // Synchronously advance to the next app. The un-hide
                // direction brings the app forward — leave the cursor on
                // it. QuitApp's target actually disappears, so
                // refreshedWith's pickAppNeighbour handles the move.
                if (action == SwitcherAction.ToggleHide && !app.isHidden) {
                    advanceAppCursorAfterDemote()
                }
            }
            ActionScope.Window -> {
                if (window == null) {
                    log("[ctl] action=$action SKIPPED — no window selected")
                    return
                }
                log("[ctl] action=$action pid=${app.pid} wid=${window.id}")
                onPerformAction?.invoke(action, app.pid, window.id)
                // Window-only recency bump for cmd+M. The AX side effect of
                // cmd+M restore (kAXMinimized=false) does not refocus the
                // window during a switcher session — the panel is key-
                // window, the target app is in the background, macOS
                // suppresses the focus shift. Without an explicit record,
                // the just-restored window stays wherever it was in the
                // per-app window order. We record it here, but only on
                // [WorldStore.recordWindowActivation] (window-only) — not
                // the full [recordActivation] — because cmd+M is a
                // window-state interaction inside an open switcher session,
                // not an "I want this app now" signal. Promoting the app
                // in [appOrder] would mean: minimizing window A demotes
                // your real attention target (the app you came from)
                // because A's app jumped ahead. Releasing cmd to commit
                // is the path that promotes the app via [recordActivation].
                if (action == SwitcherAction.ToggleMinimize) {
                    store.recordWindowActivation(app.pid, window.id)
                    // Same problem as ToggleHide: the window stays in
                    // the snapshot (just demoted), so the identity
                    // cursor would stick on it. Advance synchronously
                    // when we're going Show → minimised; on restore the
                    // window is being brought forward, leave the cursor.
                    // CloseWindow's target actually disappears, so
                    // refreshedWith's pickWindowNeighbour handles it.
                    val nextMinimized = !window.isMinimized
                    if (nextMinimized) {
                        advanceWindowCursorAfterDemote()
                    }
                    // Optimistic AX+CG flip: close the gap between our
                    // AX-set call and the notification round-trip
                    // (~200 ms) for AX, plus the cgwl refresh interval
                    // (~2 s) for isOnscreen. Without this the
                    // just-minimised window lingers in Show for a beat,
                    // and a just-restored one falls into the brief
                    // `isMinimized=false, isOnscreen=false (stale)`
                    // window that `default-hide-cg-hidden-helper`
                    // matches → it disappears until the cgwl catches
                    // up. Real AX/CG events later confirm or correct.
                    // Called AFTER the advance so the advance computes
                    // against the pre-mutation snapshot — otherwise the
                    // just-demoted window is gone from the Shown range
                    // and advance falls back to the app-level cell.
                    store.setWindowMinimizedOptimistic(app.pid, window.id, nextMinimized)
                }
            }
        }
    }

    /**
     * Bind the current selection to a digit "tag". Three behaviours per
     * [WindowTags.assign]:
     *   - same digit on the same window → unbind (toggle off);
     *   - same digit on a different window → transfer;
     *   - different digit on the same window → move the digit (each window
     *     holds at most one tag).
     *
     * Ignored when no specific window is selected — tags are per-window, not
     * per-app, so a windowless app cell can't be tagged.
     */
    fun onAssignTag(digit: Int) {
        if (digit !in WindowTags.DIGIT_RANGE) return
        val cur = _ui.value ?: return
        val pid = cur.state.selectedAppPid ?: return
        val wid = cur.state.selectedWindowId ?: run {
            log("[ctl] onAssignTag digit=$digit SKIPPED — no window selected (pid=$pid)")
            return
        }
        val before = _tags.value
        val after = before.assign(digit, pid, wid)
        if (after == before) return
        _tags.value = after
        log("[ctl] onAssignTag digit=$digit pid=$pid wid=$wid → ${after.byDigit}")
    }

    /**
     * Move the cursor to the window tagged with [digit]. No-op when no
     * binding exists, or when the bound window no longer appears in the
     * current snapshot (which shouldn't normally happen — pruning runs on
     * every snapshot refresh — but the lookup tolerates the race anyway).
     */
    fun onJumpToTag(digit: Int) {
        val cur = _ui.value ?: return
        val (pid, wid) = _tags.value.windowFor(digit) ?: run {
            log("[ctl] onJumpToTag digit=$digit SKIPPED — no binding")
            return
        }
        val app = cur.state.snapshot.all.firstOrNull { it.app.pid == pid }
        val target = app?.navigableWindows?.firstOrNull { it.id == wid }
        if (app == null || target == null) {
            log("[ctl] onJumpToTag digit=$digit SKIPPED — bound window not in snapshot")
            return
        }
        val nextState = cur.state.copy(selectedAppPid = pid, selectedWindowId = wid)
        if (nextState == cur.state) return
        _ui.value = cur.copy(state = nextState, previewedWindowId = null)
        log("[ctl] onJumpToTag digit=$digit → pid=$pid wid=$wid")
    }

    /**
     * Move the cursor to the next window of the current app within the
     * Shown scope (cmd+tab / cmd+\` cluster). Falls back to the app-level
     * cell when no other Shown window exists — matching the "close last
     * window stays on app" semantic from [refreshedWith]'s neighbour walk.
     * Wrapping is the same as keyboard NextWindow.
     */
    private fun advanceWindowCursorAfterDemote() {
        val cur = _ui.value ?: return
        val state = cur.state
        val app = state.selectedAppEntry ?: return
        val wid = state.selectedWindowId ?: return
        val navigable = app.scopedNavigable(NavScope.Shown)
        val curIdx = navigable.indexOfFirst { it.id == wid }
        if (curIdx < 0 || navigable.size <= 1) {
            // Either the cursor isn't in the Shown range (shouldn't happen
            // on the demote-direction toggle that gates this call), or
            // this was the only Shown window of the app. Drop to the
            // app-level cell.
            log("[ctl] advanceWindowCursorAfterDemote → app-level cell (no Shown sibling)")
            _ui.value = cur.copy(
                state = state.copy(selectedWindowId = null),
                previewedWindowId = null,
            )
            return
        }
        val next = navigable[(curIdx + 1) % navigable.size]
        log("[ctl] advanceWindowCursorAfterDemote → wid=${next.id}")
        _ui.value = cur.copy(
            state = state.copy(selectedWindowId = next.id),
            previewedWindowId = null,
        )
    }

    /**
     * Move the cursor to the next app in the Shown scope. No-op when the
     * current app is the only Shown app — there's nowhere to advance to
     * and snapshot refresh will sort out the visual position when the
     * app's section flips.
     */
    private fun advanceAppCursorAfterDemote() {
        val cur = _ui.value ?: return
        val state = cur.state
        val pid = state.selectedAppPid ?: return
        val apps = state.snapshot.scopedApps(NavScope.Shown)
        val curIdx = apps.indexOfFirst { it.app.pid == pid }
        if (curIdx < 0 || apps.size <= 1) return
        val nextApp = apps[(curIdx + 1) % apps.size]
        log("[ctl] advanceAppCursorAfterDemote → pid=${nextApp.app.pid}")
        _ui.value = cur.copy(
            state = state.copy(
                selectedAppPid = nextApp.app.pid,
                selectedWindowId = nextApp.mostRecentNavigableInScope(NavScope.Shown)?.id,
            ),
            previewedWindowId = null,
        )
    }

    private fun openSession(entry: SwitcherEntry) {
        val world = store.state.value
        val filters = store.filters.value
        val pinning = store.pinning.value
        val snapshot = world.filteredSwitcherSnapshot(
            filters = filters,
            pinning = pinning,
            // Without AX, never filter by Space — show every app regardless of
            // which Space its windows are on (see [launchSnapshotCollector]).
            currentSpaceOnly = store.currentSpaceOnly.value && store.axTrusted.value,
            visibleSpaceIds = store.visibleSpaceIds.value,
        )
        val state = openSwitcher(snapshot, entry)
        val appOrder = snapshot.all.map { it.app.pid to it.app.name }
        log("[ctl] openSession entry=$entry defaultCursor=${state.cursor} apps=$appOrder")
        // Hover events that fire purely because the panel just appeared
        // under a stationary mouse must not move the cursor — see
        // [mouseInteracted]. Reset on each session-open so the gate kicks
        // in fresh every time.
        mouseInteracted = false

        showJob?.cancel()
        val delayMs = showDelayMs.coerceAtLeast(0L)
        if (delayMs == 0L) {
            // Skip the pending-then-visible dance entirely: no coroutine,
            // no `delay(0)` that yields a frame for nothing. Open visible
            // immediately.
            _ui.value = SwitcherUiState(state, visible = true, previewedWindowId = null)
            // schedulePreview()  // preview disabled — see [schedulePreview] comment
        } else {
            _ui.value = SwitcherUiState(state, visible = false, previewedWindowId = null)
            showJob = scope.launch {
                delay(delayMs)
                val cur = _ui.value ?: return@launch
                _ui.value = cur.copy(visible = true)
                // schedulePreview()  // preview disabled — see [schedulePreview] comment
            }
        }

        // Live-update the session's snapshot from `store` for the duration of
        // the session. The collector starts AFTER `_ui.value` is set so the
        // first emission has a state to refresh against. Identity-based
        // cursor + `refreshedWith` make new windows / closing windows safe
        // — see [SwitcherState.refreshedWith].
        launchSnapshotCollector()
    }

    private fun navigate(event: SwitcherEvent, scope: NavScope) {
        val cur = _ui.value ?: return
        // App-only mode (no Accessibility): a specific window can't be focused
        // cross-process without AX, so we don't let the user walk through
        // windows. Window-stepping (cmd+`, up/down arrows) is a no-op; only
        // app-stepping moves the cursor. See the no-AX spec under docs/superpowers.
        if (!store.axTrusted.value &&
            (event == SwitcherEvent.NextWindow || event == SwitcherEvent.PrevWindow)
        ) {
            return
        }
        val nextState = cur.state.apply(event, scope)
        if (nextState == cur.state) return
        // Cursor moved → previous preview-raise is no longer relevant.
        _ui.value = cur.copy(state = nextState, previewedWindowId = null)
        // schedulePreview()  // preview disabled
    }

    /**
     * Start a long-running coroutine that keeps the active session's snapshot
     * in sync with the world / filters / current-space toggles. See
     * [SwitcherState.refreshedWith] for the cursor-survival policy.
     */
    private fun launchSnapshotCollector() {
        snapshotJob?.cancel()
        // Without AX we show every app regardless of Space: we can't focus a
        // specific window or page Spaces, so per-Space filtering buys nothing,
        // and the CG-only list (no AX event stream) would otherwise lag a Space
        // change. Fold AX into the current-space toggle so the Space filter is
        // off whenever AX is absent; restored to the user's setting when AX is
        // granted. Pre-combined to keep the outer combine at five flows.
        val effectiveCurrentSpaceOnly = combine(store.currentSpaceOnly, store.axTrusted) {
            currentSpaceOnly, axTrusted -> currentSpaceOnly && axTrusted
        }
        snapshotJob = scope.launch {
            combine(
                store.state,
                store.filters,
                store.pinning,
                effectiveCurrentSpaceOnly,
                store.visibleSpaceIds,
            ) { world, filters, pinning, currentSpaceOnly, visibleSpaceIds ->
                world.filteredSwitcherSnapshot(
                    filters = filters,
                    pinning = pinning,
                    currentSpaceOnly = currentSpaceOnly,
                    visibleSpaceIds = visibleSpaceIds,
                )
            }.distinctUntilChanged().collect { newSnapshot ->
                // Drop tags whose target window disappeared. Done before the
                // refreshedWith branch so the tag state stays consistent even
                // when the snapshot is structurally identical from the cursor's
                // point of view (rare but possible — e.g. a Demote window in
                // another app vanishing).
                val pruned = _tags.value.prunedAgainst(newSnapshot)
                if (pruned !== _tags.value) {
                    _tags.value = pruned
                    log("[ctl] tags pruned: now ${pruned.byDigit}")
                }
                val cur = _ui.value ?: return@collect
                // Diagnostic: an app entering/leaving the live session
                // snapshot is exactly the "switcher shows app then animates
                // it away" bug surface, and was previously unlogged (the
                // block below only logs cursor moves). Tag each removed/added
                // app with its section (Show=withWindows / Demote=windowless)
                // and window count so the transition type is unambiguous from
                // the log alone: a removed Show app with wins>0 = window-drop
                // lag; a removed Show app with wins=0 = it was sitting as
                // windowless-default-Show and just flipped to Hide (accessory
                // policy / windowless-rule lag).
                run {
                    val old = cur.state.snapshot
                    val new = newSnapshot
                    val oldPids = old.all.mapTo(HashSet()) { it.app.pid }
                    val newPids = new.all.mapTo(HashSet()) { it.app.pid }
                    if (oldPids != newPids) {
                        fun desc(s: SwitcherSnapshot, e: AppEntry): String {
                            val idx = s.all.indexOf(e)
                            val section = if (idx in 0 until s.shownAppCount) "Show" else "Demote"
                            return "(${e.app.pid},${e.app.name},$section,wins=${e.windows.size})"
                        }
                        val removed = old.all.filter { it.app.pid !in newPids }.map { desc(old, it) }
                        val added = new.all.filter { it.app.pid !in oldPids }.map { desc(new, it) }
                        log("[diag-snap] session app-set changed removed=$removed added=$added")
                    }
                }
                val refreshed = cur.state.refreshedWith(newSnapshot)
                if (refreshed == cur.state) return@collect
                val selectionChanged =
                    refreshed.selectedAppPid != cur.state.selectedAppPid ||
                            refreshed.selectedWindowId != cur.state.selectedWindowId
                _ui.value = cur.copy(state = refreshed, previewedWindowId = null)
                // if (selectionChanged) schedulePreview()  // preview disabled
                if (selectionChanged) {
                    log("[ctl] snapshot refresh moved cursor to " +
                            "pid=${refreshed.selectedAppPid} wid=${refreshed.selectedWindowId}")
                }
            }
        }
    }

    /**
     * Preview-raise on cursor change. Disabled — call sites are commented
     * out, the body kept for when we revisit. The real reason it stays
     * present in the source is that it was the original justification for
     * the `_switcherActive` gate: a session-time AX echo from our own
     * AXRaise pollutes the activation log if not gated. With preview off,
     * that echo source is gone, the gate is gone, and we accept other
     * session-time AX echoes (cmd+M's focus-shift to the next window,
     * external focus theft) as legitimate signals — they reflect what
     * macOS actually focused, which is what the switcher should surface
     * next time.
     */
    @Suppress("unused")
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
        // App-only mode (no AX): we can't target a specific window, so commit
        // at app level. A null windowId routes the platform layer to app
        // activation (SLPSMode.allWindows) instead of a phantom window target,
        // and keeps the recency log from recording a window the user never
        // actually got to pick. See the no-AX spec under docs/superpowers.
        val commitWindowId = if (store.axTrusted.value) window?.id else null
        log("[ctl] commit cursor=${cur.state.cursor} app=${app?.pid}/${app?.name} " +
            "window=${window?.id}/${window?.title} commitWid=$commitWindowId")
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
            store.recordActivation(app.pid, commitWindowId)
            onCommitActivation?.invoke(app.pid, commitWindowId)
        }
    }

    private fun cancel() {
        // Capture before closeSession — closeSession is local to the
        // controller and doesn't touch the store's active pointers, but
        // reading first keeps the order obvious and makes future moves of
        // pointer-clearing into closeSession safe.
        val focusedPid = store.activeAppPid.value
        val focusedWid = store.activeWindowId.value
        closeSession()
        // Realign z-order with focus. cmd+M restore during the cancelled
        // session may have popped a now-de-minimised window above the
        // user's actual focus target — when the panel goes away, focus
        // reverts to the pre-session window but visually the de-minimised
        // one obscures it. Raising the still-focused window puts the
        // visual back on top of the focus.
        if (focusedPid != null && focusedWid != null) {
            onRaiseFocusedWindow?.invoke(focusedPid, focusedWid)
        }
    }

    /** Tear down everything that constitutes a "live session": UI state and
     *  pending timers. AX/NSWorkspace activation events flow into the store
     *  unconditionally now (no `_switcherActive` gate) — every echo, including
     *  ones from our own commit and from session-time side effects (cmd+M
     *  shifting focus to a sibling), is treated as a legitimate recency
     *  signal. */
    private fun closeSession() {
        log("[ctl] closeSession")
        showJob?.cancel(); showJob = null
        previewJob?.cancel(); previewJob = null
        pressJob?.cancel(); pressJob = null
        snapshotJob?.cancel(); snapshotJob = null
        // Clear the held combo so the next cmd+tab is recognised as a fresh
        // press; otherwise a stale value would mask the first onShortcut
        // after commit/cancel and the user would see no advance.
        heldShortcut = null
        _ui.value = null
        // Force the next session's first onPanelSize emission to look
        // distinct from this session's last value — otherwise
        // distinctUntilChanged in observeSwitcherPanelSize might suppress
        // it and Swift wouldn't re-apply setContentSize.
        store.clearSwitcherPanelSize()
    }
}
