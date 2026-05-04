import Foundation
import AppKit
import ApplicationServices
import ComposeAppMac

/// Tracks every running macOS application and owns one `AxAppWatcher` per pid.
/// Subscribes to `NSWorkspace` launch/terminate/activate notifications to create
/// and tear down watchers as apps come and go.
///
/// **Why no KVO on `NSRunningApplication`?** Swift's KVO bridge is unsafe on
/// `NSRunningApplication`: AppKit's `runningApplicationNotificationCallback`
/// crashes with a pointer-authentication failure during termination cleanup
/// when active observations exist. We instead refresh the per-pid record
/// (via `AppRecordKt.upsertAppRecord`) on every workspace activation, which
/// catches the common runtime mutation (apps promoting `.accessory` →
/// `.regular` on first-window-open).
final class AppRegistry {
    private let store: WorldStore
    private var watchers: [pid_t: AxAppWatcher] = [:]
    private var nsObservers: [NSObjectProtocol] = []
    /// Observer token returned from the Kotlin `observeSystemAccent(store:)`
    /// helper. Held here so we can detach it on `stop()`.
    private var systemAccentObserver: NSObjectProtocol?
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

        // didHide / didUnhide both end in the same place: re-read the app's
        // record so the inspector and rule pipeline see the new
        // `isHidden` value. The hidden flag was previously passed in and
        // ignored — `upsertAppRecord` re-reads it from the live
        // NSRunningApplication.
        let hideObs = center.addObserver(
            forName: NSWorkspace.didHideApplicationNotification,
            object: nil, queue: .main
        ) { [weak self] note in self?.handleHiddenChange(note) }

        let unhideObs = center.addObserver(
            forName: NSWorkspace.didUnhideApplicationNotification,
            object: nil, queue: .main
        ) { [weak self] note in self?.handleHiddenChange(note) }

        // `activeSpaceDidChangeNotification` lives on the per-instance
        // workspace center (NOT default NotificationCenter). Fires when the
        // user switches Mission Control space — we re-poll the visible
        // space set and ask each watcher to refresh its windows so any
        // window that got dragged between spaces gets a fresh `spaceIds`.
        let spaceObs = center.addObserver(
            forName: NSWorkspace.activeSpaceDidChangeNotification,
            object: nil, queue: .main
        ) { [weak self] _ in self?.handleSpaceChanged() }

        nsObservers = [launchObs, terminateObs, activateObs, hideObs, unhideObs, spaceObs]

        for nsApp in workspace.runningApplications {
            spawn(for: nsApp)
        }

        // Seed the visible-space set so the filter works even before the
        // first space switch. Cheap call — no harm in doing it eagerly.
        refreshVisibleSpaces()

        // System accent colour: read + observe lives on the Kotlin side
        // (`SystemAccentKt.observeSystemAccent`) so the NSColor → 0xRRGGBB
        // packing has one home.
        systemAccentObserver = SystemAccentKt.observeSystemAccent(store: store)

        // seedActivationLog ends in syncActiveStateFromSystem itself, so no
        // redundant call here.
        seedActivationLog()
    }

    private func handleSpaceChanged() {
        log("[reg] activeSpaceDidChange — re-poll visible spaces & windows")
        refreshVisibleSpaces()
        for watcher in watchers.values {
            watcher.requestRefresh()
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
        if let accent = systemAccentObserver {
            // System accent observer lives on the *default* NotificationCenter,
            // not the workspace one — see SystemAccent.kt.
            NotificationCenter.default.removeObserver(accent)
            systemAccentObserver = nil
        }
        for (_, w) in watchers { w.stop() }
        watchers.removeAll()
    }

    // MARK: - Switcher actions (raise / commit / quit / hide / window-actions)

    /// Raise a window for preview without changing frontmost-app.
    func raise(pid: pid_t, windowId: Int64) {
        watchers[pid]?.raiseWindow(windowId: windowId)
    }

    /// `Q` — terminate the application gracefully (cmd+Q-equivalent: gives
    /// the app a chance to prompt for unsaved changes etc.). The world
    /// reflects the change via `NSWorkspace.didTerminateApplicationNotification`,
    /// which our handler routes through `removeApp`; the live snapshot
    /// collector (iter14) walks the cursor to a neighbour automatically.
    func quitApp(pid: pid_t) {
        guard let nsApp = NSRunningApplication(processIdentifier: pid) else { return }
        let ok = nsApp.terminate()
        log("[reg] quitApp pid=\(pid) → \(ok ? "ok" : "fail")")
    }

    /// `H` — toggle `NSRunningApplication.isHidden`. NSWorkspace fires
    /// didHide/didUnhide which we already route through
    /// `AppRecordKt.upsertAppRecord`, so the inspector and the switcher
    /// status badge update without further plumbing.
    func toggleHide(pid: pid_t) {
        guard let nsApp = NSRunningApplication(processIdentifier: pid) else { return }
        let ok = if nsApp.isHidden { nsApp.unhide() } else { nsApp.hide() }
        log("[reg] toggleHide pid=\(pid) wasHidden=\(nsApp.isHidden) → \(ok ? "ok" : "fail")")
    }

    /// `W` — press the window's red-circle close button via AX. Forwards
    /// to the per-pid watcher that owns the live AXUIElement.
    func closeWindow(pid: pid_t, windowId: Int64) {
        let ok = watchers[pid]?.closeWindow(windowId: windowId) ?? false
        log("[reg] closeWindow pid=\(pid) wid=\(windowId) → \(ok ? "ok" : "fail")")
    }

    /// `M` — toggle the window's `kAXMinimizedAttribute`.
    func toggleMinimize(pid: pid_t, windowId: Int64) {
        let ok = watchers[pid]?.toggleMinimize(windowId: windowId) ?? false
        log("[reg] toggleMinimize pid=\(pid) wid=\(windowId) → \(ok ? "ok" : "fail")")
    }

    /// `F` — toggle the window's `AXFullScreen` (private constant).
    func toggleFullscreen(pid: pid_t, windowId: Int64) {
        let ok = watchers[pid]?.toggleFullscreen(windowId: windowId) ?? false
        log("[reg] toggleFullscreen pid=\(pid) wid=\(windowId) → \(ok ? "ok" : "fail")")
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
    ///  4. `nsApp.activate()` as a free fallback. If a future macOS breaks
    ///     the SkyLight call, this keeps focus moving on the public path.
    ///     The `.activateIgnoringOtherApps` option was deprecated on macOS
    ///     14+ and Apple documents it as ineffective — the no-arg form is
    ///     the modern equivalent and avoids a deprecation warning at build.
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
        let nsAppOk = NSRunningApplication(processIdentifier: pid)?.activate() ?? false
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
        // pointers (highlight) atomically.
        syncActiveStateFromSystem(store: store)
        // Re-poll the app's metadata so a runtime activationPolicy promotion
        // (Bitwarden et al. flip .accessory → .regular when their main window
        // opens, which almost always coincides with workspace activation)
        // shows up live in the inspector and the rule pipeline. Cheap and
        // — critically — does not lean on Swift's KVO bridge, which crashes
        // intermittently on NSRunningApplication during termination cleanup.
        AppRecordKt.upsertAppRecord(pid: nsApp.processIdentifier, store: store)
        // ChatGPT and similar apps refuse AX subscriptions on launch with
        // kAXErrorAPIDisabled. They typically accept once they've been activated by
        // the user — kick the watcher to retry subscriptions and refresh windows.
        watchers[nsApp.processIdentifier]?.requestRefresh()
    }

    private func handleHiddenChange(_ note: Notification) {
        guard let nsApp = note.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication else { return }
        AppRecordKt.upsertAppRecord(pid: nsApp.processIdentifier, store: store)
    }

    // MARK: - Spawn / push

    private func spawn(for nsApp: NSRunningApplication) {
        let pid = nsApp.processIdentifier
        guard pid > 0 else { return }
        guard nsApp.activationPolicy != .prohibited else { return }
        if watchers[pid] != nil { return }

        AppRecordKt.upsertAppRecord(pid: pid, store: store)
        let watcher = AxAppWatcher(pid: pid, store: store)
        // Window-set mutation may flip activationPolicy back the other way
        // (apps that drop to `.accessory` after their last window closes).
        // No workspace notification fires for that transition; this hook is
        // the only signal we get on the AppKit side.
        watcher.onWindowsChanged = { [weak self] pid in
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                AppRecordKt.upsertAppRecord(pid: pid, store: self.store)
            }
        }
        watchers[pid] = watcher
        watcher.start()
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
