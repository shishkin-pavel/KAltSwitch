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
        case kAXApplicationActivatedNotification as String,
             kAXMainWindowChangedNotification as String,
             kAXFocusedWindowChangedNotification as String,
             kAXUIElementDestroyedNotification as String:
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
        store.setWindows(pid: pid, windows: out)
        // Notify the registry: the windowed/windowless transition may have
        // shifted activationPolicy (Bitwarden et al. flip back to .accessory
        // when their last window closes — no workspace event for that).
        onWindowsChanged?(pid)
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
            spaceIds: base.spaceIds
        )
    }

    private func pushWindowFromElement(_ axWin: AXUIElement) {
        guard let win = makeWindow(from: axWin) else { return }
        store.upsertWindow(window: win)
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
        var cgWid: CGWindowID = 0
        let spaceIds: [Int64]
        if _AXUIElementGetWindow(axWin, &cgWid) == .success, cgWid != 0 {
            spaceIds = spaceIdsFor(cgWindowId: cgWid)
        } else {
            spaceIds = []
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
            spaceIds: spaceIds.map { KotlinLong(value: $0) }
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
