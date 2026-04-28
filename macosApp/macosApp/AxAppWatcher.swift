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

    /// AX windows we've already subscribed to, keyed by `CFHash` of the AXUIElement.
    private var perWindowSubscribed = Set<Int>()

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
        switch name {
        case kAXApplicationActivatedNotification as String:
            store.setActiveApp(pid: pid)
            store.recordAppActivation(pid: pid, timestampMs: nowMillis())
            refreshAllWindows()

        case kAXApplicationHiddenNotification as String,
             kAXApplicationShownNotification as String:
            // NSWorkspace's didHide/didUnhide already triggers AppRegistry to push
            // a fresh App record with isHidden flipped. Nothing to do here.
            break

        case kAXMainWindowChangedNotification as String,
             kAXFocusedWindowChangedNotification as String:
            refreshAllWindows()
            let id = Int64(CFHash(element))
            store.recordWindowActivation(pid: pid, windowId: id, timestampMs: nowMillis())
            store.setActiveAppAndWindow(pid: pid, windowId: id)

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

        default:
            break
        }
    }

    // MARK: - Window queries

    private func refreshAllWindows() {
        let windows = (readAttribute(appElement, kAXWindowsAttribute as String) as? [AXUIElement]) ?? []
        var out: [Window] = []
        for axWin in windows {
            if let win = makeWindow(from: axWin) {
                out.append(win)
            }
        }
        store.setWindows(pid: pid, windows: out)
    }

    private func pushWindowFromElement(_ axWin: AXUIElement) {
        guard let win = makeWindow(from: axWin) else { return }
        store.upsertWindow(window: win)
    }

    /// Recursively collect window-like children attached to a window (sheets, drawers,
    /// "child windows" exposed by some apps). Each visited element gets a per-window
    /// AX subscription so we react to its own miniaturize/move/title changes too.
    ///
    /// We try three attributes since macOS doesn't have a single canonical "child
    /// windows" name: AXSheets is the standard for modal sheets, AXDrawers for
    /// (now-rare) drawer panels, and AXChildWindows is a non-standard string that
    /// some apps populate.
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
}

private let childWindowAttrs: [String] = [
    "AXSheets",
    "AXDrawers",
    "AXChildWindows",
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
