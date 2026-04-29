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

data class SwitcherState(
    val snapshot: SwitcherSnapshot,
    val entry: SwitcherEntry,
    val cursor: SwitcherCursor,
) {
    val selectedAppEntry: AppEntry?
        get() = snapshot.all.getOrNull(cursor.appIndex)
    val selectedWindow: Window?
        get() = selectedAppEntry?.windows?.getOrNull(cursor.windowIndex)
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

fun openSwitcher(snapshot: SwitcherSnapshot, entry: SwitcherEntry): SwitcherState =
    SwitcherState(snapshot, entry, snapshot.defaultCursor(entry))

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

    return when (event) {
        SwitcherEvent.NextApp -> {
            val maxIdx = snapshot.appLastIndexInScope(scope)
            if (maxIdx < 0) return this
            val next = nextInRange(cursor.appIndex, 0, maxIdx)
            copy(cursor = SwitcherCursor(appIndex = next, windowIndex = 0))
        }
        SwitcherEvent.PrevApp -> {
            val maxIdx = snapshot.appLastIndexInScope(scope)
            if (maxIdx < 0) return this
            val prev = prevInRange(cursor.appIndex, 0, maxIdx)
            copy(cursor = SwitcherCursor(appIndex = prev, windowIndex = 0))
        }
        SwitcherEvent.NextWindow -> {
            val app = items.getOrNull(cursor.appIndex) ?: return this
            val maxIdx = app.windowLastIndexInScope(scope)
            if (maxIdx < 0) this
            else copy(cursor = cursor.copy(windowIndex = nextInRange(cursor.windowIndex, 0, maxIdx)))
        }
        SwitcherEvent.PrevWindow -> {
            val app = items.getOrNull(cursor.appIndex) ?: return this
            val maxIdx = app.windowLastIndexInScope(scope)
            if (maxIdx < 0) this
            else copy(cursor = cursor.copy(windowIndex = prevInRange(cursor.windowIndex, 0, maxIdx)))
        }
    }
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
