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

    /// Per-app notifications we've successfully subscribed to. Retried on every
    /// refresh until full — early-launch apps like ChatGPT often refuse AX initially
    /// (kAXErrorAPIDisabled) and accept only after their UI has fully come up.
    private var subscribedAppNotifications = Set<String>()
    /// Per-window subscriptions — keyed by (CFHash, notificationName). Same retry idea.
    private var subscribedWindowNotifications = Set<String>()

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

        ensureAppSubscriptions()

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
        switch name {
        case kAXApplicationActivatedNotification as String:
            store.recordAppActivation(pid: pid, timestampMs: nowMillis())
            refreshAllWindows()
            syncActiveStateFromSystem(store: store)

        case kAXApplicationHiddenNotification as String,
             kAXApplicationShownNotification as String:
            // NSWorkspace's didHide/didUnhide already triggers AppRegistry to push
            // a fresh App record with isHidden flipped. Nothing to do here.
            break

        case kAXMainWindowChangedNotification as String,
             kAXFocusedWindowChangedNotification as String:
            refreshAllWindows()
            // Only record this as activation history if our app is actually frontmost
            // — otherwise we'd reorder the log based on a no-longer-active app's
            // events. Sync active-state from the system to authoritative truth.
            if NSWorkspace.shared.frontmostApplication?.processIdentifier == pid {
                store.recordWindowActivation(pid: pid, windowId: Int64(CFHash(element)), timestampMs: nowMillis())
            }
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

        case kAXUIElementDestroyedNotification as String:
            // Without a stable id-before-destruction we just re-snapshot; the gone
            // window will simply be missing from the new list.
            refreshAllWindows()
            // The destroyed window may have been the active one, and macOS may have
            // silently transitioned focus (e.g. closing Finder's last real window).
            // Re-sync from the system rather than assuming.
            syncActiveStateFromSystem(store: store)

        default:
            break
        }
    }

    // MARK: - Window queries

    private func refreshAllWindows() {
        // Whenever we manage to read AX state, re-try any per-app subscriptions
        // that earlier failed (apps like ChatGPT often deny AX until they're fully
        // up, then start accepting). Idempotent.
        ensureAppSubscriptions()

        let raw = readAttribute(appElement, kAXWindowsAttribute as String)
        let topLevel = (raw as? [AXUIElement]) ?? []
        let appHash = Int(CFHash(appElement))
        let byHash: [Int: AXUIElement] = Dictionary(uniqueKeysWithValues:
            topLevel.map { (Int(CFHash($0)), $0) })

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
            children: base.children + extras
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

    /// Re-attempt per-app notification subscriptions. Notifications already accepted
    /// stay in `subscribedAppNotifications`; ones that returned `kAXErrorAPIDisabled`
    /// (-25211) earlier get retried until they succeed. Safe to call repeatedly —
    /// `kAXErrorNotificationAlreadyRegistered` is treated as success.
    private func ensureAppSubscriptions() {
        guard let observer = observer else { return }
        let selfPtr = Unmanaged.passUnretained(self).toOpaque()
        for notif in appNotifications {
            if subscribedAppNotifications.contains(notif) { continue }
            let err = AXObserverAddNotification(observer, appElement, notif as CFString, selfPtr)
            if err == .success || err == .notificationAlreadyRegistered {
                subscribedAppNotifications.insert(notif)
            }
        }
    }

    private func subscribePerWindow(_ axWin: AXUIElement) {
        guard let observer = observer else { return }
        let hash = Int(CFHash(axWin))
        let selfPtr = Unmanaged.passUnretained(self).toOpaque()
        for notif in windowNotifications {
            let key = "\(hash)|\(notif)"
            if subscribedWindowNotifications.contains(key) { continue }
            let err = AXObserverAddNotification(observer, axWin, notif as CFString, selfPtr)
            if err == .success || err == .notificationAlreadyRegistered {
                subscribedWindowNotifications.insert(key)
            }
        }
    }

    private func makeWindow(from axWin: AXUIElement) -> Window? {
        subscribePerWindow(axWin)
        let title = (readAttribute(axWin, kAXTitleAttribute as String) as? String) ?? ""
        let role = readAttribute(axWin, kAXRoleAttribute as String) as? String
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
            children: children
        )
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
    kAXApplicationHiddenNotification as String,
    kAXApplicationShownNotification as String,
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
