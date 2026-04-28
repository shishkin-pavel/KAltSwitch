package com.shish.kaltswitch.model

enum class SwitcherEntry { App, Window }

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
 * Default cursor on switcher open:
 *   - App entry  → app[1].window[0] (second-most-recent app, its newest window).
 *   - Window entry → app[0].window[1] (current app, its second-most-recent window).
 *
 * Both fall back to the closest available cell when the snapshot is small.
 */
fun SwitcherSnapshot.defaultCursor(entry: SwitcherEntry): SwitcherCursor {
    val items = all
    if (items.isEmpty()) return SwitcherCursor(0, 0)
    return when (entry) {
        SwitcherEntry.App -> SwitcherCursor(
            appIndex = 1.coerceAtMost(items.lastIndex),
            windowIndex = 0,
        )
        SwitcherEntry.Window -> SwitcherCursor(
            appIndex = 0,
            windowIndex = 1.coerceAtMost(items[0].windows.lastIndex.coerceAtLeast(0)),
        )
    }
}

fun openSwitcher(snapshot: SwitcherSnapshot, entry: SwitcherEntry): SwitcherState =
    SwitcherState(snapshot, entry, snapshot.defaultCursor(entry))

/**
 * Apply a navigation event. Wraps around at edges. Changing apps resets window index to 0
 * (most-recent window of the newly-selected app).
 */
fun SwitcherState.apply(event: SwitcherEvent): SwitcherState {
    val items = snapshot.all
    if (items.isEmpty()) return this

    return when (event) {
        SwitcherEvent.NextApp -> copy(
            cursor = SwitcherCursor(
                appIndex = (cursor.appIndex + 1).mod(items.size),
                windowIndex = 0,
            )
        )
        SwitcherEvent.PrevApp -> copy(
            cursor = SwitcherCursor(
                appIndex = (cursor.appIndex - 1).mod(items.size),
                windowIndex = 0,
            )
        )
        SwitcherEvent.NextWindow -> {
            val windows = items[cursor.appIndex].windows
            if (windows.isEmpty()) this
            else copy(
                cursor = cursor.copy(
                    windowIndex = (cursor.windowIndex + 1).mod(windows.size),
                )
            )
        }
        SwitcherEvent.PrevWindow -> {
            val windows = items[cursor.appIndex].windows
            if (windows.isEmpty()) this
            else copy(
                cursor = cursor.copy(
                    windowIndex = (cursor.windowIndex - 1).mod(windows.size),
                )
            )
        }
    }
}
