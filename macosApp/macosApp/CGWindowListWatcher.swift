import AppKit
import ApplicationServices
import CoreGraphics
import ComposeAppMac

/// Cross-space window enumerator. Bridges `CGWindowListCopyWindowInfo`
/// — a public CoreGraphics call that reports windows regardless of which
/// Mission Control space they live on — into the Kotlin `WorldStore` as
/// "phantom" `Window` rows alongside the AX-derived ones.
///
/// **Why this exists.** macOS silently filters AX's `kAXWindowsAttribute`
/// to the *current* space, so a per-app `AxAppWatcher` only ever sees
/// windows on the user's active space; everything else is invisible
/// even though the app process is alive and watched. This source covers
/// the gap so the switcher can surface windows on other spaces.
///
/// **What we get vs. AX.** CG gives us `CGWindowID`, owner pid, raw
/// `kCGWindowName` (often empty — see below), bounds, and a handful of
/// metadata fields that AX doesn't expose: `kCGWindowIsOnscreen`,
/// `kCGWindowLayer`, `kCGWindowAlpha`, `kCGWindowOwnerName`. Those CG-only
/// fields are surfaced on the [Window] model so user-editable
/// `FilteringRules` can match against them. AX is the richer source for
/// everything else (role/subrole/focused/main/minimised, a live AX
/// element for actions); `WorldStore.replacePhantomWindows` de-duplicates
/// against AX entries by `cgWindowId`, so a window visible to both sources
/// keeps its AX-side fidelity.
///
/// **Filtering policy: zero hardcode.** Every keep/drop decision lives in
/// `FilteringRules` (commonMain). This watcher emits a [Window] for every
/// CG entry it sees. Even our own pid is forwarded — the Inspector and
/// Settings windows are legitimate switcher targets, and the switcher
/// overlay panel itself sits on a non-zero CG layer (`.popUpMenu`,
/// `kCGCursorWindowLevelKey` and friends), which the
/// `default-hide-cg-overlay-layer` rule already handles. System overlays,
/// tiny tooltip popovers, zero-alpha stubs, hidden helpers — all filtered
/// downstream via predicates against `cgLayer` / `width` / `height` /
/// `cgAlpha` / `ownerName` / `isOnscreen` / `isOnVisibleSpace`. See
/// `model/Filters.kt::SeedRules` for the shipped defaults.
///
/// **`kCGWindowName` and Screen Recording.** On macOS 10.15+ this field
/// only carries the real title when the app is granted Screen Recording
/// (i.e. `kCGWindowListOptionAll` is filtered for privacy). KAltSwitch
/// does not currently request that permission, so most titles arrive
/// empty; the UI's `effectiveWindowTitle` already substitutes the app
/// name when the window title is blank.
///
/// **Triggers.** The enumerator is pull-driven — there is no public
/// "window-list-changed" notification. We re-scan on three triggers:
/// process start, `activeSpaceDidChangeNotification`, and the start
/// of every switcher session. That's enough to keep the merged view
/// correct in practice without burning a polling loop.
///
/// **Verbose first-pass dump.** `verbosePassDone` gates a one-off
/// per-entry log on the first refresh; every subsequent refresh emits
/// the always-on `[cgwl/keep]` line for each emitted phantom so the
/// log tail always answers "what is the switcher currently surfacing
/// for app X". Kept in tree (not deleted once stable) because it's the
/// fastest path back into the problem when a phantom looks wrong.
final class CGWindowListWatcher {
    private let store: WorldStore
    /// Serial background queue for the heavy CG / CGS syscalls inside
    /// `refresh()`. Single in-flight guarantee (serial queue + the
    /// [refreshInFlight] coalescing flag) — overlapping refresh
    /// requests fold into the next iteration rather than queueing
    /// up, which is the right behaviour for an idempotent "snapshot
    /// the world" operation. Anchored at `userInitiated` so the
    /// 2-second-cadence timer in `AppRegistry` doesn't get scheduled
    /// behind low-priority background work.
    private let workQueue = DispatchQueue(label: "cgwl.refresh", qos: .userInitiated)
    /// Coalescing flag, owned by main. Read + flipped on main only —
    /// no lock needed. When `true` an off-main pass is already underway;
    /// caller's `refresh()` becomes a no-op (the in-flight pass will
    /// pick up the latest state when it reads from CG/CGS).
    private var refreshInFlight = false
    /// Touched only from [workQueue] — the queue is serial so no
    /// synchronisation is required.
    private var verbosePassDone = false
    /// Last refresh's cgWindowId → ownerName, kept off-main on
    /// [workQueue]. Used to log only the *diff* against the previous
    /// pass — listing added / removed entries. Replaces the previous
    /// always-on per-window `[cgwl/emit]` line, which on a typical
    /// desktop emitted ~280 log entries every 2 s and pushed steady-
    /// state log I/O into the 1–2 % CPU range.
    private var lastEmitMap: [UInt32: String] = [:]

    /// `cgWindowId → spaceIds` cache. The per-window
    /// `CGSCopySpacesForWindows` call is the largest CPU contributor in
    /// `refreshOnWorkQueue` (~250 syscalls per tick → ~50–250 ms of
    /// total wallclock per refresh). Window-to-space membership only
    /// changes when (a) the user drags a window between Mission Control
    /// spaces — rare, observable only via `activeSpaceDidChange` — or
    /// (b) the window is closed / re-opened, in which case the new
    /// cgWindowId means a cache miss anyway. Caching lets idle ticks
    /// reuse the result for every window already on file.
    ///
    /// Off-main-only — read/written exclusively from [workQueue], which
    /// is serial, so no synchronisation. Cleared by
    /// [invalidateSpaceIdsCache] (also marshalled onto [workQueue]).
    private var spaceIdsCache: [UInt32: [Int64]] = [:]

    init(store: WorldStore) {
        self.store = store
    }

    /// Phantom-id → CGWindowID map by pid. AppRegistry consults this
    /// from `commit(...)` when the per-pid AX watcher doesn't recognise
    /// the windowId — happens for phantom rows whose AX element doesn't
    /// exist on the current space. The cgWindowId fed into
    /// `bringAppToFront` lets the SkyLight `_SLPSSetFrontProcessWithOptions`
    /// path target the specific window and trust the WindowServer to
    /// switch the space implicitly. **Main-thread only** — writes
    /// happen in the `refresh()` completion hop after CG work finishes.
    private(set) var cgWindowIdByPidByPhantomId: [pid_t: [Int64: CGWindowID]] = [:]

    /// Schedule one enumeration pass. Returns immediately; the actual
    /// CG / CGS syscalls (one `CGWindowListCopyWindowInfo` plus a
    /// `CGSCopySpacesForWindows` per entry — ~250 on a typical desktop)
    /// run on [workQueue]. Only the final `store.replacePhantomWindows`
    /// push and the [cgWindowIdByPidByPhantomId] update land back on
    /// main, so callers (the cmd+tab hotkey path, the 2s background
    /// timer, switcher-session-start, space-change handler) never
    /// block on the main thread.
    ///
    /// Coalesced: an in-flight call absorbs concurrent invocations.
    /// Two refreshes 50 ms apart collapse to one pass — the freshest
    /// world the next pass observes is what reaches the store.
    func refresh() {
        guard !refreshInFlight else { return }
        refreshInFlight = true
        workQueue.async { [weak self] in
            self?.refreshOnWorkQueue()
        }
    }

    /// Drop the in-memory `spaceIdsCache`. Called by `AppRegistry` when
    /// the active Mission Control space switches (the only public signal
    /// that window-to-space membership may have shifted en masse). Hops
    /// onto [workQueue] so it can't race the cache lookups in
    /// `refreshOnWorkQueue`. Empty cache = next refresh repopulates
    /// it via direct CGS calls; one stale pass is the cost of the
    /// cache, paid only on actual space transitions.
    func invalidateSpaceIdsCache() {
        workQueue.async { [weak self] in
            self?.spaceIdsCache.removeAll()
        }
    }

    /// Cache-backed `spaceIdsFor`. Off-main only.
    private func cachedSpaceIds(_ cgWid: CGWindowID) -> [Int64] {
        if let cached = spaceIdsCache[cgWid] { return cached }
        let fetched = spaceIdsFor(cgWindowId: cgWid)
        spaceIdsCache[cgWid] = fetched
        return fetched
    }

    /// Body of the off-main refresh. All CG / CGS syscalls live here;
    /// no UIKit / store / kotlin-bridge calls (those need main). The
    /// final hop back to main does the store push and bookkeeping in
    /// one atomic batch so observers see a consistent state.
    private func refreshOnWorkQueue() {
        let options: CGWindowListOption = [.optionAll, .excludeDesktopElements]
        guard let raw = CGWindowListCopyWindowInfo(options, kCGNullWindowID) as? [[String: Any]] else {
            log("[cgwl] CGWindowListCopyWindowInfo returned nil")
            DispatchQueue.main.async { [weak self] in self?.refreshInFlight = false }
            return
        }

        // Compute the visible-space set once per refresh so every phantom
        // gets a consistent `isOnVisibleSpace` reading. Empty list means
        // the private CGS API was unhappy; in that case we set the flag
        // to `false` on every entry and rely on rules / the AX path to
        // do the right thing (the existing `maskOffSpace` also treats
        // empty as "feature unavailable" and skips its filter).
        let visibleSpaceIds = Set(currentVisibleSpaceIds())

        var phantoms: [Window] = []
        var byPid: [pid_t: [Int64: CGWindowID]] = [:]
        var emitMap: [UInt32: String] = [:]
        let verbose = !verbosePassDone

        for entry in raw {
            // No own-pid recursion guard: Inspector and Settings are
            // legitimate switcher targets that happen to share our pid.
            // The switcher overlay panel itself runs on a non-zero CG
            // layer (`.popUpMenu`) so the `default-hide-cg-overlay-layer`
            // rule keeps it out of the switcher.
            guard
                let cgWid = (entry[kCGWindowNumber as String] as? NSNumber)?.uint32Value,
                let ownerPid = (entry[kCGWindowOwnerPID as String] as? NSNumber)?.int32Value
            else { continue }

            let ownerName = (entry[kCGWindowOwnerName as String] as? String) ?? ""
            let title = (entry[kCGWindowName as String] as? String) ?? ""
            let layer = (entry[kCGWindowLayer as String] as? NSNumber)?.intValue ?? -1
            let alpha = (entry[kCGWindowAlpha as String] as? NSNumber)?.doubleValue ?? 1.0
            let isOnscreen = (entry[kCGWindowIsOnscreen as String] as? NSNumber)?.boolValue ?? false
            let bounds = parseBounds(entry[kCGWindowBounds as String] as? [String: Any])
            let width = bounds.w ?? 0
            let height = bounds.h ?? 0
            let spaceIds = cachedSpaceIds(cgWid)
            let isOnVisibleSpace = !visibleSpaceIds.isDisjoint(with: spaceIds)

            let kotlinId = Int64(cgWid)
            byPid[ownerPid, default: [:]][kotlinId] = cgWid
            emitMap[cgWid] = ownerName

            if verbose {
                logVerbose(
                    cgWid: cgWid,
                    ownerPid: ownerPid,
                    ownerName: ownerName,
                    title: title,
                    layer: layer,
                    alpha: alpha,
                    width: width,
                    height: height,
                    x: bounds.x,
                    y: bounds.y,
                    isOnscreen: isOnscreen,
                    isOnVisibleSpace: isOnVisibleSpace
                )
            }

            phantoms.append(Window(
                id: kotlinId,
                pid: ownerPid,
                title: title,
                role: nil,
                subrole: nil,
                isMinimized: false,
                isFullscreen: false,
                isFocused: false,
                isMain: false,
                x: bounds.x.map { KotlinDouble(value: $0) },
                y: bounds.y.map { KotlinDouble(value: $0) },
                width: bounds.w.map { KotlinDouble(value: $0) },
                height: bounds.h.map { KotlinDouble(value: $0) },
                children: [],
                spaceIds: spaceIds.map { KotlinLong(value: $0) },
                cgWindowId: KotlinLong(value: kotlinId),
                isOnscreen: KotlinBoolean(value: isOnscreen),
                isOnVisibleSpace: KotlinBoolean(value: isOnVisibleSpace),
                cgLayer: KotlinInt(value: Int32(layer)),
                cgAlpha: KotlinDouble(value: alpha),
                ownerName: ownerName,
                // Source bit is set by the store on `applyCgSnapshot` —
                // empty here keeps the Swift side ignorant of the
                // bookkeeping rules in commonMain.
                sources: Set<WindowSource>()
            ))
        }

        if verbose {
            verbosePassDone = true
            log("[cgwl] verbose first-pass dump complete")
        }

        // Drop cache entries for windows that are gone — keeps the cache
        // bounded as windows come and go without forcing a full
        // invalidation (which would re-pay the ~250 CGS syscalls).
        spaceIdsCache = spaceIdsCache.filter { emitMap[$0.key] != nil }

        // Diff vs the previous refresh — added / removed cgWindowIds.
        // Replaces the old per-emit log: signal lives only on actual
        // inventory mutations, not on every steady-state tick. Both
        // ends still log enough to debug "which window disappeared
        // when" from log tails alone (project convention — see
        // feedback_log_coverage memory).
        let added = emitMap.keys.filter { lastEmitMap[$0] == nil }
        let removed = lastEmitMap.keys.filter { emitMap[$0] == nil }
        for cgWid in added {
            log("[cgwl] +\(cgWid) \(emitMap[cgWid] ?? "?")")
        }
        for cgWid in removed {
            log("[cgwl] -\(cgWid) \(lastEmitMap[cgWid] ?? "?")")
        }
        lastEmitMap = emitMap

        // Hop back to main for the kotlin-bridge call and the bookkeeping
        // map update. Both are main-thread invariants (`WorldStore`
        // mutators are serialised through main, `cgWindowIdByPidByPhantomId`
        // is read from `AppRegistry.commit` on main). Refresh-in-flight
        // is cleared *after* the push so a synchronous follow-up call
        // doesn't double-trigger.
        let outCount = phantoms.count
        let pidCount = byPid.count
        let addedCount = added.count
        let removedCount = removed.count
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.cgWindowIdByPidByPhantomId = byPid
            self.store.applyCgSnapshot(allCgWindows: phantoms)
            self.refreshInFlight = false
            log("[cgwl] refreshed: emitted=\(outCount) pids=\(pidCount) +\(addedCount) -\(removedCount)")
        }
    }

    /// One-off detailed dump used on the very first refresh to help
    /// re-tune filters from real-world data. Output is one line per CG
    /// entry with every field the heuristic looks at. Disabled after
    /// the first call by `verbosePassDone`. Kept in tree because it's
    /// the fastest path back into the problem when a phantom looks wrong.
    private func logVerbose(
        cgWid: CGWindowID,
        ownerPid: pid_t,
        ownerName: String,
        title: String,
        layer: Int,
        alpha: Double,
        width: Double,
        height: Double,
        x: Double?,
        y: Double?,
        isOnscreen: Bool,
        isOnVisibleSpace: Bool
    ) {
        let titleTrunc = title.prefix(40)
        let nameTrunc = ownerName.prefix(28)
        let xs = x.map { String(format: "%.0f", $0) } ?? "?"
        let ys = y.map { String(format: "%.0f", $0) } ?? "?"
        log(String(
            format: "[cgwl/v] cgwid=%d pid=%d owner=%-28@ layer=%d alpha=%.2f onscreen=%@ onSpace=%@ pos=%@,%@ size=%.0fx%.0f title=%@",
            cgWid,
            ownerPid,
            String(nameTrunc) as CVarArg,
            layer,
            alpha,
            isOnscreen ? "1" : "0",
            isOnVisibleSpace ? "1" : "0",
            xs as CVarArg,
            ys as CVarArg,
            width,
            height,
            String(titleTrunc) as CVarArg
        ))
    }

    /// CG's `kCGWindowBounds` is a CFDictionary with X / Y / Width / Height
    /// as CGFloats. CG uses top-left-origin coordinates anchored to the
    /// primary screen; we pass them through unchanged because the AX-derived
    /// `Window.x/y` fields are *also* top-left-origin (read straight from
    /// `kAXPositionAttribute`), so the inspector and any debug overlay
    /// stay in one coordinate system.
    private func parseBounds(_ dict: [String: Any]?) -> (x: Double?, y: Double?, w: Double?, h: Double?) {
        guard let d = dict else { return (nil, nil, nil, nil) }
        return (
            (d["X"] as? NSNumber)?.doubleValue,
            (d["Y"] as? NSNumber)?.doubleValue,
            (d["Width"] as? NSNumber)?.doubleValue,
            (d["Height"] as? NSNumber)?.doubleValue
        )
    }
}
