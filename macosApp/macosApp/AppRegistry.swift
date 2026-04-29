import Foundation
import AppKit
import ApplicationServices
import ComposeAppMac

/// Tracks every running macOS application and owns one `AxAppWatcher` per pid.
/// Subscribes to `NSWorkspace` launch/terminate/activate notifications to create
/// and tear down watchers as apps come and go.
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
        NSLog("KAltSwitch: AX trusted = %@", lastTrusted ? "true" : "false")

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

        nsObservers = [launchObs, terminateObs, activateObs, hideObs, unhideObs]

        for nsApp in workspace.runningApplications {
            spawn(for: nsApp)
        }

        seedActivationLog()
        syncActiveStateFromSystem(store: store)
    }

    /// Pre-fill the activation log with one event per running app, descending
    /// timestamps so the frontmost app is "newest". This makes cmd+tab
    /// meaningful from the very first keystroke after launch — without it the
    /// log would be empty and the default cursor would have nowhere to land.
    private func seedActivationLog() {
        let workspace = NSWorkspace.shared
        let now = nowMillis()
        let frontPid = workspace.frontmostApplication?.processIdentifier

        // Background apps first, descending into the past, in the order
        // NSWorkspace reports them (system order is roughly launch order).
        let backgroundApps = workspace.runningApplications.filter {
            $0.activationPolicy != .prohibited
                && $0.processIdentifier > 0
                && $0.processIdentifier != frontPid
        }
        for (i, nsApp) in backgroundApps.enumerated() {
            let offsetMs = Int64(backgroundApps.count - i + 1) * 1000
            store.recordAppActivation(pid: nsApp.processIdentifier, timestampMs: now - offsetMs)
        }

        // Frontmost gets the latest timestamp and, if AX cooperates, also a
        // window-level event so cmd+` (window-mode) has a default target.
        if let frontPid = frontPid {
            store.recordAppActivation(pid: frontPid, timestampMs: now)
            let appEl = AXUIElementCreateApplication(frontPid)
            var focusedRef: AnyObject?
            let err = AXUIElementCopyAttributeValue(
                appEl, kAXFocusedWindowAttribute as CFString, &focusedRef)
            if err == .success,
               let v = focusedRef,
               CFGetTypeID(v as CFTypeRef) == AXUIElementGetTypeID() {
                let focused = v as! AXUIElement
                store.recordWindowActivation(
                    pid: frontPid,
                    windowId: Int64(CFHash(focused)),
                    timestampMs: now
                )
            }
        }
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

    /// Final activation on cmd-release. Order matters:
    ///  1. Mark target window as main + raise it inside its app, BEFORE
    ///     activating the app — otherwise some apps (Safari, iTerm, IDEs)
    ///     bring forward whatever was their last-frontmost window when they
    ///     first activate, ignoring our subsequent kAXMain change.
    ///     This is the same order AeroSpace uses in `MacApp.nativeFocus`.
    ///  2. Activate the app to bring it to the foreground.
    func commit(pid: pid_t, windowId: Int64?) {
        guard let nsApp = NSRunningApplication(processIdentifier: pid) else {
            NSLog("KAltSwitch: commit(pid=%d) — no NSRunningApplication", pid)
            return
        }
        if let wid = windowId {
            let raised = watchers[pid]?.makeWindowMain(windowId: wid) ?? false
            NSLog("KAltSwitch: commit pid=%d wid=%lld raise=%@", pid, wid,
                  raised ? "ok" : "fail")
        } else {
            NSLog("KAltSwitch: commit pid=%d (app-only, no window)", pid)
        }
        let activated = nsApp.activate(options: [.activateIgnoringOtherApps])
        NSLog("KAltSwitch: commit activate=%@", activated ? "ok" : "fail")
    }

    private func checkTrust() {
        let trusted = AXIsProcessTrusted()
        if trusted != lastTrusted {
            lastTrusted = trusted
            store.setAxTrusted(trusted: trusted)
            NSLog("KAltSwitch: AX trust changed to %@", trusted ? "true" : "false")
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
        let pid = nsApp.processIdentifier
        store.recordAppActivation(pid: pid, timestampMs: nowMillis())
        syncActiveStateFromSystem(store: store)
        // ChatGPT and similar apps refuse AX subscriptions on launch with
        // kAXErrorAPIDisabled. They typically accept once they've been activated by
        // the user — kick the watcher to retry subscriptions and refresh windows.
        watchers[pid]?.requestRefresh()
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

func nowMillis() -> Int64 {
    return Int64(Date().timeIntervalSince1970 * 1000)
}

/// Pulls authoritative "who is active" state from the system: NSWorkspace's
/// frontmost app, then that app's kAXFocusedWindow. Used after every event that
/// could move focus, including window destruction (e.g. closing Finder's last
/// real window — Finder may stay frontmost but with no usable focused window,
/// or focus may transition to another app silently).
func syncActiveStateFromSystem(store: WorldStore) {
    guard let frontmost = NSWorkspace.shared.frontmostApplication else {
        store.clearActive()
        return
    }
    let pid = frontmost.processIdentifier
    let appEl = AXUIElementCreateApplication(pid)
    var focusedRef: AnyObject?
    let err = AXUIElementCopyAttributeValue(appEl, kAXFocusedWindowAttribute as CFString, &focusedRef)
    if err == .success,
       let v = focusedRef,
       CFGetTypeID(v as CFTypeRef) == AXUIElementGetTypeID() {
        let focused = v as! AXUIElement
        store.setActiveAppAndWindow(pid: pid, windowId: Int64(CFHash(focused)))
    } else {
        store.setActiveApp(pid: pid)
    }
}
