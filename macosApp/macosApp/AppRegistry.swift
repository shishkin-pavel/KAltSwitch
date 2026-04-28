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

        if let frontmost = workspace.frontmostApplication {
            store.setActiveApp(pid: frontmost.processIdentifier)
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

    private func checkTrust() {
        let trusted = AXIsProcessTrusted()
        if trusted != lastTrusted {
            lastTrusted = trusted
            store.setAxTrusted(trusted: trusted)
            NSLog("KAltSwitch: AX trust changed to %@", trusted ? "true" : "false")
            if trusted {
                respawnAllWatchers()
            }
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
