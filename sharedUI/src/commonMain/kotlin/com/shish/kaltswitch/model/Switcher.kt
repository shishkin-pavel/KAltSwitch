package com.shish.kaltswitch.model

enum class SwitcherEntry { App, Window }

/**
 * Side-effecting actions a user can fire on the currently-selected target
 * via single-key bindings while the switcher session is open (cmd held).
 * Bindings: `Q` → [QuitApp], `W` → [CloseWindow], `M` → [ToggleMinimize],
 * `H` → [ToggleHide], `F` → [ToggleFullscreen].
 *
 * The actions split by **scope**:
 *   - App-level ([QuitApp], [ToggleHide]) — fire on `selectedAppPid`
 *     regardless of whether a window is selected. Always available.
 *   - Window-level ([CloseWindow], [ToggleMinimize], [ToggleFullscreen]) —
 *     no-op when no specific window is selected (e.g. windowless app).
 *
 * Side effects flow back through the live snapshot ([SwitcherState.refreshedWith]):
 * a quit/close removes the target from the world, the cursor jumps to the
 * right-neighbour automatically; a minimize/hide/fullscreen toggle changes
 * status flags but keeps identity. The session itself **stays open** — the
 * user keeps holding cmd and can navigate further.
 */
enum class SwitcherAction(internal val scope: ActionScope) {
    QuitApp(ActionScope.App),
    CloseWindow(ActionScope.Window),
    ToggleMinimize(ActionScope.Window),
    ToggleHide(ActionScope.App),
    ToggleFullscreen(ActionScope.Window),
}

internal enum class ActionScope { App, Window }

/**
 * Range the navigation event applies to:
 *   - [Shown]: only the inspector's `Show` items (`withWindows` for apps,
 *     leading `shownWindowCount` for windows). The Carbon hot keys
 *     (cmd+tab, cmd+shift+tab, cmd+`, cmd+shift+`) run in this scope.
 *   - [All]: the full `Show + Demote` set. The arrow keys run in this scope
 *     so power users can still reach demoted entries from the keyboard.
 */
enum class NavScope { Shown, All }

data class SwitcherCursor(val appIndex: Int, val windowIndex: Int)

/**
 * Live state of the switcher UI.
 *
 * The cursor is stored as **identity** (`selectedAppPid` + `selectedWindowId`)
 * rather than indices. The visual position ([cursor]) is computed against
 * [snapshot]: that way a live snapshot refresh during the session — a window
 * closing, a new app launching — can update the snapshot without making the
 * user's selection silently jump to whatever sits at the same index. See
 * [refreshedWith] for the fallback rules when the selected target itself
 * disappears.
 *
 * `selectedAppPid == null` represents "no selection" (empty snapshot, transient).
 * `selectedWindowId == null` represents an app-level cell (windowless app, or
 * an app whose AX window list is unknown).
 */
data class SwitcherState(
    val snapshot: SwitcherSnapshot,
    val entry: SwitcherEntry,
    val selectedAppPid: Pid?,
    val selectedWindowId: WindowId?,
) {
    val selectedAppEntry: AppEntry?
        get() {
            val pid = selectedAppPid ?: return null
            return snapshot.all.firstOrNull { it.app.pid == pid }
        }

    val selectedWindow: Window?
        get() {
            val app = selectedAppEntry ?: return null
            val wid = selectedWindowId ?: return null
            return app.navigableWindows.firstOrNull { it.id == wid }
        }

    /**
     * UI-friendly indices into [snapshot]. Falls back to `(0, 0)` if the
     * identity isn't resolvable in the current snapshot — should be
     * unreachable while [refreshedWith] is the only path that mutates
     * [snapshot] mid-session.
     */
    val cursor: SwitcherCursor
        get() {
            val pid = selectedAppPid ?: return SwitcherCursor(0, 0)
            val appIndex = snapshot.all.indexOfFirst { it.app.pid == pid }
            if (appIndex < 0) return SwitcherCursor(0, 0)
            val app = snapshot.all[appIndex]
            val windowIndex = if (selectedWindowId == null) 0
            else app.navigableWindows.indexOfFirst { it.id == selectedWindowId }
                .takeIf { it >= 0 } ?: 0
            return SwitcherCursor(appIndex, windowIndex)
        }
}

sealed interface SwitcherEvent {
    object NextApp : SwitcherEvent
    object PrevApp : SwitcherEvent
    object NextWindow : SwitcherEvent
    object PrevWindow : SwitcherEvent
}

/**
 * Default cursor identity on switcher open. Shown-scope by default — the
 * Carbon hot-key path is the canonical entry, and we want it to land on a
 * visible (Show) item even when the user has demoted lots of apps:
 *   - App entry → second-most-recent Show app, its newest navigable window
 *     (clamped to the only available app when there's just one).
 *   - Window entry → current app, its second-most-recent Show window.
 *
 * Returns `(null, null)` when the snapshot is empty so an empty switcher
 * session has a well-defined "no selection" identity.
 */
internal fun SwitcherSnapshot.defaultIdentity(
    entry: SwitcherEntry,
    scope: NavScope = NavScope.Shown,
): Pair<Pid?, WindowId?> {
    val apps = scopedApps(scope)
    if (apps.isEmpty()) return null to null
    return when (entry) {
        SwitcherEntry.App -> {
            val app = apps.getOrNull(1) ?: apps.first()
            app.app.pid to app.scopedNavigable(scope).firstOrNull()?.id
        }
        SwitcherEntry.Window -> {
            val app = apps.first()
            val wins = app.scopedNavigable(scope)
            val wid = wins.getOrNull(1)?.id ?: wins.firstOrNull()?.id
            app.app.pid to wid
        }
    }
}

/** Index-based wrapper around [defaultIdentity] kept for tests / debug surfaces
 *  that still want a numeric cursor. New code should reach for the identity
 *  fields on [SwitcherState] directly. */
fun SwitcherSnapshot.defaultCursor(
    entry: SwitcherEntry,
    scope: NavScope = NavScope.Shown,
): SwitcherCursor {
    val (pid, wid) = defaultIdentity(entry, scope)
    if (pid == null) return SwitcherCursor(0, 0)
    val appIdx = all.indexOfFirst { it.app.pid == pid }.coerceAtLeast(0)
    val winIdx = wid?.let { id ->
        all[appIdx].navigableWindows.indexOfFirst { it.id == id }
    } ?: 0
    return SwitcherCursor(appIdx, winIdx.coerceAtLeast(0))
}

fun openSwitcher(snapshot: SwitcherSnapshot, entry: SwitcherEntry): SwitcherState {
    val (pid, wid) = snapshot.defaultIdentity(entry)
    return SwitcherState(
        snapshot = snapshot,
        entry = entry,
        selectedAppPid = pid,
        selectedWindowId = wid,
    )
}

/** Resolve a numeric cursor to its (pid, windowId?) identity against this
 *  snapshot. `windowIndex` indexes into the app's [AppEntry.navigableWindows]
 *  (DFS over roots + pinned children). Returns `(null, null)` for an out-of-
 *  range cursor so an empty session has a well-defined no-selection identity. */
internal fun SwitcherSnapshot.identityAt(cursor: SwitcherCursor): Pair<Pid?, WindowId?> {
    val app = all.getOrNull(cursor.appIndex) ?: return null to null
    val wid = app.navigableWindows.getOrNull(cursor.windowIndex)?.id
    return app.app.pid to wid
}

/** Set the cursor by numeric index, translating to the persisted identity.
 *  Kept for the mouse-pointAt path (which speaks indices) and for tests; the
 *  keyboard `apply()` works on identity directly. */
fun SwitcherState.withCursor(cursor: SwitcherCursor): SwitcherState {
    val (pid, wid) = snapshot.identityAt(cursor)
    return copy(selectedAppPid = pid, selectedWindowId = wid)
}

/**
 * Apply a navigation event under the given scope. Identity-based: the
 * cursor advances by index *within the scoped list* and wraps at the edges,
 * but the persisted state remains (pid, windowId). Changing apps lands the
 * cursor on the new app's newest navigable window (or its app-level cell
 * when the app is windowless / its windows are all out-of-scope).
 *
 * `NavScope.Shown` operates on `withWindows` + each app's
 * `shownNavigableWindows` (Show entries DFS, Demote subtrees pruned).
 * `NavScope.All` operates on `all` apps + each app's `navigableWindows`
 * (full DFS, Show siblings before Demote siblings per level — already
 * baked in by `classifyWindow`'s child sort).
 *
 * If the cursor sits on an item that the scope doesn't include (e.g. user
 * arrow-keyed to a demoted PiP, then hit cmd+tab), the next/prev still
 * wraps inside the scope — same effect as a fresh open, matches "cmd+tab
 * keeps me in Show; arrows can wander".
 */
fun SwitcherState.apply(
    event: SwitcherEvent,
    scope: NavScope = NavScope.All,
): SwitcherState = when (event) {
    SwitcherEvent.NextApp -> stepApp(scope, forward = true)
    SwitcherEvent.PrevApp -> stepApp(scope, forward = false)
    SwitcherEvent.NextWindow -> stepWindow(scope, forward = true)
    SwitcherEvent.PrevWindow -> stepWindow(scope, forward = false)
}

private fun SwitcherState.stepApp(scope: NavScope, forward: Boolean): SwitcherState {
    val apps = snapshot.scopedApps(scope)
    if (apps.isEmpty()) return this
    val currentIdx = apps.indexOfFirst { it.app.pid == selectedAppPid }
    val nextApp = apps[stepIndex(currentIdx, apps.size, forward)]
    return copy(
        selectedAppPid = nextApp.app.pid,
        selectedWindowId = nextApp.scopedNavigable(scope).firstOrNull()?.id,
    )
}

private fun SwitcherState.stepWindow(scope: NavScope, forward: Boolean): SwitcherState {
    val app = selectedAppEntry ?: return this
    val windows = app.scopedNavigable(scope)
    if (windows.isEmpty()) return this
    val currentIdx = windows.indexOfFirst { it.id == selectedWindowId }
    return copy(selectedWindowId = windows[stepIndex(currentIdx, windows.size, forward)].id)
}

/** Cyclic step: advance/retreat by one within `[0, size)`, wrapping at the
 *  edges. A `current` of -1 (out of scope) lands at the start (forward) or
 *  end (backward) — same as the previous "snap to range edge" rule. */
private fun stepIndex(current: Int, size: Int, forward: Boolean): Int {
    if (size <= 0) return 0
    if (current < 0) return if (forward) 0 else size - 1
    return if (forward) (current + 1).mod(size) else (current - 1 + size).mod(size)
}

internal fun SwitcherSnapshot.scopedApps(scope: NavScope): List<AppEntry> = when (scope) {
    NavScope.Shown -> withWindows
    NavScope.All -> all
}

internal fun AppEntry.scopedNavigable(scope: NavScope): List<Window> = when (scope) {
    NavScope.Shown -> shownNavigableWindows
    NavScope.All -> navigableWindows
}

/**
 * Return a state whose [snapshot] is replaced by [newSnapshot], with the
 * cursor identity preserved if the selected (pid, windowId) still exists,
 * or moved to the right-neighbour in the **previous** snapshot's order if
 * the selected target disappeared (so the cursor jump matches what the
 * user just saw).
 *
 * Falls back through:
 *   1. selected window present in same app → keep identity unchanged
 *   2. selected window gone, app present → next-then-previous live window
 *      from the old window order; or app-level cell if app is windowless
 *   3. selected app gone → next-then-previous live app from the old app
 *      order; cursor lands on its first window or app-level cell
 *   4. nothing recoverable → first app/window of new snapshot
 *   5. new snapshot empty → no-selection sentinel `(-1, null)`
 *
 * No-op (returns `this`) when the snapshot is structurally identical so
 * data-class equality short-circuits unnecessary recompositions.
 */
fun SwitcherState.refreshedWith(newSnapshot: SwitcherSnapshot): SwitcherState {
    if (newSnapshot == snapshot) return this

    val newAll = newSnapshot.all
    if (newAll.isEmpty()) {
        return copy(snapshot = newSnapshot, selectedAppPid = null, selectedWindowId = null)
    }

    val pid = selectedAppPid
    val newApp = pid?.let { p -> newAll.firstOrNull { it.app.pid == p } }
    if (newApp != null) {
        // Selected app survived. Either window is still alive, or pick a
        // neighbour from the OLD app's window order.
        if (selectedWindowId == null) {
            // App-level cell. If the app is still windowless or unknown, stay
            // there; otherwise drop into its first (newest) window.
            val nextWid = if (newApp.windows.isEmpty()) null else newApp.windows.first().id
            return copy(snapshot = newSnapshot, selectedWindowId = nextWid)
        }
        if (newApp.windows.any { it.id == selectedWindowId }) {
            return copy(snapshot = newSnapshot)
        }
        val replacementWid = pickWindowNeighbour(
            oldApp = snapshot.all.firstOrNull { it.app.pid == pid },
            newApp = newApp,
            disappearedWid = selectedWindowId,
        )
        return copy(snapshot = newSnapshot, selectedWindowId = replacementWid)
    }

    // Selected app gone (or unset). Walk the OLD app order looking for a survivor.
    val replacementApp = pid?.let {
        pickAppNeighbour(oldAll = snapshot.all, newAll = newAll, disappearedPid = it)
    } ?: newAll.first()
    return copy(
        snapshot = newSnapshot,
        selectedAppPid = replacementApp.app.pid,
        selectedWindowId = replacementApp.windows.firstOrNull()?.id,
    )
}

private fun pickWindowNeighbour(
    oldApp: AppEntry?,
    newApp: AppEntry,
    disappearedWid: WindowId,
): WindowId? {
    // Operate on the navigable list so pinned children participate in the
    // neighbour walk — closing a child window should jump to its sibling /
    // parent the same way closing a top-level window jumps to its neighbour.
    val newNav = newApp.navigableWindows
    if (newNav.isEmpty()) return null
    val newIds = newNav.mapTo(HashSet()) { it.id }
    val oldNav = oldApp?.navigableWindows.orEmpty()
    val oldIndex = oldNav.indexOfFirst { it.id == disappearedWid }
    if (oldIndex >= 0) {
        for (i in (oldIndex + 1) until oldNav.size) {
            val cand = oldNav[i].id
            if (cand in newIds) return cand
        }
        for (i in (oldIndex - 1) downTo 0) {
            val cand = oldNav[i].id
            if (cand in newIds) return cand
        }
    }
    return newNav.first().id
}

private fun pickAppNeighbour(
    oldAll: List<AppEntry>,
    newAll: List<AppEntry>,
    disappearedPid: Pid,
): AppEntry? {
    val oldIndex = oldAll.indexOfFirst { it.app.pid == disappearedPid }
    if (oldIndex < 0) return null
    for (i in (oldIndex + 1) until oldAll.size) {
        val candPid = oldAll[i].app.pid
        val cand = newAll.firstOrNull { it.app.pid == candPid }
        if (cand != null) return cand
    }
    for (i in (oldIndex - 1) downTo 0) {
        val candPid = oldAll[i].app.pid
        val cand = newAll.firstOrNull { it.app.pid == candPid }
        if (cand != null) return cand
    }
    return null
}

/** Last valid app index inside [scope] in [SwitcherSnapshot.all]. -1 if empty.
 *  Retained for callers that still want a numeric range; new code reaches
 *  for [scopedApps] directly. */
fun SwitcherSnapshot.appLastIndexInScope(scope: NavScope): Int = when (scope) {
    NavScope.Shown -> shownAppCount - 1
    NavScope.All -> all.lastIndex
}

/** Last valid window index inside [scope] in [AppEntry.navigableWindows].
 *  Retained for callers that still want a numeric range; new code reaches
 *  for [scopedNavigable] directly. */
fun AppEntry.windowLastIndexInScope(scope: NavScope): Int = when (scope) {
    NavScope.Shown -> shownWindowCount - 1
    NavScope.All -> navigableWindows.lastIndex
}
