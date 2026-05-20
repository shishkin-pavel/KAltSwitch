package com.shish.kaltswitch.model

/** Live system state — what we know about running apps and their windows right now. */
data class World(
    val log: ActivationLog,
    val runningApps: Map<Pid, App>,
    /** `null` (key absent) = AX info not yet known for this pid; empty list = knowingly windowless. */
    val windowsByPid: Map<Pid, List<Window>>,
    /** Sticky pin-target memory: for every window that currently matches some
     *  pinning rule, the WindowId of the root it was first pinned under. Set
     *  once at the moment a window starts matching (window creation, title
     *  change, or rule change) and only updated if that anchor stops being a
     *  valid non-matching root. See `applyPinning` for the consumer and
     *  `WorldStore` for the population path. Outer key is the owning pid;
     *  WindowIds aren't unique across pids in this model. */
    val pinAnchorByWindow: Map<Pid, Map<WindowId, WindowId>> = emptyMap(),
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
    /** Number of leading entries in [windows] (top-level only) that are
     *  filter-mode `Show`. The switcher overlay uses this to partition the
     *  visible top-level rows into "Show" and "Demote" buckets. Children of
     *  those roots are partitioned per-level via [demotedWindowIds] — each
     *  tree level has its own Show/Demote split. */
    val shownTopWindowCount: Int = windows.size,
    /** IDs of every window (root or nested child) the filter pipeline
     *  classified as `Demote`. Drives per-row visual demote cues and the
     *  per-level partitioning of children. An empty set means "treat every
     *  entry as Show", which matches callers that build an `AppEntry`
     *  directly without going through the filter pipeline. */
    val demotedWindowIds: Set<WindowId> = emptySet(),
) {
    val hasWindows: Boolean get() = windows.isNotEmpty()

    /** Arrow-key order: DFS pre-order over [windows]. Because
     *  `classifyWindow` already sorts children by mode (Show, then Demote)
     *  the pre-order naturally interleaves visit/demote sections per
     *  tree level — a pinned demoted child shows up right after its
     *  Show parent, matching the on-screen nesting. */
    val navigableWindows: List<Window>
        get() = buildList(estimatedNavigableSize()) {
            for (w in windows) flattenDfs(w, this)
        }

    /** cmd+` order: DFS pre-order over Show entries only. Demote entries
     *  are skipped together with their subtrees — once a window is demoted,
     *  the hot-key path stops descending into it even if some grandchild
     *  happens to be classified Show. Reachable via the arrow keys. */
    val shownNavigableWindows: List<Window>
        get() = buildList(estimatedNavigableSize()) {
            for (w in windows) flattenShown(w, this, demotedWindowIds)
        }

    /** Convenience for callers that just want the count cmd+` cycles
     *  through. Same as `shownNavigableWindows.size` — derived so it
     *  stays correct without callers having to thread the value through
     *  the snapshot pipeline. */
    val shownWindowCount: Int get() = shownNavigableWindows.size

    private fun estimatedNavigableSize(): Int = windows.size * 2
}

private fun flattenDfs(w: Window, out: MutableList<Window>) {
    out.add(w)
    for (c in w.children) flattenDfs(c, out)
}

private fun flattenShown(w: Window, out: MutableList<Window>, demoted: Set<WindowId>) {
    if (w.id in demoted) return
    out.add(w)
    for (c in w.children) flattenShown(c, out, demoted)
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

fun World.snapshot(pinning: PinningRules = PinningRules()): SwitcherSnapshot {
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
        val windows = applyPinning(app, orderedWindows(pid) ?: emptyList(), pinning)
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
        val windows = applyPinning(app, orderedWindows(pid) ?: emptyList(), pinning)
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

/**
 * Re-parent matching top-level windows of [app] as children of one of the
 * same app's non-matching roots. Inputs are the per-app top-level [roots]
 * (already recency-ordered). Returns the new roots; matching windows are
 * appended in recency order to their resolved parent's `children`.
 *
 * Pin behaviour:
 *  - If no rule matches any root, returns [roots] unchanged.
 *  - **Per-window sticky anchor.** Each matching window's anchor is taken
 *    from [World.pinAnchorByWindow] (populated by `WorldStore` at the
 *    moment the window first started matching). That choice survives later
 *    activation shuffles, so e.g. a Firefox PiP opened from window A keeps
 *    anchoring on A even after the user moves on to window B. The fallback
 *    only runs when no remembered anchor exists yet or the remembered one
 *    is no longer a valid non-matching root (the original window closed,
 *    or it has itself become a pin target).
 *  - **Fallback (no sticky entry).** Most recent non-matching root in
 *    `log.windowOrder(pid)`; if the log is silent on this app, the first
 *    non-matching root in input order. If every root matches and we have
 *    no recency hint, matching windows stay as roots — better to leak
 *    one un-pinned root than to hide a window entirely.
 *  - Children attached to a pinned window via AX are preserved.
 *  - Multiple pinned siblings can resolve to different anchors (e.g. two
 *    PiPs opened from two different FF windows). They land under their
 *    respective parents in the input recency order.
 */
internal fun World.applyPinning(app: App, roots: List<Window>, pinning: PinningRules): List<Window> {
    if (pinning.rules.isEmpty()) return roots
    if (roots.isEmpty()) return roots

    val matchingIds = LinkedHashSet<WindowId>()
    for (w in roots) if (pinning.matches(app, w)) matchingIds.add(w.id)
    if (matchingIds.isEmpty()) return roots

    val rootIds = roots.mapTo(HashSet()) { it.id }
    val stickyForPid = pinAnchorByWindow[app.pid].orEmpty()
    // Lazily resolved on first use — only one of the matching windows
    // typically needs the fallback per snapshot.
    val fallbackAnchor: WindowId? by lazy {
        log.windowOrder(app.pid).firstOrNull { it !in matchingIds && it in rootIds }
            ?: roots.firstOrNull { it.id !in matchingIds }?.id
    }

    val pinnedByAnchor = LinkedHashMap<WindowId, MutableList<Window>>()
    val placedMatchingIds = HashSet<WindowId>()
    for (w in roots) {
        if (w.id !in matchingIds) continue
        val sticky = stickyForPid[w.id]
        val anchor = if (sticky != null && sticky in rootIds && sticky !in matchingIds) {
            sticky
        } else {
            fallbackAnchor ?: continue   // no non-matching root anywhere → leave this one as a root
        }
        pinnedByAnchor.getOrPut(anchor) { ArrayList() }.add(w)
        placedMatchingIds.add(w.id)
    }
    if (pinnedByAnchor.isEmpty()) return roots   // nothing could be anchored

    return roots.mapNotNull { r ->
        val children = pinnedByAnchor[r.id]
        when {
            children != null -> r.copy(children = r.children + children)
            r.id in placedMatchingIds -> null   // moved under its anchor
            else -> r
        }
    }
}

/**
 * Reconcile [pinAnchorByWindow] for one pid against the freshest known window
 * list. Used by `WorldStore` whenever the pid's roots change (new window,
 * dropped window, title-change retitling) or the pinning rule set changes.
 *
 * Behaviour:
 *  - Drops every stored entry whose pinned window is no longer in [roots] or
 *    no longer matches any rule.
 *  - Keeps an existing entry as-is when its anchor is still a non-matching
 *    root of [roots] — that's the "sticky" property that fixes the user-
 *    reported bug where a PiP re-pinned to the latest active window.
 *  - For matching windows without a valid stored anchor (newly observed,
 *    newly matching after a title/rule change, or anchor disappeared),
 *    chooses the most recently activated non-matching root from [log], with
 *    a final fallback to input-order. Mirrors `applyPinning`'s fallback so
 *    a fresh snapshot agrees with what would have been picked anyway.
 *  - Returns a map where the pid key is dropped entirely when there's no
 *    matching window to remember — keeps the data structure compact.
 *
 * Pure function — does not look at [World] beyond what's passed in. The
 * caller's pre-update [log] is fine: window-list changes don't touch the
 * activation log, so its recency view is still current.
 */
internal fun reconcilePinAnchorsForPid(
    current: Map<Pid, Map<WindowId, WindowId>>,
    pid: Pid,
    app: App?,
    roots: List<Window>,
    log: ActivationLog,
    pinning: PinningRules,
): Map<Pid, Map<WindowId, WindowId>> {
    val empty: () -> Map<Pid, Map<WindowId, WindowId>> = {
        if (pid in current) current - pid else current
    }
    if (app == null || pinning.rules.isEmpty() || roots.isEmpty()) return empty()

    val matchingIds = LinkedHashSet<WindowId>()
    for (w in roots) if (pinning.matches(app, w)) matchingIds.add(w.id)
    if (matchingIds.isEmpty()) return empty()

    val rootIds = roots.mapTo(HashSet()) { it.id }
    val previous = current[pid].orEmpty()
    val fallbackAnchor: WindowId? by lazy {
        log.windowOrder(pid).firstOrNull { it !in matchingIds && it in rootIds }
            ?: roots.firstOrNull { it.id !in matchingIds }?.id
    }

    val next = HashMap<WindowId, WindowId>(matchingIds.size)
    for (wid in matchingIds) {
        val stored = previous[wid]
        val anchor = if (stored != null && stored in rootIds && stored !in matchingIds) {
            stored
        } else {
            fallbackAnchor ?: continue
        }
        next[wid] = anchor
    }
    return if (next.isEmpty()) empty() else current + (pid to next)
}
