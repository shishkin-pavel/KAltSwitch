package com.shish.kaltswitch.model

/** Live system state — what we know about running apps and their windows right now. */
data class World(
    val log: ActivationLog,
    val runningApps: Map<Pid, App>,
    /** `null` (key absent) = AX info not yet known for this pid; empty list = knowingly windowless. */
    val windowsByPid: Map<Pid, List<Window>>,
) {
    /** Returns ordered windows, or `null` if AX info is unknown for this pid. */
    fun orderedWindows(pid: Pid): List<Window>? {
        val available = windowsByPid[pid] ?: return null
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
    /** Number of leading entries in [windows] that are filter-mode `Show`. The
     *  rest are `Demote` (those wrap into the same list because the inspector
     *  renders them dimmed but adjacent). The hot-key navigation (cmd+`)
     *  cycles only within `[0, shownWindowCount-1]`; the arrow keys traverse
     *  the full list. Defaults to `windows.size` so callers that don't
     *  classify windows treat everything as Show. */
    val shownWindowCount: Int = windows.size,
) {
    val hasWindows: Boolean get() = windows.isNotEmpty()
}

/** Frozen view of the world at switcher-open time. */
data class SwitcherSnapshot(
    /** Apps that the inspector classifies as `Show`. */
    val withWindows: List<AppEntry>,
    /** Apps that the inspector classifies as `Demote` — rendered after the
     *  vertical separator. The hot-key path skips these; arrows visit them. */
    val windowless: List<AppEntry>,
) {
    val all: List<AppEntry> = withWindows + windowless

    /** Index just past the last [withWindows] entry inside [all] —
     *  i.e. `[0, shownAppCount-1]` is the Show range, `[shownAppCount, size)`
     *  is the Demote range. */
    val shownAppCount: Int get() = withWindows.size

    companion object {
        val Empty = SwitcherSnapshot(emptyList(), emptyList())
    }
}

fun World.snapshot(): SwitcherSnapshot {
    val placedPids = HashSet<Pid>()
    val withWindows = ArrayList<AppEntry>()

    // 1. Activation-recency-ordered apps. Includes both windowed AND known-
    //    windowless apps, as long as the pid has an entry in appOrder — the
    //    user just used the app, recency wins over the has-windows split.
    //    AX-unknown apps (windowsByPid[pid] == null) also land here with an
    //    empty windows list. The split-on-windows used to live in this loop
    //    too and routed every windowless-but-recently-active app to step 3
    //    (alphabetical), which sent apps the user was just using to the tail
    //    of the switcher — exactly the wrong direction.
    for (pid in log.appOrder) {
        if (pid in placedPids) continue
        val app = runningApps[pid] ?: continue
        val windows = orderedWindows(pid) ?: emptyList()
        placedPids.add(pid)
        withWindows.add(AppEntry(app, windows))
    }

    // 2. Running apps not in the log yet (just-launched, never focused). A
    //    just-launched windowed app belongs in front of the separator with a
    //    deterministic position; a just-launched windowless app has no
    //    recency to anchor on, so it joins step 3's alphabetical list.
    for ((pid, app) in runningApps) {
        if (pid in placedPids) continue
        if (windowsByPid[pid]?.isEmpty() == true) continue   // never-activated windowless → step 3
        val windows = orderedWindows(pid) ?: emptyList()
        placedPids.add(pid)
        withWindows.add(AppEntry(app, windows))
    }

    // 3. Never-activated windowless apps. Alphabetical for visual stability.
    val windowless = runningApps.values
        .asSequence()
        .filter { it.pid !in placedPids }
        .sortedBy { it.name.lowercase() }
        .map { AppEntry(it, emptyList()) }
        .toList()

    return SwitcherSnapshot(withWindows, windowless)
}
