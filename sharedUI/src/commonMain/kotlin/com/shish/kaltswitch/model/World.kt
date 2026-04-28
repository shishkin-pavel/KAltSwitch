package com.shish.kaltswitch.model

/** Live system state — what we know about running apps and their windows right now. */
data class World(
    val log: ActivationLog,
    val runningApps: Map<Int, App>,
    val windowsByPid: Map<Int, List<Window>>,
) {
    /** Order [pid]'s windows: activation-recency first, then the rest in input order. */
    fun orderedWindows(pid: Int): List<Window> {
        val available = windowsByPid[pid].orEmpty()
        if (available.isEmpty()) return emptyList()
        val byId = available.associateBy { it.id }

        val seen = HashSet<WindowId>(available.size)
        val result = ArrayList<Window>(available.size)

        for (winId in log.windowOrder(pid)) {
            val w = byId[winId] ?: continue
            if (seen.add(winId)) result.add(w)
        }
        for (w in available) if (seen.add(w.id)) result.add(w)
        return result
    }
}

data class AppEntry(
    val app: App,
    val windows: List<Window>,
) {
    val hasWindows: Boolean get() = windows.isNotEmpty()
}

/** Frozen view of the world at switcher-open time. */
data class SwitcherSnapshot(
    val withWindows: List<AppEntry>,
    val windowless: List<AppEntry>,
) {
    val all: List<AppEntry> = withWindows + windowless

    companion object {
        val Empty = SwitcherSnapshot(emptyList(), emptyList())
    }
}

fun World.snapshot(): SwitcherSnapshot {
    val placedPids = HashSet<Int>()
    val withWindows = ArrayList<AppEntry>()

    // 1. Activation-recency-ordered apps that still have windows.
    for (pid in log.appOrder()) {
        if (pid in placedPids) continue
        val app = runningApps[pid] ?: continue
        val windows = orderedWindows(pid)
        if (windows.isEmpty()) continue
        placedPids.add(pid)
        withWindows.add(AppEntry(app, windows))
    }

    // 2. Running apps with windows but no activation history yet (e.g. just-launched).
    for ((pid, app) in runningApps) {
        if (pid in placedPids) continue
        val windows = orderedWindows(pid)
        if (windows.isEmpty()) continue
        placedPids.add(pid)
        withWindows.add(AppEntry(app, windows))
    }

    // 3. Windowless apps go behind the separator, alphabetical for visual stability.
    val windowless = runningApps.values
        .asSequence()
        .filter { it.pid !in placedPids }
        .sortedBy { it.name.lowercase() }
        .map { AppEntry(it, emptyList()) }
        .toList()

    return SwitcherSnapshot(withWindows, windowless)
}
