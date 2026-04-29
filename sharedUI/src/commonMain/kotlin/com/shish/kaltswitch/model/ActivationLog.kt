package com.shish.kaltswitch.model

/** A single user-driven activation. `windowId == null` means an app-level activation
 * (we know an app got focus, but not which of its windows).
 *
 * No timestamp: ordering comes from insertion order (`record` prepends), and we
 * have no feature that asks "how long ago was X activated". If/when we do, add
 * a clock then. */
data class ActivationEvent(
    val pid: Int,
    val windowId: WindowId?,
)

/**
 * Single source of truth for "what was used most recently". Immutable, newest-first
 * event log. Display orderings (apps, windows-within-app) are derived projections.
 */
data class ActivationLog(val events: List<ActivationEvent> = emptyList()) {

    fun record(event: ActivationEvent): ActivationLog = copy(events = listOf(event) + events)

    /** Pids ordered newest-first by their most-recent activation. */
    fun appOrder(): List<Int> {
        val seen = LinkedHashSet<Int>()
        for (e in events) seen.add(e.pid)
        return seen.toList()
    }

    /** Window ids of [pid] ordered newest-first. App-level events (`windowId == null`) are skipped. */
    fun windowOrder(pid: Int): List<WindowId> {
        val seen = LinkedHashSet<WindowId>()
        for (e in events) {
            if (e.pid != pid) continue
            val wid = e.windowId ?: continue
            seen.add(wid)
        }
        return seen.toList()
    }
}
