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

        // seedActivationLog ends in syncActiveStateFromSystem itself, so no
        // redundant call here.
        seedActivationLog()
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
            store.recordActivation(
                pid: Int32(nsApp.processIdentifier),
                windowId: nil,
                timestampMs: now - offsetMs
            )
        }

        // Frontmost gets the latest timestamp via the same unified path that
        // every later activation goes through (`syncActiveStateFromSystem`),
        // so the seed is indistinguishable from a real activation event. If
        // there is no frontmost (login screen, all apps quit), this clears
        // the active pointers — also the right thing to do.
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
    /// Order:
    ///  1. AX-side: kAXMain + kAXRaise on the chosen window. Sets its app's
    ///     "main window" so the system focuses it once the app is frontmost.
    ///  2. CGS-side: `_SLPSSetFrontProcessWithOptions` (SkyLight private API)
    ///     to actually flip the frontmost-app pointer in WindowServer. This
    ///     is what `NSRunningApplication.activate(options:)` *would* do in
    ///     theory but silently skips on macOS 14+ when the caller (us) isn't
    ///     already the active app. Our nonactivating overlay panel is exactly
    ///     that case, so we go around the public API. See
    ///     `docs/window-state-attributes.md` §8 ("AeroSpace-style agent app")
    ///     for the public-API alternative we deliberately deferred.
    ///  3. As a safety net we also call `nsApp.activate(...)` — it's free
    ///     when it works, no-ops otherwise. If a future macOS breaks the
    ///     SkyLight call, this keeps us limping along on the public path.
    func commit(pid: pid_t, windowId: Int64?) {
        let watcher = watchers[pid]
        var cgWid: CGWindowID? = nil
        if let wid = windowId, let watcher = watcher {
            let raised = watcher.makeWindowMain(windowId: wid)
            cgWid = watcher.cgWindowId(forAxWindowId: wid)
            NSLog("KAltSwitch: commit pid=%d ax=%lld cg=%u raise=%@",
                  pid, wid, cgWid ?? 0, raised ? "ok" : "fail")
        } else {
            NSLog("KAltSwitch: commit pid=%d (app-only, no window)", pid)
        }
        let cgsOk = bringAppToFront(pid: pid, cgWindowId: cgWid)
        let nsAppOk = NSRunningApplication(processIdentifier: pid)?
            .activate(options: [.activateIgnoringOtherApps]) ?? false
        NSLog("KAltSwitch: commit cgs=%@ nsapp=%@",
              cgsOk ? "ok" : "fail", nsAppOk ? "ok" : "fail")
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
        // syncActiveStateFromSystem records the activation through the unified
        // recordActivation API, updating both the log (row order) and the active
        // pointers (highlight) under the same switcherActive gate.
        syncActiveStateFromSystem(store: store)
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

func nowMillis() -> Int64 {
    return Int64(Date().timeIntervalSince1970 * 1000)
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
    store.recordActivation(pid: Int32(pid), windowId: windowId, timestampMs: nowMillis())
}
