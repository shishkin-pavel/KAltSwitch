import Foundation
import AppKit
import ApplicationServices
import ComposeAppMac

/// Per-app accessibility watcher. Spawns a dedicated thread that owns a CFRunLoop;
/// an `AXObserver` attached to that run loop fires on per-app and per-window AX
/// events. State changes are dispatched to main to update [WorldStore].
///
/// AeroSpace-style: AX work happens off-main, only state mutations cross over.
final class AxAppWatcher {
    let pid: pid_t
    private let store: WorldStore
    private let appElement: AXUIElement

    private var thread: Thread?
    private weak var runLoop: CFRunLoop?
    private var observer: AXObserver?

    /// AX windows we've already subscribed to, keyed by CFHash of the AXUIElement.
    private var perWindowSubscribed = Set<Int>()
    /// `CFHash(axUIElement) → CGWindowID` for windows we've subscribed
    /// to per-window AX notifications on. Populated in
    /// [subscribePerWindow] (the AXUIElement is alive then —
    /// `_AXUIElementGetWindow` answers correctly). Read by the
    /// `kAXUIElementDestroyed` handler: we can't resolve the CGWindowID
    /// after destruction (the AX server might be unresponsive on the
    /// dying proxy), so the cache is the only way to know which
    /// WindowServer window the destroyed AX element was proxying.
    private var cgWindowIdByAxHash: [Int: CGWindowID] = [:]
    /// Live AXUIElement references for every top-level window currently known,
    /// keyed by `CFHash(axWin)` (which is also the WindowId we publish to the
    /// store). Looked up on raise / commit to find the element to act on.
    private var windowsByHash: [Int: AXUIElement] = [:]

    /// Fired on every snapshot-style window-list refresh and on
    /// per-window destroyed events. AppRegistry uses this to re-poll the
    /// NSRunningApplication's `activationPolicy` — apps like Bitwarden
    /// flip back from `.regular` to `.accessory` when their last window
    /// closes, and that mutation has no workspace-level signal.
    var onWindowsChanged: ((pid_t) -> Void)?

    /// Narrower variant of [onWindowsChanged]: fires **only** when the
    /// set of `cgWindowId`s for this pid actually changed between two
    /// `refreshAllWindows` runs — i.e. an AX window was created or
    /// destroyed. Title / focus / minimise mutations don't trigger it.
    ///
    /// AppRegistry hooks this to kick a `CGWindowListWatcher.refresh()`
    /// so the CG-phantom map drops the just-closed window's entry
    /// immediately. Without it, the user sees the row briefly fall back
    /// to "app name" (because the AX-side window with the real title
    /// just disappeared and the phantom — title-less, courtesy of
    /// `kCGWindowName` requiring Screen Recording permission — takes
    /// its place until the 2 s background timer ticks).
    var onCgWindowIdSetChanged: ((pid_t) -> Void)?

    /// `cgWindowId`s observed by AX on the most recent
    /// `refreshAllWindows`. Compared against the freshly-built set to
    /// decide whether [onCgWindowIdSetChanged] should fire — empty on
    /// first refresh so the very first call always reports the
    /// transition `{} → currentSet`.
    private var lastCgWindowIdSet: Set<UInt32> = []

    /// CGWindowIDs we just authoritatively dropped via WindowServer
    /// probe in [handleAxElementDestroyed]. Maps cgwid → expiry
    /// (`Date().timeIntervalSince1970` deadline).
    ///
    /// After a real close, the app's `kAXWindowsAttribute` keeps
    /// listing the dead window for ~0.5–1.5 s before its own AX tree
    /// catches up. Anything that triggers a `refreshAllWindows` in
    /// that window — our own destroyed-handler fall-through (now
    /// removed), the OS-emitted `kAXFocusedWindowChangedNotification`
    /// that follows the close, a `kAXTitleChangedNotification` from
    /// some other window — would otherwise re-emit a stale
    /// `applyAxSnapshot` that re-adds the just-dropped window with
    /// `sources={AX}`, making the row reappear in the switcher for
    /// the full duration of the AX lag.
    ///
    /// The tombstone short-circuits that: `refreshAllWindows` and
    /// `pushWindowFromElement` filter out any window whose cgwid is
    /// in this map, so the drop sticks until the app's AX tree
    /// genuinely converges on "this window is gone".
    ///
    /// TTL is conservative: 2 s comfortably covers every app I've
    /// measured (Slack/Electron worst-case ≈ 1.2 s, native apps <
    /// 200 ms). CGWindowIDs aren't recycled by WindowServer within
    /// that window in practice, so the false-positive risk
    /// (suppressing a *new* AX window that happened to be assigned
    /// the same cgwid) is essentially zero.
    private var droppedCgWidTombstones: [CGWindowID: TimeInterval] = [:]
    private let droppedCgWidTombstoneTTL: TimeInterval = 2.0

    init(pid: pid_t, store: WorldStore) {
        self.pid = pid
        self.store = store
        self.appElement = AXUIElementCreateApplication(pid)
    }

    func start() {
        let t = Thread { [weak self] in self?.runLoopBody() }
        t.name = "ax-app-\(pid)"
        t.start()
        thread = t
    }

    func stop() {
        if let runLoop = runLoop {
            CFRunLoopStop(runLoop)
        }
    }

    /// Force a window snapshot + subscription retry. Useful when the system tells
    /// us this app just activated — its AX surface may have just become available.
    func requestRefresh() {
        DispatchQueue.main.async { [weak self] in self?.refreshAllWindows() }
    }

    private func runLoopBody() {
        runLoop = CFRunLoopGetCurrent()

        var observerRef: AXObserver?
        let err = AXObserverCreate(pid, axCallback, &observerRef)
        guard err == .success, let observerRef = observerRef else {
            NSLog("KAltSwitch: AXObserverCreate(pid=%d) failed: %d", pid, err.rawValue)
            return
        }
        observer = observerRef

        let selfPtr = Unmanaged.passUnretained(self).toOpaque()
        for notif in appNotifications {
            _ = AXObserverAddNotification(observerRef, appElement, notif as CFString, selfPtr)
        }

        CFRunLoopAddSource(
            CFRunLoopGetCurrent(),
            AXObserverGetRunLoopSource(observerRef),
            .defaultMode
        )

        DispatchQueue.main.async { [weak self] in self?.refreshAllWindows() }

        CFRunLoopRun()
    }

    /// Called from C `axCallback` on this watcher's thread. Marshals to main.
    fileprivate func receive(notification: CFString, element: AXUIElement) {
        let name = notification as String
        DispatchQueue.main.async { [weak self] in
            self?.handle(name: name, element: element)
        }
    }

    private func handle(name: String, element: AXUIElement) {
        // Three families of events:
        //   - app/window-focus changes → re-snapshot windows + re-sync the
        //     authoritative "who's active" pointers from the system. Goes
        //     through `syncActiveStateFromSystem` which is the sole writer of
        //     activation events, so log ordering and active-pointer highlight
        //     can never drift apart.
        //   - window creation → subscribe to per-window notifications, then
        //     re-snapshot.
        //   - per-window mutations (title/minimize/etc.) → upsert that one
        //     window without re-reading the whole list.
        // App hidden/shown is handled by NSWorkspace.didHide/didUnhide on the
        // AppRegistry side, so we don't subscribe to those here.
        switch name {
        case kAXUIElementDestroyedNotification as String:
            handleAxElementDestroyed(element)

        case kAXApplicationActivatedNotification as String,
             kAXMainWindowChangedNotification as String,
             kAXFocusedWindowChangedNotification as String:
            refreshAllWindows()
            syncActiveStateFromSystem(store: store)

        case kAXWindowCreatedNotification as String:
            subscribePerWindow(element)
            refreshAllWindows()

        case kAXTitleChangedNotification as String,
             kAXWindowMiniaturizedNotification as String,
             kAXWindowDeminiaturizedNotification as String,
             kAXWindowResizedNotification as String,
             kAXWindowMovedNotification as String:
            pushWindowFromElement(element)

        default:
            break
        }
    }

    // MARK: - Window queries

    /// Fast-path response to `kAXUIElementDestroyedNotification`.
    ///
    /// The notification fires when the *AX-server-side proxy* dies, which
    /// is **not** the same thing as the WindowServer destroying the
    /// underlying window. Apps recycle their AX trees on all sorts of
    /// transitions (fullscreen toggle, Slack / Electron AX-state resets,
    /// resume-from-AppNap) and emit destroyed events for windows that
    /// are still very much alive.
    ///
    /// To disambiguate without paying the latency of a full
    /// `refreshAllWindows` + off-main CG refresh round-trip (~80–150 ms
    /// total), we:
    /// 1. Resolve the destroyed AX element's `cgWindowId` from
    ///    [cgWindowIdByAxHash] — cached at subscribe time, since
    ///    `_AXUIElementGetWindow` can stall on the dying proxy.
    /// 2. Ask the WindowServer directly via [aliveCgWindowIds] (one
    ///    Mach IPC, sub-millisecond) whether that one `cgWindowId` is
    ///    still alive.
    /// 3. If gone → call the store's [WorldStore.dropWindowsByCgWindowIds]
    ///    so the row disappears in the very next render — no waiting for
    ///    the background CG poll. Plant a tombstone (see
    ///    [droppedCgWidTombstones]) so any AX snapshot that runs in the
    ///    next ~2 s and still lists the dead window can't re-add it.
    /// 4. If alive → AX-recycle case. Trigger `refreshAllWindows` so
    ///    the recycled AXUIElement re-attaches.
    /// 5. Either case → `syncActiveStateFromSystem` to keep the
    ///    active-pointer coherent (a destroyed focused window must
    ///    clear that pointer).
    ///
    /// We do **not** call `refreshAllWindows` on the agrees-drop
    /// path. The drop is authoritative; the surviving windows don't
    /// need an immediate re-snapshot just because one of their
    /// siblings died, and forcing one was the bug — the app's
    /// `kAXWindowsAttribute` is stale for ~1 s after a close, so a
    /// refresh inside that window would re-add the just-dropped
    /// window with `sources={AX}` (see `droppedCgWidTombstones`
    /// docstring for details).
    private func handleAxElementDestroyed(_ element: AXUIElement) {
        let axHash = Int(CFHash(element))
        let destroyedCgWid = cgWindowIdByAxHash.removeValue(forKey: axHash)
        if let cgWid = destroyedCgWid {
            // Ask WindowServer about exactly this one window. Single-element
            // batch keeps the cost minimal and the diagnostic noise quiet
            // (one `[ax/destroy]` line per actual close vs N lines for
            // app-wide quit, which the subsequent kAXUIElementDestroyed
            // events also each trigger one of).
            let alive = aliveCgWindowIds([cgWid])
            if !alive.contains(cgWid) {
                log("[ax/destroy] pid=\(pid) cgwid=\(cgWid) → drop (WindowServer agrees)")
                store.dropWindowsByCgWindowIds(
                    pid: pid,
                    cgWindowIds: [KotlinLong(value: Int64(cgWid))]
                )
                tombstoneDroppedCgWid(cgWid)
                syncActiveStateFromSystem(store: store)
                return
            }
            // Window is still alive in WindowServer — AX is just
            // recycling its proxy. Don't drop; the imminent
            // refreshAllWindows + kAXWindowCreated path will
            // re-attach a fresh AX element.
            log("[ax/destroy] pid=\(pid) cgwid=\(cgWid) → AX-recycle (window alive)")
        }
        // Either the AX-recycle case (window alive in WindowServer)
        // or the no-cached-cgwid case (rare — destroyed-event for an
        // element we never resolved a cgwid for). In both, the
        // standard full-refresh path is the right thing: pick up the
        // recycled AXUIElement / reconcile the AX snapshot, then
        // refresh active pointers.
        refreshAllWindows()
        syncActiveStateFromSystem(store: store)
    }

    /// Plant a tombstone for `cgWid`. See [droppedCgWidTombstones].
    private func tombstoneDroppedCgWid(_ cgWid: CGWindowID) {
        droppedCgWidTombstones[cgWid] = Date().timeIntervalSince1970 + droppedCgWidTombstoneTTL
    }

    /// Currently-live tombstones; also prunes expired entries. The
    /// dictionary is short — at most a handful of entries at any one
    /// time (only windows closed in the last 2 s) — so the
    /// rebuild-on-read pattern is cheaper than a separate timer.
    private func liveDroppedCgWidTombstones() -> Set<CGWindowID> {
        let now = Date().timeIntervalSince1970
        droppedCgWidTombstones = droppedCgWidTombstones.filter { $0.value > now }
        return Set(droppedCgWidTombstones.keys)
    }

    private func refreshAllWindows() {
        let topLevel = (readAttribute(appElement, kAXWindowsAttribute as String) as? [AXUIElement]) ?? []
        let appHash = Int(CFHash(appElement))
        // AX occasionally returns the same AXUIElement twice in kAXWindows, and
        // CFHash collisions are theoretically possible — keep the last value
        // instead of trapping on duplicates.
        let byHash: [Int: AXUIElement] = Dictionary(
            topLevel.map { (Int(CFHash($0)), $0) },
            uniquingKeysWith: { _, new in new }
        )
        windowsByHash = byHash

        // For each "top-level" window, see whether its AXParent is another top-level
        // window. Sheets/dialogs/floating panels often appear in app's kAXWindows even
        // though they logically belong to another window — use AXParent to relink.
        var childrenOfParent: [Int: [AXUIElement]] = [:]
        var attachedAsChild = Set<Int>()
        for win in topLevel {
            let myHash = Int(CFHash(win))
            guard let parent = readAttributeAsElement(win, kAXParentAttribute as String) else { continue }
            let parentHash = Int(CFHash(parent))
            guard parentHash != appHash, parentHash != myHash, byHash[parentHash] != nil else { continue }
            childrenOfParent[parentHash, default: []].append(win)
            attachedAsChild.insert(myHash)
        }

        let roots = topLevel.filter { !attachedAsChild.contains(Int(CFHash($0))) }
        var out: [Window] = []
        for axWin in roots {
            if let win = buildWindowTree(axWin, childrenOfParent: childrenOfParent) {
                out.append(win)
            }
        }
        // Filter out windows whose cgwid we just authoritatively
        // dropped — see [droppedCgWidTombstones]. The app's AX tree
        // takes up to ~1.5 s to catch up after a close, so without
        // this filter `kAXFocusedWindowChangedNotification` (always
        // fired by the OS after a close) would re-add the dead
        // window via `applyAxSnapshot`.
        let tomb = liveDroppedCgWidTombstones()
        if !tomb.isEmpty {
            let kept = out.filter { win in
                guard let cg = win.cgWindowId?.int64Value else { return true }
                let cgU32 = UInt32(truncatingIfNeeded: cg)
                if tomb.contains(cgU32) {
                    log("[ax/snapshot] pid=\(pid) cgwid=\(cgU32) suppressed (tombstoned)")
                    return false
                }
                return true
            }
            out = kept
        }
        store.applyAxSnapshot(pid: pid, axWindows: out)
        // Notify the registry: the windowed/windowless transition may have
        // shifted activationPolicy (Bitwarden et al. flip back to .accessory
        // when their last window closes — no workspace event for that).
        onWindowsChanged?(pid)

        // Did the actual *set* of cgWindowIds change (not just attributes)?
        // Walk the tree, collect every non-nil cgWindowId, compare. AX
        // emits refreshAllWindows on every notification — title change,
        // focus change, AXMain shift — so this check is what keeps us
        // from kicking a CG refresh on each of those.
        let newSet = collectCgWindowIds(out)
        if newSet != lastCgWindowIdSet {
            lastCgWindowIdSet = newSet
            onCgWindowIdSetChanged?(pid)
        }
    }

    /// Recursive cgWindowId harvest over a [Window] tree. Skips null
    /// entries (`_AXUIElementGetWindow` occasionally fails) — they
    /// can't contribute to the dedup decision anyway. Walks children
    /// because AX puts sheets / drawers / popovers there and they
    /// have their own CGWindowIDs.
    private func collectCgWindowIds(_ windows: [Window]) -> Set<UInt32> {
        var out: Set<UInt32> = []
        func visit(_ w: Window) {
            if let cg = w.cgWindowId?.int64Value, cg != 0 {
                out.insert(UInt32(truncatingIfNeeded: cg))
            }
            for c in w.children { visit(c) }
        }
        for w in windows { visit(w) }
        return out
    }

    /// Build a Window with its children = (typed-attribute children) + (top-level
    /// siblings whose AXParent points back to us).
    private func buildWindowTree(_ axWin: AXUIElement, childrenOfParent: [Int: [AXUIElement]]) -> Window? {
        guard let base = makeWindow(from: axWin) else { return nil }
        let extras = (childrenOfParent[Int(CFHash(axWin))] ?? [])
            .compactMap { buildWindowTree($0, childrenOfParent: childrenOfParent) }
        if extras.isEmpty { return base }
        return base.doCopy(
            id: base.id,
            pid: base.pid,
            title: base.title,
            role: base.role,
            subrole: base.subrole,
            isMinimized: base.isMinimized,
            isFullscreen: base.isFullscreen,
            isFocused: base.isFocused,
            isMain: base.isMain,
            x: base.x,
            y: base.y,
            width: base.width,
            height: base.height,
            children: base.children + extras,
            spaceIds: base.spaceIds,
            cgWindowId: base.cgWindowId,
            isOnscreen: base.isOnscreen,
            isOnVisibleSpace: base.isOnVisibleSpace,
            cgLayer: base.cgLayer,
            cgAlpha: base.cgAlpha,
            ownerName: base.ownerName,
            sources: base.sources
        )
    }

    private func pushWindowFromElement(_ axWin: AXUIElement) {
        guard let win = makeWindow(from: axWin) else { return }
        // Same tombstone gate as `refreshAllWindows`. The app can
        // emit per-window AX events (title change, miniaturize) on
        // a window whose `kAXUIElementDestroyed` we just processed
        // — its AX-server side stays alive briefly after the row
        // disappears from `kAXWindowsAttribute`.
        if let cg = win.cgWindowId?.int64Value {
            let cgU32 = UInt32(truncatingIfNeeded: cg)
            if liveDroppedCgWidTombstones().contains(cgU32) {
                log("[ax/upsert] pid=\(pid) cgwid=\(cgU32) suppressed (tombstoned)")
                return
            }
        }
        store.upsertAxWindow(window: win)
    }

    /// Collect window-like children attached to a window. macOS doesn't have one
    /// canonical attribute, so we try several:
    ///  - AXSheets, AXDrawers (typed list attributes when supported)
    ///  - AXChildWindows (non-standard, populated by some apps)
    ///  - kAXChildrenAttribute filtered by role — sheets often appear in there
    ///    rather than under a typed attribute.
    private func makeChildren(of axWin: AXUIElement) -> [Window] {
        var seen = Set<Int>()
        var raw: [AXUIElement] = []

        for attr in childWindowAttrs {
            guard let elements = readAttribute(axWin, attr) as? [AXUIElement] else { continue }
            for el in elements {
                let key = Int(CFHash(el))
                if seen.insert(key).inserted { raw.append(el) }
            }
        }

        if let allChildren = readAttribute(axWin, kAXChildrenAttribute as String) as? [AXUIElement] {
            for child in allChildren {
                let role = (readAttribute(child, kAXRoleAttribute as String) as? String) ?? ""
                guard windowLikeRoles.contains(role) else { continue }
                let key = Int(CFHash(child))
                if seen.insert(key).inserted { raw.append(child) }
            }
        }

        return raw.compactMap { makeWindow(from: $0) }
    }

    private func subscribePerWindow(_ axWin: AXUIElement) {
        let key = Int(CFHash(axWin))
        guard !perWindowSubscribed.contains(key) else { return }
        guard let observer = observer else { return }
        let selfPtr = Unmanaged.passUnretained(self).toOpaque()
        for notif in windowNotifications {
            _ = AXObserverAddNotification(observer, axWin, notif as CFString, selfPtr)
        }
        perWindowSubscribed.insert(key)
        // Cache cgWindowId for the destroyed-handler's fast-path. Resolved
        // here because the AX element is alive — `_AXUIElementGetWindow`
        // on a destroyed element can stall or return stale results.
        var cgWid: CGWindowID = 0
        if _AXUIElementGetWindow(axWin, &cgWid) == .success, cgWid != 0 {
            cgWindowIdByAxHash[key] = cgWid
        }
    }

    private func makeWindow(from axWin: AXUIElement) -> Window? {
        // Role / subrole values come from AX with the "AX" prefix
        // ("AXWindow", "AXStandardWindow", "AXSheet"). We *don't* strip
        // the prefix on the way in — what AX returns is what the user
        // sees in the inspector and what they type into a
        // RolePredicate(Eq, "AXWindow") rule. Better to expose the real
        // names than to silently rewrite them.
        let role = readAttribute(axWin, kAXRoleAttribute as String) as? String
        // Reject anything that isn't a real window-like element. AX
        // notifications subscribed on a window bubble — when a child
        // AXStaticText / AXImage changes, kAXTitleChanged fires on our
        // subscribed window *with `element` set to the child*. Without
        // this guard, `pushWindowFromElement` would upsert that child
        // as a "window" of the app (role: AXStaticText). Slack hits
        // this constantly because its message rows are AXStaticText
        // whose AXTitle is the message body, which changes on every
        // edit / scroll / mention update.
        guard let role, windowLikeRoles.contains(role) else { return nil }
        subscribePerWindow(axWin)
        let title = (readAttribute(axWin, kAXTitleAttribute as String) as? String) ?? ""
        let subrole = readAttribute(axWin, kAXSubroleAttribute as String) as? String
        let isMin = (readAttribute(axWin, kAXMinimizedAttribute as String) as? Bool) ?? false
        // kAXFullscreenAttribute isn't a public constant; the underlying name is "AXFullScreen".
        let isFs = (readAttribute(axWin, "AXFullScreen") as? Bool) ?? false
        let isFocused = (readAttribute(axWin, kAXFocusedAttribute as String) as? Bool) ?? false
        let isMain = (readAttribute(axWin, kAXMainAttribute as String) as? Bool) ?? false

        var x: KotlinDouble? = nil
        var y: KotlinDouble? = nil
        var w: KotlinDouble? = nil
        var h: KotlinDouble? = nil
        if let posVal = readAttribute(axWin, kAXPositionAttribute as String), CFGetTypeID(posVal as CFTypeRef) == AXValueGetTypeID() {
            var p = CGPoint.zero
            AXValueGetValue(posVal as! AXValue, .cgPoint, &p)
            x = KotlinDouble(value: Double(p.x))
            y = KotlinDouble(value: Double(p.y))
        }
        if let sizeVal = readAttribute(axWin, kAXSizeAttribute as String), CFGetTypeID(sizeVal as CFTypeRef) == AXValueGetTypeID() {
            var s = CGSize.zero
            AXValueGetValue(sizeVal as! AXValue, .cgSize, &s)
            w = KotlinDouble(value: Double(s.width))
            h = KotlinDouble(value: Double(s.height))
        }

        let id = Int64(CFHash(axWin))
        let children = makeChildren(of: axWin)
        // Resolve CGWindowID then ask CGS which Mission Control space(s) the
        // window currently belongs to. Empty list on failure — the
        // classifier treats that as "no space data available, skip the
        // current-space filter for this window" so a transient AX failure
        // doesn't make a window vanish from the switcher.
        //
        // The CGWindowID is now also persisted on the Kotlin Window model
        // (`cgWindowId`) so phantom rows from the cross-space enumerator
        // (CGWindowListWatcher) can be de-duplicated against AX rows by
        // the same key.
        var cgWid: CGWindowID = 0
        let spaceIds: [Int64]
        let cgWindowId: KotlinLong?
        if _AXUIElementGetWindow(axWin, &cgWid) == .success, cgWid != 0 {
            spaceIds = spaceIdsFor(cgWindowId: cgWid)
            cgWindowId = KotlinLong(value: Int64(cgWid))
        } else {
            spaceIds = []
            cgWindowId = nil
        }
        return Window(
            id: id,
            pid: pid,
            title: title,
            role: role,
            subrole: subrole,
            isMinimized: isMin,
            isFullscreen: isFs,
            isFocused: isFocused,
            isMain: isMain,
            x: x,
            y: y,
            width: w,
            height: h,
            children: children,
            spaceIds: spaceIds.map { KotlinLong(value: $0) },
            cgWindowId: cgWindowId,
            // CG-only fields stay nil on AX-derived rows. The CG enumerator
            // owns these — see CGWindowListWatcher.
            isOnscreen: nil,
            isOnVisibleSpace: nil,
            cgLayer: nil,
            cgAlpha: nil,
            ownerName: nil,
            // Source bit is set by the store on every mutator (`applyAxSnapshot`
            // / `upsertAxWindow` add `WindowSource.AX`). Passing empty here
            // keeps the Swift side ignorant of source-bookkeeping rules.
            sources: Set<WindowSource>()
        )
    }

    // MARK: - Raise / commit (called from main, from the SwitcherController bridge)

    /// `kAXRaiseAction` raises a window to the front *of its app* without making
    /// the app frontmost. Used for preview-on-hover; doesn't fire
    /// `didActivateApplicationNotification` so it doesn't pollute history.
    @discardableResult
    func raiseWindow(windowId: Int64) -> Bool {
        guard let el = windowsByHash[Int(windowId)] else { return false }
        return AXUIElementPerformAction(el, kAXRaiseAction as CFString) == .success
    }

    /// Make this window the app's main window. Pair this with
    /// [bringAppToFront] (SkyLight CGS path) to bring the app + this specific
    /// window forward. Used on switcher-commit.
    @discardableResult
    func makeWindowMain(windowId: Int64) -> Bool {
        guard let el = windowsByHash[Int(windowId)] else { return false }
        let setMain = AXUIElementSetAttributeValue(el, kAXMainAttribute as CFString, kCFBooleanTrue)
        let raise = AXUIElementPerformAction(el, kAXRaiseAction as CFString)
        return setMain == .success || raise == .success
    }

    // MARK: - Switcher actions on a specific window

    /// Press the window's close button (`kAXCloseButtonAttribute → kAXPressAction`).
    /// Same effect as the user clicking the red traffic light or hitting
    /// cmd+W in the target app. Returns `false` if the window has no close
    /// button (some kAXSheet / kAXSystemDialog windows don't), the press
    /// failed, or the AX id is unknown to this watcher.
    @discardableResult
    func closeWindow(windowId: Int64) -> Bool {
        guard let el = windowsByHash[Int(windowId)] else { return false }
        guard let btn = readAttributeAsElement(el, kAXCloseButtonAttribute as String) else {
            NSLog("KAltSwitch: closeWindow(pid=%d, wid=%lld) — no AXCloseButton", pid, windowId)
            return false
        }
        return AXUIElementPerformAction(btn, kAXPressAction as CFString) == .success
    }

    /// Toggle `kAXMinimizedAttribute` on the window. AX exposes the attribute
    /// as a Bool; we read the current value to flip it. Reads fall back to
    /// `false` so a brand-new window with no minimized attribute set is
    /// treated as "not minimized" → the toggle minimizes it.
    @discardableResult
    func toggleMinimize(windowId: Int64) -> Bool {
        guard let el = windowsByHash[Int(windowId)] else { return false }
        let current = (readAttribute(el, kAXMinimizedAttribute as String) as? Bool) ?? false
        let target: CFBoolean = if current { kCFBooleanFalse } else { kCFBooleanTrue }
        return AXUIElementSetAttributeValue(el, kAXMinimizedAttribute as CFString, target) == .success
    }

    /// Toggle `AXFullScreen` on the window. Note: the constant is
    /// `kAXFullscreenAttribute` in some headers but the underlying ObjC
    /// string is `"AXFullScreen"` — we use the literal because the public
    /// constant isn't always available across SDK versions.
    @discardableResult
    func toggleFullscreen(windowId: Int64) -> Bool {
        guard let el = windowsByHash[Int(windowId)] else { return false }
        let current = (readAttribute(el, "AXFullScreen") as? Bool) ?? false
        let target: CFBoolean = if current { kCFBooleanFalse } else { kCFBooleanTrue }
        return AXUIElementSetAttributeValue(el, "AXFullScreen" as CFString, target) == .success
    }

    /// Resolve the window's CGWindowID via the private `_AXUIElementGetWindow`
    /// API. The CGS focus call (`_SLPSSetFrontProcessWithOptions`) operates on
    /// CGWindowIDs, not AXUIElements, so we need this conversion.
    func cgWindowId(forAxWindowId windowId: Int64) -> CGWindowID? {
        guard let el = windowsByHash[Int(windowId)] else { return nil }
        var cgWid: CGWindowID = 0
        let err = _AXUIElementGetWindow(el, &cgWid)
        guard err == .success else {
            NSLog("KAltSwitch: _AXUIElementGetWindow(pid=%d, ax=%lld) failed: %d",
                  pid, windowId, err.rawValue)
            return nil
        }
        return cgWid
    }

    private func readAttribute(_ element: AXUIElement, _ attribute: String) -> Any? {
        var value: AnyObject?
        let err = AXUIElementCopyAttributeValue(element, attribute as CFString, &value)
        guard err == .success else { return nil }
        return value
    }

    private func readAttributeAsElement(_ element: AXUIElement, _ attribute: String) -> AXUIElement? {
        guard let value = readAttribute(element, attribute) else { return nil }
        guard CFGetTypeID(value as CFTypeRef) == AXUIElementGetTypeID() else { return nil }
        return (value as! AXUIElement)
    }
}

private let childWindowAttrs: [String] = [
    "AXSheets",
    "AXDrawers",
    "AXChildWindows",
]

private let windowLikeRoles: Set<String> = [
    "AXWindow",
    "AXSheet",
    "AXDrawer",
    "AXSystemDialog",
    "AXPopover",
    "AXFloatingWindow",
]

private let appNotifications: [String] = [
    kAXApplicationActivatedNotification as String,
    kAXMainWindowChangedNotification as String,
    kAXFocusedWindowChangedNotification as String,
    kAXWindowCreatedNotification as String,
]

private let windowNotifications: [String] = [
    kAXUIElementDestroyedNotification as String,
    kAXTitleChangedNotification as String,
    kAXWindowMiniaturizedNotification as String,
    kAXWindowDeminiaturizedNotification as String,
    kAXWindowResizedNotification as String,
    kAXWindowMovedNotification as String,
]

private let axCallback: AXObserverCallback = { observer, element, notification, refcon in
    guard let refcon = refcon else { return }
    let watcher = Unmanaged<AxAppWatcher>.fromOpaque(refcon).takeUnretainedValue()
    watcher.receive(notification: notification, element: element)
}
