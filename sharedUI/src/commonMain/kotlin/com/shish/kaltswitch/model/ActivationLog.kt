package com.shish.kaltswitch.model

/**
 * Single source of truth for "what was used most recently". Two independent
 * recency indices, both newest-first deduped lists:
 *
 *  - [appOrder] — per-pid recency, bumped on **every** activation (window-
 *    level or app-level). Survives window-list pruning, so a windowless app
 *    that was active a moment ago keeps its place at the top.
 *  - [windowsOrderByPid] — per-(pid, windowId) recency, bumped only on
 *    window-level activations. A pid's per-window list is pruned when
 *    its windows die; the pid itself stays in [appOrder].
 *
 * Splitting the two solves the "windowless app falls to the end" bug: the
 * old single-event-log derived both orderings, so when a pid's last
 * window-level event got pruned (every active app emits window-level
 * events; app-level events are rarer), the pid disappeared from
 * [appOrder] entirely.
 *
 * No timestamps: we only ever ask "who is newer", never "how long ago".
 * List-based ordering is equivalent and avoids a clock dependency in the
 * pure-logic model (tests stay deterministic without injecting a fake
 * clock).
 */
data class ActivationEvent(
    val pid: Pid,
    val windowId: WindowId?,
)

data class ActivationLog(
    /** Pids newest-first, deduped. */
    val appOrder: List<Pid> = emptyList(),
    /** Per-pid: window ids newest-first, deduped. Pids absent from this map
     *  have no recorded window activations (could be windowless or simply
     *  AX-unknown). */
    val windowsOrderByPid: Map<Pid, List<WindowId>> = emptyMap(),
) {

    /** Append a full activation: bumps both indices. The pid moves to the
     *  front of [appOrder]; if [event] carries a window id, that id also
     *  moves to the front of `windowsOrderByPid[pid]`. App-level events
     *  (`windowId == null`) bump only [appOrder].
     *
     *  Use this for events that mean "the user's attention is now on this
     *  (app, window)" — workspace activations, switcher commit, real focus
     *  shifts. For a window-state interaction that doesn't shift attention
     *  to the app (cmd+M minimize/restore in the switcher: the user is
     *  still browsing inside the switcher, not asking to use that app
     *  yet), see [recordWindow]. */
    fun record(event: ActivationEvent): ActivationLog {
        val newAppOrder = bumpFront(appOrder, event.pid)
        val newWindowOrder = if (event.windowId != null) {
            val perPid = windowsOrderByPid[event.pid].orEmpty()
            windowsOrderByPid + (event.pid to bumpFront(perPid, event.windowId))
        } else {
            windowsOrderByPid
        }
        return copy(appOrder = newAppOrder, windowsOrderByPid = newWindowOrder)
    }

    /** Window-only counterpart of [record]: bumps `windowsOrderByPid[pid]`
     *  without touching [appOrder] or any other pid's window order. The pid
     *  may not even be in [appOrder] yet (e.g. early-life states); the
     *  window order is recorded regardless so when the pid does enter
     *  [appOrder] later, its per-window history is already there. */
    fun recordWindow(pid: Pid, windowId: WindowId): ActivationLog {
        val perPid = windowsOrderByPid[pid].orEmpty()
        return copy(windowsOrderByPid = windowsOrderByPid + (pid to bumpFront(perPid, windowId)))
    }

    /** Drop every entry for [pid]. Used when the app terminates so a future
     *  reused pid cannot inherit stale recency. */
    fun withoutPid(pid: Pid): ActivationLog =
        copy(
            appOrder = appOrder.filterNot { it == pid },
            windowsOrderByPid = windowsOrderByPid - pid,
        )

    /** Drop a single window from [pid]'s per-window order. The app-level
     *  recency in [appOrder] is preserved — closing a window doesn't make
     *  the user "less recently using" the app. */
    fun withoutWindow(pid: Pid, windowId: WindowId): ActivationLog {
        val perPid = windowsOrderByPid[pid] ?: return this
        val updated = perPid.filterNot { it == windowId }
        return copy(windowsOrderByPid = windowsOrderByPid + (pid to updated))
    }

    /** Drop window entries for [pid] whose ids aren't in [liveWindowIds].
     *  Used by snapshot refreshes that report the full live window set in
     *  one go. [appOrder] is untouched — the pid stays in app recency
     *  even when all its windows are gone. */
    fun withoutMissingWindows(pid: Pid, liveWindowIds: Set<WindowId>): ActivationLog {
        val perPid = windowsOrderByPid[pid] ?: return this
        val updated = perPid.filter { it in liveWindowIds }
        return copy(windowsOrderByPid = windowsOrderByPid + (pid to updated))
    }

    /** Window ids of [pid] ordered newest-first. Empty if the pid has no
     *  recorded window activations. */
    fun windowOrder(pid: Pid): List<WindowId> = windowsOrderByPid[pid].orEmpty()
}

/** Move [item] to the front of [list], removing any existing occurrence so
 *  the result stays deduped. If [item] was already at the front, returns
 *  [list] unchanged for cheap data-class equality short-circuits upstream. */
private fun <T> bumpFront(list: List<T>, item: T): List<T> {
    if (list.firstOrNull() == item) return list
    val out = ArrayList<T>(list.size + 1)
    out.add(item)
    for (e in list) if (e != item) out.add(e)
    return out
}
