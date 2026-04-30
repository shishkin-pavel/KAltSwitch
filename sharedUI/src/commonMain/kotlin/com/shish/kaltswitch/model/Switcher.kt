package com.shish.kaltswitch.model

enum class SwitcherEntry { App, Window }

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
 * `selectedAppPid == -1` represents "no selection" (empty snapshot, transient).
 * `selectedWindowId == null` represents an app-level cell (windowless app, or
 * an app whose AX window list is unknown).
 */
data class SwitcherState(
    val snapshot: SwitcherSnapshot,
    val entry: SwitcherEntry,
    val selectedAppPid: Int,
    val selectedWindowId: WindowId?,
) {
    val selectedAppEntry: AppEntry?
        get() = if (selectedAppPid < 0) null
        else snapshot.all.firstOrNull { it.app.pid == selectedAppPid }

    val selectedWindow: Window?
        get() {
            val app = selectedAppEntry ?: return null
            val wid = selectedWindowId ?: return null
            return app.windows.firstOrNull { it.id == wid }
        }

    /**
     * UI-friendly indices into [snapshot]. Falls back to `(0, 0)` if the
     * identity isn't resolvable in the current snapshot — should be
     * unreachable while [refreshedWith] is the only path that mutates
     * [snapshot] mid-session.
     */
    val cursor: SwitcherCursor
        get() {
            val appIndex = snapshot.all.indexOfFirst { it.app.pid == selectedAppPid }
            if (appIndex < 0) return SwitcherCursor(0, 0)
            val app = snapshot.all[appIndex]
            val windowIndex = if (selectedWindowId == null) 0
            else app.windows.indexOfFirst { it.id == selectedWindowId }
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
 * Default cursor on switcher open. Shown-scope by default — the Carbon hot
 * key path is the canonical entry, and we want it to land on a visible (Show)
 * item even when the inspector is configured to demote a lot of apps:
 *   - App entry  → app[1].window[0] (second-most-recent Show app, its newest window).
 *   - Window entry → app[0].window[1] (current app, its second-most-recent Show window).
 *
 * Both fall back to the closest available cell when the scope's range is
 * smaller than the index (e.g. only one Show app → cursor stays on app[0]).
 */
fun SwitcherSnapshot.defaultCursor(
    entry: SwitcherEntry,
    scope: NavScope = NavScope.Shown,
): SwitcherCursor {
    val items = all
    if (items.isEmpty()) return SwitcherCursor(0, 0)
    return when (entry) {
        SwitcherEntry.App -> {
            val maxIndex = appLastIndexInScope(scope)
            SwitcherCursor(
                appIndex = 1.coerceAtMost(maxIndex),
                windowIndex = 0,
            )
        }
        SwitcherEntry.Window -> {
            val first = items[0]
            val maxIndex = first.windowLastIndexInScope(scope)
            SwitcherCursor(
                appIndex = 0,
                windowIndex = 1.coerceAtMost(maxIndex),
            )
        }
    }
}

fun openSwitcher(snapshot: SwitcherSnapshot, entry: SwitcherEntry): SwitcherState {
    val initialCursor = snapshot.defaultCursor(entry)
    val (pid, wid) = snapshot.identityAt(initialCursor)
    return SwitcherState(
        snapshot = snapshot,
        entry = entry,
        selectedAppPid = pid,
        selectedWindowId = wid,
    )
}

/** Resolve `(appIndex, windowIndex)` to (pid, windowId?) against this snapshot.
 *  Returns `(-1, null)` when the snapshot is empty so an empty switcher session
 *  has a well-defined "no selection" identity. */
internal fun SwitcherSnapshot.identityAt(cursor: SwitcherCursor): Pair<Int, WindowId?> {
    val app = all.getOrNull(cursor.appIndex) ?: return -1 to null
    val wid = app.windows.getOrNull(cursor.windowIndex)?.id
    return app.app.pid to wid
}

/**
 * Apply a navigation event under the given scope. Wraps around at the scope's
 * edges. Changing apps resets `windowIndex` to 0 (most-recent window of the
 * newly-selected app).
 *
 * If the cursor sits *outside* the scope's range when a hot-key event fires
 * (e.g. user used the arrow keys to step into a demoted app, then hit
 * cmd+tab), the cursor snaps to the start/end of the in-scope range — same
 * effect as a fresh open. That matches the user expectation of "cmd+tab
 * keeps me in Show; arrows can wander".
 */
fun SwitcherState.apply(
    event: SwitcherEvent,
    scope: NavScope = NavScope.All,
): SwitcherState {
    val items = snapshot.all
    if (items.isEmpty()) return this
    val current = cursor

    return when (event) {
        SwitcherEvent.NextApp -> {
            val maxIdx = snapshot.appLastIndexInScope(scope)
            if (maxIdx < 0) return this
            val next = nextInRange(current.appIndex, 0, maxIdx)
            withCursor(SwitcherCursor(appIndex = next, windowIndex = 0))
        }
        SwitcherEvent.PrevApp -> {
            val maxIdx = snapshot.appLastIndexInScope(scope)
            if (maxIdx < 0) return this
            val prev = prevInRange(current.appIndex, 0, maxIdx)
            withCursor(SwitcherCursor(appIndex = prev, windowIndex = 0))
        }
        SwitcherEvent.NextWindow -> {
            val app = items.getOrNull(current.appIndex) ?: return this
            val maxIdx = app.windowLastIndexInScope(scope)
            if (maxIdx < 0) this
            else withCursor(current.copy(windowIndex = nextInRange(current.windowIndex, 0, maxIdx)))
        }
        SwitcherEvent.PrevWindow -> {
            val app = items.getOrNull(current.appIndex) ?: return this
            val maxIdx = app.windowLastIndexInScope(scope)
            if (maxIdx < 0) this
            else withCursor(current.copy(windowIndex = prevInRange(current.windowIndex, 0, maxIdx)))
        }
    }
}

/** Set the cursor by index, translating to the persisted (pid, windowId)
 *  identity against the current snapshot. The switcher state is index-driven
 *  for navigation events but identity-stored, so callers from [apply] / hover
 *  / [SwitcherController.placeOnLastInRecency] all flow through one helper. */
fun SwitcherState.withCursor(cursor: SwitcherCursor): SwitcherState {
    val (pid, wid) = snapshot.identityAt(cursor)
    return copy(selectedAppPid = pid, selectedWindowId = wid)
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
        return copy(snapshot = newSnapshot, selectedAppPid = -1, selectedWindowId = null)
    }

    val newApp = newAll.firstOrNull { it.app.pid == selectedAppPid }
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
            oldApp = snapshot.all.firstOrNull { it.app.pid == selectedAppPid },
            newApp = newApp,
            disappearedWid = selectedWindowId,
        )
        return copy(snapshot = newSnapshot, selectedWindowId = replacementWid)
    }

    // Selected app gone. Walk the OLD app order looking for a survivor.
    val replacementApp = pickAppNeighbour(
        oldAll = snapshot.all,
        newAll = newAll,
        disappearedPid = selectedAppPid,
    ) ?: newAll.first()
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
    if (newApp.windows.isEmpty()) return null
    val oldWindows = oldApp?.windows.orEmpty()
    val oldIndex = oldWindows.indexOfFirst { it.id == disappearedWid }
    if (oldIndex >= 0) {
        // Look right first (visual right-neighbour matches what the user saw).
        for (i in (oldIndex + 1) until oldWindows.size) {
            val cand = oldWindows[i].id
            if (newApp.windows.any { it.id == cand }) return cand
        }
        for (i in (oldIndex - 1) downTo 0) {
            val cand = oldWindows[i].id
            if (newApp.windows.any { it.id == cand }) return cand
        }
    }
    return newApp.windows.first().id
}

private fun pickAppNeighbour(
    oldAll: List<AppEntry>,
    newAll: List<AppEntry>,
    disappearedPid: Int,
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

/** Last valid app index inside [scope] in [SwitcherSnapshot.all]. -1 if empty. */
fun SwitcherSnapshot.appLastIndexInScope(scope: NavScope): Int = when (scope) {
    NavScope.Shown -> shownAppCount - 1
    NavScope.All -> all.lastIndex
}

/** Last valid window index inside [scope] in [AppEntry.windows]. -1 if empty. */
fun AppEntry.windowLastIndexInScope(scope: NavScope): Int = when (scope) {
    NavScope.Shown -> shownWindowCount - 1
    NavScope.All -> windows.lastIndex
}

private fun nextInRange(current: Int, min: Int, max: Int): Int {
    val size = max - min + 1
    if (size <= 0) return current
    return if (current < min || current > max) min
    else (current - min + 1).mod(size) + min
}

private fun prevInRange(current: Int, min: Int, max: Int): Int {
    val size = max - min + 1
    if (size <= 0) return current
    return if (current < min || current > max) max
    else (current - min - 1 + size).mod(size) + min
}
