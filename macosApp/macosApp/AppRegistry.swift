import Foundation
import AppKit
import ApplicationServices
import ComposeAppMac

private extension Comparable {
    /// Clamp `self` to the inclusive bounds of `range`. Used to keep
    /// converted RGB components inside 0...255 when sRGB rounding pushes
    /// a component slightly past the byte range.
    func clamped(to range: ClosedRange<Self>) -> Self {
        return min(max(self, range.lowerBound), range.upperBound)
    }
}

/// Tracks every running macOS application and owns one `AxAppWatcher` per pid.
/// Subscribes to `NSWorkspace` launch/terminate/activate notifications to create
/// and tear down watchers as apps come and go.
///
/// **Why no KVO on `NSRunningApplication`?** Swift's KVO bridge is unsafe on
/// `NSRunningApplication`: AppKit's `runningApplicationNotificationCallback`
/// crashes with a pointer-authentication failure during termination cleanup
/// when active observations exist. We instead refresh `pushAppRecord` on
/// every workspace activation, which catches the common runtime mutation
/// (apps promoting `.accessory` → `.regular` on first-window-open).
final class AppRegistry {
    private let store: WorldStore
    private var watchers: [pid_t: AxAppWatcher] = [:]
    private var nsObservers: [NSObjectProtocol] = []
    private var trustTimer: Timer?
    private var lastTrusted = false
    /// Fires whenever AX trust flips. The hotkey controller's CGEventTap
    /// creation requires AX, so on `true` AppDelegate calls `start()` again
    /// (the call is idempotent).
    var onAxTrustChanged: ((Bool) -> Void)?

    init(store: WorldStore) {
        self.store = store
    }

    func start() {
        lastTrusted = AXIsProcessTrusted()
        store.setAxTrusted(trusted: lastTrusted)
        log("[reg] AX trusted = \(lastTrusted)")

        // Poll AX trust state every second. The moment macOS flips it to true
        // (after the user toggles us in Settings), we re-spawn all watchers.
        trustTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            self?.checkTrust()
        }

        let workspace = NSWorkspace.shared
        let center = workspace.notificationCenter

        let launchObs = center.addObserver(
            forName: NSWorkspace.didLaunchApplicationNotification,
            object: nil, queue: .main
        ) { [weak self] note in self?.handleLaunched(note) }

        let terminateObs = center.addObserver(
            forName: NSWorkspace.didTerminateApplicationNotification,
            object: nil, queue: .main
        ) { [weak self] note in self?.handleTerminated(note) }

        let activateObs = center.addObserver(
            forName: NSWorkspace.didActivateApplicationNotification,
            object: nil, queue: .main
        ) { [weak self] note in self?.handleActivated(note) }

        let hideObs = center.addObserver(
            forName: NSWorkspace.didHideApplicationNotification,
            object: nil, queue: .main
        ) { [weak self] note in self?.handleHiddenChange(note, hidden: true) }

        let unhideObs = center.addObserver(
            forName: NSWorkspace.didUnhideApplicationNotification,
            object: nil, queue: .main
        ) { [weak self] note in self?.handleHiddenChange(note, hidden: false) }

        // `activeSpaceDidChangeNotification` lives on the per-instance
        // workspace center (NOT default NotificationCenter). Fires when the
        // user switches Mission Control space — we re-poll the visible
        // space set and ask each watcher to refresh its windows so any
        // window that got dragged between spaces gets a fresh `spaceIds`.
        let spaceObs = center.addObserver(
            forName: NSWorkspace.activeSpaceDidChangeNotification,
            object: nil, queue: .main
        ) { [weak self] _ in self?.handleSpaceChanged() }

        // System accent colour. NSSystemColorsDidChangeNotification fires
        // when the user picks a different highlight in System Settings →
        // Appearance, so we can mirror the change live without polling.
        // Lives on the default NotificationCenter (not the workspace one).
        let accentObs = NotificationCenter.default.addObserver(
            forName: NSNotification.Name("NSSystemColorsDidChangeNotification"),
            object: nil, queue: .main
        ) { [weak self] _ in self?.refreshSystemAccent() }

        nsObservers = [launchObs, terminateObs, activateObs, hideObs, unhideObs, spaceObs, accentObs]

        for nsApp in workspace.runningApplications {
            spawn(for: nsApp)
        }

        // Seed the visible-space set so the filter works even before the
        // first space switch. Cheap call — no harm in doing it eagerly.
        refreshVisibleSpaces()
        refreshSystemAccent()

        // seedActivationLog ends in syncActiveStateFromSystem itself, so no
        // redundant call here.
        seedActivationLog()
    }

    /// Read NSColor.controlAccentColor, convert to sRGB, pack into 0xRRGGBB
    /// and push to the Kotlin store. Used both at startup and whenever the
    /// system accent setting changes.
    private func refreshSystemAccent() {
        let raw = NSColor.controlAccentColor
        guard let srgb = raw.usingColorSpace(.sRGB) else {
            log("[reg] system accent: failed to convert to sRGB")
            return
        }
        let r = Int((srgb.redComponent * 255).rounded()).clamped(to: 0...255)
        let g = Int((srgb.greenComponent * 255).rounded()).clamped(to: 0...255)
        let b = Int((srgb.blueComponent * 255).rounded()).clamped(to: 0...255)
        let rgb = Int64((r << 16) | (g << 8) | b)
        store.setSystemAccentRgb(rgb: rgb)
    }

    private func handleSpaceChanged() {
        log("[reg] activeSpaceDidChange — re-poll visible spaces & windows")
        refreshVisibleSpaces()
        for watcher in watchers.values {
            watcher.refreshAllWindowsSpaces()
        }
    }

    private func refreshVisibleSpaces() {
        let ids = currentVisibleSpaceIds()
        store.setVisibleSpaceIds(ids: ids.map { KotlinLong(value: $0) })
    }

    /// Pre-fill the activation log with one event per running app, descending
    /// timestamps so the frontmost app is "newest". This makes cmd+tab
    /// meaningful from the very first keystroke after launch — without it the
    /// log would be empty and the default cursor would have nowhere to land.
    private func seedActivationLog() {
        let workspace = NSWorkspace.shared
        let frontPid = workspace.frontmostApplication?.processIdentifier

        // Background apps in NSWorkspace's reported order (roughly launch
        // order). recordActivation prepends, so the *last* one looped over
        // ends up second-from-top in appOrder() — i.e. it'll be the default
        // cmd+tab cursor target. The exact ordering isn't critical: the very
        // first real activation event after launch will reorder things.
        let backgroundApps = workspace.runningApplications.filter {
            $0.activationPolicy != .prohibited
                && $0.processIdentifier > 0
                && $0.processIdentifier != frontPid
        }
        for nsApp in backgroundApps {
            store.recordActivation(pid: Int32(nsApp.processIdentifier), windowId: nil)
        }

        // Frontmost goes last (= ends up newest = top of the log) via the
        // same unified path that every later activation uses. If no app is
        // frontmost (login screen, all apps quit), this clears the active
        // pointers — also the right thing to do.
        syncActiveStateFromSystem(store: store)
    }

    func stop() {
        trustTimer?.invalidate(); trustTimer = nil
        let center = NSWorkspace.shared.notificationCenter
        for obs in nsObservers { center.removeObserver(obs) }
        nsObservers.removeAll()
        for (_, w) in watchers { w.stop() }
        watchers.removeAll()
    }

    // MARK: - Switcher actions (raise / commit)

    /// Raise a window for preview without changing frontmost-app.
    func raise(pid: pid_t, windowId: Int64) {
        watchers[pid]?.raiseWindow(windowId: windowId)
    }

    /// Final activation on cmd-release.
    ///
    /// Order matters and matches alt-tab-macos's `Window.focus()` exactly:
    ///  1. CGS `_SLPSSetFrontProcessWithOptions` (with the window's CGWindowID)
    ///     to flip the frontmost-app pointer in WindowServer **and** put the
    ///     specific window on top inside that process. NSRunningApplication.
    ///     activate would no-op here because we're a non-activating panel
    ///     host, so we call SkyLight directly.
    ///  2. The byte-record key-window trick is part of `bringAppToFront`.
    ///  3. AX `kAXMain` + `kAXRaise` on the chosen window. Done **after**
    ///     CGS so the target process is already frontmost when its main thread
    ///     processes the AX message — otherwise some apps (Safari, FF) ignore
    ///     a kAXMain change to a backgrounded window and re-raise their
    ///     previous frontmost window instead.
    ///  4. `nsApp.activate(...)` as a free fallback. If a future macOS breaks
    ///     the SkyLight call, this keeps focus moving on the public path.
    func commit(pid: pid_t, windowId: Int64?) {
        let watcher = watchers[pid]
        let cgWid: CGWindowID? = (windowId != nil)
            ? watcher?.cgWindowId(forAxWindowId: windowId!)
            : nil
        let cgsOk = bringAppToFront(pid: pid, cgWindowId: cgWid)
        var axOk = false
        if let wid = windowId {
            axOk = watcher?.makeWindowMain(windowId: wid) ?? false
        }
        let nsAppOk = NSRunningApplication(processIdentifier: pid)?
            .activate(options: [.activateIgnoringOtherApps]) ?? false
        log("[reg] commit pid=\(pid) ax=\(windowId ?? 0) cg=\(cgWid ?? 0) " +
            "cgs=\(cgsOk ? "ok" : "fail") axRaise=\(axOk ? "ok" : "fail") nsapp=\(nsAppOk ? "ok" : "fail")")
    }

    private func checkTrust() {
        let trusted = AXIsProcessTrusted()
        if trusted != lastTrusted {
            lastTrusted = trusted
            store.setAxTrusted(trusted: trusted)
            log("[reg] AX trust changed to \(trusted)")
            if trusted {
                respawnAllWatchers()
            }
            onAxTrustChanged?(trusted)
        }
    }

    private func respawnAllWatchers() {
        for (_, w) in watchers { w.stop() }
        watchers.removeAll()
        for nsApp in NSWorkspace.shared.runningApplications {
            spawn(for: nsApp)
        }
    }

    // MARK: - Notification handlers

    private func handleLaunched(_ note: Notification) {
        guard let nsApp = note.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication else { return }
        spawn(for: nsApp)
    }

    private func handleTerminated(_ note: Notification) {
        guard let nsApp = note.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication else { return }
        let pid = nsApp.processIdentifier
        watchers[pid]?.stop()
        watchers.removeValue(forKey: pid)
        store.removeApp(pid: pid)
    }

    private func handleActivated(_ note: Notification) {
        guard let nsApp = note.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication else { return }
        // syncActiveStateFromSystem records the activation through the unified
        // recordActivation API, updating both the log (row order) and the active
        // pointers (highlight) under the same switcherActive gate.
        syncActiveStateFromSystem(store: store)
        // Re-poll the app's metadata so a runtime activationPolicy promotion
        // (Bitwarden et al. flip .accessory → .regular when their main window
        // opens, which almost always coincides with workspace activation)
        // shows up live in the inspector and the rule pipeline. Cheap and
        // — critically — does not lean on Swift's KVO bridge, which crashes
        // intermittently on NSRunningApplication during termination cleanup.
        pushAppRecord(nsApp)
        // ChatGPT and similar apps refuse AX subscriptions on launch with
        // kAXErrorAPIDisabled. They typically accept once they've been activated by
        // the user — kick the watcher to retry subscriptions and refresh windows.
        watchers[nsApp.processIdentifier]?.requestRefresh()
    }

    private func handleHiddenChange(_ note: Notification, hidden: Bool) {
        guard let nsApp = note.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication else { return }
        pushAppRecord(nsApp)
    }

    // MARK: - Spawn / push

    private func spawn(for nsApp: NSRunningApplication) {
        let pid = nsApp.processIdentifier
        guard pid > 0 else { return }
        guard nsApp.activationPolicy != .prohibited else { return }
        if watchers[pid] != nil { return }

        pushAppRecord(nsApp)
        let watcher = AxAppWatcher(pid: pid, store: store)
        watchers[pid] = watcher
        watcher.start()
    }

    private func pushAppRecord(_ nsApp: NSRunningApplication) {
        let policy: Int64 = {
            switch nsApp.activationPolicy {
            case .regular: return 0
            case .accessory: return 1
            case .prohibited: return 2
            @unknown default: return 2
            }
        }()
        let launchMs: Int64 = {
            guard let date = nsApp.launchDate else { return 0 }
            return Int64(date.timeIntervalSince1970 * 1000)
        }()
        store.upsertAppFields(
            pid: nsApp.processIdentifier,
            bundleId: nsApp.bundleIdentifier,
            name: nsApp.localizedName ?? nsApp.bundleIdentifier ?? "Unknown",
            activationPolicyRaw: policy,
            isHidden: nsApp.isHidden,
            isFinishedLaunching: nsApp.isFinishedLaunching,
            executablePath: nsApp.executableURL?.path,
            launchDateMillis: launchMs
        )
        pushAppIcon(nsApp)
    }

    /// Render `NSRunningApplication.icon` (a vector NSImage) to a 128×128 PNG and push
    /// the bytes into the store so the Compose overlay can decode and display it.
    /// Skia decodes PNG natively; TIFF (NSImage's default representation) does not.
    private func pushAppIcon(_ nsApp: NSRunningApplication) {
        let pid = nsApp.processIdentifier
        guard let image = nsApp.icon else { return }
        let target = NSSize(width: 128, height: 128)
        let bitmap = NSBitmapImageRep(
            bitmapDataPlanes: nil,
            pixelsWide: Int(target.width), pixelsHigh: Int(target.height),
            bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true,
            isPlanar: false, colorSpaceName: .deviceRGB,
            bytesPerRow: 0, bitsPerPixel: 32
        )
        guard let bitmap = bitmap else { return }
        bitmap.size = target
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: bitmap)
        image.draw(in: NSRect(origin: .zero, size: target),
                   from: .zero, operation: .copy, fraction: 1.0)
        NSGraphicsContext.restoreGraphicsState()
        guard let png = bitmap.representation(using: .png, properties: [:]) else { return }
        let bytes = [UInt8](png)
        let kotlinByteArray = KotlinByteArray(size: Int32(bytes.count))
        for i in 0..<bytes.count {
            kotlinByteArray.set(index: Int32(i), value: Int8(bitPattern: bytes[i]))
        }
        store.setAppIconPng(pid: pid, png: kotlinByteArray)
    }
}

/// Pulls authoritative "who is active" state from the system: NSWorkspace's
/// frontmost app, then that app's kAXFocusedWindow. Used after every event that
/// could move focus, including window destruction (e.g. closing Finder's last
/// real window — Finder may stay frontmost but with no usable focused window,
/// or focus may transition to another app silently).
///
/// Goes through `WorldStore.recordActivation` so the activation log AND the
/// active-app/window pointers update **atomically through one gate**. Every
/// AX/NSWorkspace handler in this file delegates to this function — that's
/// what guarantees the inspector's row order can never disagree with its
/// active-row highlight.
func syncActiveStateFromSystem(store: WorldStore) {
    guard let frontmost = NSWorkspace.shared.frontmostApplication else {
        store.clearActive()
        return
    }
    let pid = frontmost.processIdentifier
    let appEl = AXUIElementCreateApplication(pid)
    var focusedRef: AnyObject?
    let err = AXUIElementCopyAttributeValue(appEl, kAXFocusedWindowAttribute as CFString, &focusedRef)
    let windowId: KotlinLong?
    if err == .success,
       let v = focusedRef,
       CFGetTypeID(v as CFTypeRef) == AXUIElementGetTypeID() {
        let focused = v as! AXUIElement
        windowId = KotlinLong(value: Int64(CFHash(focused)))
    } else {
        windowId = nil
    }
    store.recordActivation(pid: Int32(pid), windowId: windowId)
}
