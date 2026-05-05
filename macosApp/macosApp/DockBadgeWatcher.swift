import Foundation
import AppKit
import ApplicationServices
import ComposeAppMac

/// Reads notification badges (e.g. "5" on Mail, "•" on Slack) from the
/// macOS Dock's accessibility tree and pushes them into
/// `WorldStore.setAppBadge(...)`.
///
/// **Source of truth.** The Dock is itself an accessible app
/// (`com.apple.dock`). Every running / pinned app has an
/// `AXApplicationDockItem`-subroled child whose `AXURL` points at the
/// .app bundle (used for matching to running pids) and whose
/// `AXStatusLabel` holds the badge string the app set via
/// `NSDockTile.badgeLabel`. There is no public API for this — alt-tab-macos
/// and AeroSpace use the same AX-on-Dock approach.
///
/// **Live updates.** We register a `kAXValueChangedNotification` observer
/// on every dock item, so when an app changes its badge (Slack going
/// 5 → 6) the change lands in the store within an event cycle. New apps
/// joining the dock are picked up via `rescan()`, called from
/// `AppRegistry.handleLaunched`. Apps leaving have their badge cleared
/// implicitly when `removeApp(pid:)` drops the whole record.
///
/// **Failure modes.** Requires Accessibility permission, same as the
/// per-app watchers. If the Dock's AX surface is unavailable
/// (mid-restart, AX denied) we log + bail out; badges silently stay
/// nil and the rest of the switcher still works.
final class DockBadgeWatcher {
    private let store: WorldStore
    private var observer: AXObserver?
    private var dockApp: AXUIElement?
    /// Dock-tile element CFHashes we've already attached an observer to.
    /// AX returns `cannotComplete` on a duplicate add, so we de-dupe here.
    private var subscribedHashes = Set<Int>()
    /// Bundle path of the .app a given dock-tile element represents,
    /// keyed by element CFHash. The reverse-lookup we need on
    /// AXValueChanged: callback hands us the element, we resolve its
    /// bundle path and from there the running pid.
    private var bundlePathByHash: [Int: String] = [:]

    init(store: WorldStore) { self.store = store }

    func start() {
        guard let dockPid = NSWorkspace.shared.runningApplications
            .first(where: { $0.bundleIdentifier == "com.apple.dock" })?
            .processIdentifier
        else {
            log("[dock] dock app not found, badges disabled")
            return
        }
        let app = AXUIElementCreateApplication(dockPid)
        dockApp = app

        var observerRef: AXObserver?
        let err = AXObserverCreate(dockPid, dockBadgeCallback, &observerRef)
        guard err == .success, let observerRef = observerRef else {
            log("[dock] AXObserverCreate(dockPid=\(dockPid)) failed: \(err.rawValue)")
            return
        }
        observer = observerRef
        CFRunLoopAddSource(
            CFRunLoopGetMain(),
            AXObserverGetRunLoopSource(observerRef),
            .defaultMode
        )

        scan()
    }

    func stop() {
        // Same lifetime model as AxAppWatcher: stop is called on app
        // termination, and dropping the observer reference takes its
        // run-loop source with it. We don't bother with
        // AXObserverRemoveNotification per item.
        observer = nil
        dockApp = nil
        subscribedHashes.removeAll()
        bundlePathByHash.removeAll()
    }

    /// Re-walk the dock to pick up newly-pinned items. Called from
    /// `AppRegistry.handleLaunched` so a freshly-launched app's dock
    /// tile (which the Dock adds asynchronously) gets its observer
    /// attached. Cheap on the main run loop — the dock has dozens of
    /// items, not thousands.
    func rescan() {
        scan()
    }

    private func scan() {
        guard let dockApp = dockApp, let observer = observer else { return }
        let items = collectDockAppItems(dockApp)
        for item in items {
            let hash = Int(CFHash(item))
            // The Dock's `AXURL` is a file URL of the .app bundle. Items
            // without a URL (separators, the trash) are filtered out
            // earlier by subrole, but the read can still legitimately
            // return nil during dock animations — skip rather than crash.
            guard let url = readAttribute(item, "AXURL") as? URL else { continue }
            bundlePathByHash[hash] = url.standardizedFileURL.path

            if subscribedHashes.insert(hash).inserted {
                let selfPtr = Unmanaged.passUnretained(self).toOpaque()
                let err = AXObserverAddNotification(
                    observer, item,
                    kAXValueChangedNotification as CFString,
                    selfPtr
                )
                if err != .success {
                    log("[dock] addNotification(\(url.lastPathComponent)) failed: \(err.rawValue)")
                }
            }
            push(itemHash: hash, badgeText: readBadge(item))
        }
    }

    /// Walk the dock app one or two levels deep to collect every leaf
    /// with subrole `AXApplicationDockItem`. Modern macOS nests these
    /// under an intermediate `AXList`, but we don't depend on that —
    /// we descend any non-application-item child once and pick up the
    /// app items wherever they sit.
    private func collectDockAppItems(_ root: AXUIElement) -> [AXUIElement] {
        var out: [AXUIElement] = []
        let topChildren = (readAttribute(root, kAXChildrenAttribute as String) as? [AXUIElement]) ?? []
        for child in topChildren {
            let childSubrole = readAttribute(child, kAXSubroleAttribute as String) as? String
            if childSubrole == "AXApplicationDockItem" {
                out.append(child)
                continue
            }
            let grandKids = (readAttribute(child, kAXChildrenAttribute as String) as? [AXUIElement]) ?? []
            for gk in grandKids {
                let sub = readAttribute(gk, kAXSubroleAttribute as String) as? String
                if sub == "AXApplicationDockItem" { out.append(gk) }
            }
        }
        return out
    }

    /// Called from C `dockBadgeCallback` on the main run loop when a
    /// dock item's `AXValueChanged` fires (the value being its
    /// `AXStatusLabel`). We don't always know *which* attribute
    /// changed from a generic value-changed event, so we just re-read
    /// the badge — cheap, idempotent, and the store's `setAppBadge`
    /// dedupes equal values so equal-write spam is a no-op.
    fileprivate func receive(element: AXUIElement) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            let hash = Int(CFHash(element))
            self.push(itemHash: hash, badgeText: self.readBadge(element))
        }
    }

    private func readBadge(_ item: AXUIElement) -> String? {
        let raw = readAttribute(item, "AXStatusLabel") as? String
        // Dock returns "" when an app cleared its badge — normalise to nil
        // so downstream can use a single absent-marker.
        return (raw?.isEmpty == false) ? raw : nil
    }

    private func push(itemHash: Int, badgeText: String?) {
        guard let path = bundlePathByHash[itemHash] else { return }
        // Match by bundle path. Two NSRunningApplication instances
        // pointing at the same bundle (multi-instance apps like Terminal
        // when "Open in new window" launches separate processes) is
        // rare for the badge-bearing apps — first match wins.
        guard let nsApp = NSWorkspace.shared.runningApplications.first(where: {
            $0.bundleURL?.standardizedFileURL.path == path
        }) else { return }
        let pid = nsApp.processIdentifier
        guard pid > 0 else { return }
        store.setAppBadge(pid: Int32(pid), badgeText: badgeText)
    }

    private func readAttribute(_ element: AXUIElement, _ attribute: String) -> Any? {
        var value: AnyObject?
        let err = AXUIElementCopyAttributeValue(element, attribute as CFString, &value)
        guard err == .success else { return nil }
        return value
    }
}

private let dockBadgeCallback: AXObserverCallback = { _, element, _, refcon in
    guard let refcon = refcon else { return }
    let watcher = Unmanaged<DockBadgeWatcher>.fromOpaque(refcon).takeUnretainedValue()
    watcher.receive(element: element)
}
