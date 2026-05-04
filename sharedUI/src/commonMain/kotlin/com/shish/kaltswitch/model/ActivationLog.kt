package com.shish.kaltswitch.model

/** A single user-driven activation. `windowId == null` means an app-level activation
 * (we know an app got focus, but not which of its windows).
 *
 * No timestamp: ordering comes from insertion order (`record` prepends), and we
 * have no feature that asks "how long ago was X activated". If/when we do, add
 * a clock then. */
data class ActivationEvent(
    val pid: Pid,
    val windowId: WindowId?,
)

/** How many events to retain. Anything beyond no longer affects [appOrder] /
 *  [windowOrder] in practice (those derive newest-first deduped lists), so the
 *  cap is a memory-only knob. KAltSwitch is a long-running menubar app; without
 *  a cap, AX/NSWorkspace echoes can grow the log indefinitely. */
private const val DefaultMaxActivationEvents = 2_048

/**
 * Single source of truth for "what was used most recently". Immutable, newest-first
 * event log. Display orderings (apps, windows-within-app) are derived projections.
 *
 * The log is intentionally bounded ([DefaultMaxActivationEvents]) — only recent
 * recency matters; older entries are projected away by [appOrder] and
 * [windowOrder] anyway. `record` also collapses adjacent-equal events so a
 * Workspace echo immediately after our own synchronous commit doesn't double
 * up the head.
 */
data class ActivationLog(val events: List<ActivationEvent> = emptyList()) {

    fun record(
        event: ActivationEvent,
        maxEvents: Int = DefaultMaxActivationEvents,
    ): ActivationLog {
        if (events.firstOrNull() == event) return this
        val limit = maxEvents.coerceAtLeast(1)
        return copy(events = buildList(capacity = minOf(events.size + 1, limit)) {
            add(event)
            addAll(events.take(limit - 1))
        })
    }

    /** Drop every event for [pid]. Used when the app terminates so a future
     *  reused pid cannot inherit stale recency. */
    fun withoutPid(pid: Pid): ActivationLog =
        copy(events = events.filterNot { it.pid == pid })

    /** Drop every window-level event for ([pid], [windowId]). Used when a
     *  specific window goes away. App-level events for the pid are kept. */
    fun withoutWindow(pid: Pid, windowId: WindowId): ActivationLog =
        copy(events = events.filterNot { it.pid == pid && it.windowId == windowId })

    /** Keep app-level events for [pid] but drop every window-level event whose
     *  id isn't in [liveWindowIds]. Used by snapshot refreshes that report the
     *  full live window set in one go. */
    fun withoutMissingWindows(pid: Pid, liveWindowIds: Set<WindowId>): ActivationLog =
        copy(events = events.filterNot {
            it.pid == pid && it.windowId != null && it.windowId !in liveWindowIds
        })

    /** Pids ordered newest-first by their most-recent activation. */
    fun appOrder(): List<Pid> {
        val seen = LinkedHashSet<Pid>()
        for (e in events) seen.add(e.pid)
        return seen.toList()
    }

    /** Window ids of [pid] ordered newest-first. App-level events (`windowId == null`) are skipped. */
    fun windowOrder(pid: Pid): List<WindowId> {
        val seen = LinkedHashSet<WindowId>()
        for (e in events) {
            if (e.pid != pid) continue
            val wid = e.windowId ?: continue
            seen.add(wid)
        }
        return seen.toList()
    }
}
