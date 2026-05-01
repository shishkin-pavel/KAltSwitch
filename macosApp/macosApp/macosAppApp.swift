import SwiftUI
import AppKit
import Carbon.HIToolbox
import ServiceManagement
import ComposeAppMac

@main
struct macosAppApp: SwiftUI.App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    var body: some SwiftUI.Scene {}
}

class AppDelegate: NSObject, NSApplicationDelegate {
    var window: NSWindow!
    private var composeDelegate: ComposeNSViewDelegate? = nil
    private var appRegistry: AppRegistry? = nil
    private var hotkeyController: HotkeyController? = nil
    private var overlayWindow: SwitcherOverlayWindow? = nil
    private var overlayComposeDelegate: ComposeNSViewDelegate? = nil
    private var frameObservers: [NSObjectProtocol] = []
    private var statusItem: NSStatusItem? = nil
    private var signalSources: [DispatchSourceSignal] = []
    /// Best-effort hot-key-restoration helper. See
    /// `macosApp/Watchdog/main.swift` for the rationale and protocol.
    private var watchdogProcess: Process?
    /// Latest applied inspector-visibility state. `nil` until the first
    /// observeInspectorVisible callback fires; afterwards used by
    /// persistFrame() to know whether the user is editing the
    /// settings-only width or the inspector's extra width, and by the
    /// toggle observer to detect transitions vs the initial seed.
    private var inspectorVisibleApplied: Bool? = nil
    /// Used the first time the user resizes before any windowFrame is saved.
    private let defaultSettingsOnlyWidth: Double = 320
    /// Global keyboard monitor — third path for detecting tab/grave keyUp
    /// (after Carbon Released and panel sendEvent). Needs AX permission to
    /// receive global events. The first two paths can silently miss events
    /// in some macOS configurations; this monitor sees them at the system
    /// level.
    private var globalKeyMonitor: Any? = nil
    /// Global NSEvent mouse-down monitor. Writes `lastMouseDownTime`
    /// so the panel's resignKey safety net (see the resignObs in
    /// `applicationDidFinishLaunching`) can tell a user-driven click
    /// from a system focus reassignment, even when cmd is still held.
    /// Only `addGlobalMonitor` (events delivered to OTHER apps) is
    /// needed: in-app clicks that affect the switcher panel either
    /// commit/cancel the session before resignKey fires (so the
    /// notification is harmless) or land in unusual edge cases we
    /// don't optimise for.
    private var globalMouseMonitor: Any? = nil
    /// Event timestamp of the most recent mouseDown delivered to
    /// another process (or `-1` if none yet). Same time base as
    /// `NSEvent.timestamp` / `ProcessInfo.processInfo.systemUptime`.
    private var lastMouseDownTime: TimeInterval = -1
    /// Latest applied state — observers fire eagerly with the seed value
    /// AFTER the initial install, so we record state to detect "change vs
    /// seed" and keep menu items / SMAppService in sync without thrashing.
    private var showMenubarIconApplied: Bool? = nil
    private var launchAtLoginApplied: Bool? = nil
    /// Mutable refs to the toggle menu items so we can flip the checkmark
    /// when the state changes via the settings panel.
    private var menubarIconMenuItem: NSMenuItem? = nil
    private var launchAtLoginMenuItem: NSMenuItem? = nil

    func applicationDidFinishLaunching(_ aNotification: Notification) {
        // InspectorWindow is a thin NSWindow subclass that intercepts
        // cmd+W/Q/M/H before they reach the Compose NSView's keyDown
        // path (which silently consumes them). See InspectorWindow.swift
        // for the rationale.
        window = InspectorWindow(
            contentRect: NSRect(x: 0, y: 0, width: 800, height: 600),
            styleMask: [.titled, .closable, .miniaturizable, .resizable],
            backing: .buffered,
            defer: false
        )
        // Initial title — overwritten as soon as observeInspectorVisible
        // delivers the persisted state below. The "right pane" (live
        // apps/windows table) is still internally called "the inspector"
        // in code; user-facing copy says "Settings" regardless.
        window.title = "KAltSwitch — Settings"
        window.center()
        // Closing or cmd+W on the inspector hides it instead of releasing.
        // The app keeps running (see applicationShouldTerminateAfterLastWindowClosed)
        // so cmd+tab still works; the menubar item or Spotlight relaunch can
        // bring the inspector back.
        window.isReleasedWhenClosed = false

        composeDelegate = ComposeViewKt.AttachMainComposeView(window: window)

        // Restore the saved window frame. Stored width is the *settings-only*
        // width; when the inspector starts visible we add the saved
        // inspectorWidth on top. Math lives in Kotlin
        // (`InspectorWindowLayoutKt.restoredInspectorWindowFrame`) so it can
        // be unit-tested next to the persisted `WindowFrame` semantics.
        let initialInspectorVisible = (ComposeViewKt.store.inspectorVisible.value as? KotlinBoolean)?.boolValue ?? true
        if let restoredFrame = InspectorWindowLayoutKt.restoredInspectorWindowFrame(
            settingsFrame: ComposeViewKt.store.windowFrame.value as? WindowFrame,
            inspectorVisible: initialInspectorVisible,
            inspectorWidth: currentInspectorWidth()
        ) {
            let restored = restoredFrame.nsRect
            if NSScreen.screens.contains(where: { $0.visibleFrame.intersects(restored) }) {
                window.setFrame(restored, display: false)
            }
        }

        // Don't auto-show the inspector at startup. The user opens it
        // explicitly via the menubar icon (when shown), Dock-icon click /
        // Spotlight relaunch (`applicationShouldHandleReopen`), or a fresh
        // `open -a KAltSwitch`. Launching at login should be silent.

        // Persist user-driven moves and resizes back to config.json.
        let center = NotificationCenter.default
        let move = center.addObserver(forName: NSWindow.didMoveNotification, object: window, queue: .main) { [weak self] _ in
            self?.persistFrame()
        }
        let resize = center.addObserver(forName: NSWindow.didEndLiveResizeNotification, object: window, queue: .main) { [weak self] _ in
            self?.persistFrame()
        }
        // Dock icon + system cmd+tab presence are tied to the inspector
        // window's visibility. Closing it (cmd+W or red-circle) drops us
        // back to `.accessory` — a pure menubar utility in steady state.
        // Re-opening (menubar / Spotlight relaunch) flips us to `.regular`
        // again via showInspector(). NSWindow.willClose fires before the
        // window is hidden so the policy change races perfectly.
        let willClose = center.addObserver(forName: NSWindow.willCloseNotification, object: window, queue: .main) { [weak self] _ in
            self?.setActivationPolicyForInspector(visible: false)
        }
        frameObservers = [move, resize, willClose]

        // Bring up the per-app AX watchers. Store is the singleton owned by the framework.
        let registry = AppRegistry(store: ComposeViewKt.store)
        appRegistry = registry
        registry.start()

        // Wire the Kotlin switcher controller's AX-side actions to AppRegistry.
        let controller = ComposeViewKt.switcherController
        controller.onRaiseWindow = { [weak registry] pid, windowId in
            registry?.raise(pid: pid_t(truncatingIfNeeded: pid.int32Value),
                            windowId: windowId.int64Value)
        }
        controller.onCommitActivation = { [weak self, weak registry] pid, windowId in
            // Hide the overlay BEFORE asking macOS to activate the target app.
            // The StateFlow observer that orderOuts the panel is dispatched
            // through the bridge scope and would otherwise fire AFTER we've
            // already issued the activate call, leaving the system to negotiate
            // focus while our key panel is still on screen — that's enough to
            // make `nsApp.activate` silently no-op.
            let widStr = windowId.map { String($0.int64Value) } ?? "nil"
            log("[swift] onCommitActivation pid=\(pid.int32Value) wid=\(widStr)")
            self?.overlayWindow?.orderOut(nil)
            registry?.commit(pid: pid_t(truncatingIfNeeded: pid.int32Value),
                             windowId: windowId?.int64Value)
        }
        // Single-key actions (Q/W/M/H/F) fired from the Compose overlay
        // while the session is open. The session stays open after each
        // action; the world mutates and the live snapshot collector
        // (iter14) walks the cursor to a neighbour automatically when a
        // close/quit removes the target.
        controller.onPerformAction = { [weak registry] action, pid, windowId in
            guard let registry = registry else { return }
            let p = pid_t(truncatingIfNeeded: pid.int32Value)
            let wid = windowId?.int64Value
            // K/N exports Kotlin enum cases as ObjC class properties named
            // by the *raw* lower-cased identifier (no word separation), so
            // `SwitcherAction.QuitApp` becomes `.quitapp` etc. at the Swift
            // call site. Switch on equality with the static instances rather
            // than via `case .quitapp:` because Swift treats CAMSwitcherAction
            // as an Obj-C class subclass, not a native Swift enum.
            if action == SwitcherAction.quitapp {
                registry.quitApp(pid: p)
            } else if action == SwitcherAction.togglehide {
                registry.toggleHide(pid: p)
            } else if action == SwitcherAction.closewindow, let wid = wid {
                registry.closeWindow(pid: p, windowId: wid)
            } else if action == SwitcherAction.toggleminimize, let wid = wid {
                registry.toggleMinimize(pid: p, windowId: wid)
            } else if action == SwitcherAction.togglefullscreen, let wid = wid {
                registry.toggleFullscreen(pid: p, windowId: wid)
            }
        }

        // Global hotkeys + cmd-release detection feed the Kotlin SwitcherController.
        hotkeyController = HotkeyController(controller: controller)
        hotkeyController?.start()

        // The CGEventTap inside HotkeyController needs AX permission. If the
        // user grants it after launch, re-run start() so the tap installs.
        registry.onAxTrustChanged = { [weak self] trusted in
            if trusted { self?.hotkeyController?.start() }
        }

        // Switcher overlay panel. Built eagerly so showing has zero startup cost.
        // Two flow observers below split the lifecycle into two stages:
        //   * session-active (ui != null) → place the panel and make it key
        //     so we can observe modifier-release flagsChanged via sendEvent
        //     without depending on AX-gated CGEventTap.
        //   * visible (ui.visible == true) → flip alphaValue to actually show
        //     the contents after `showDelay` has elapsed.
        let panel = SwitcherOverlayWindow()
        overlayWindow = panel
        overlayComposeDelegate = ComposeViewKt.AttachSwitcherOverlay(window: panel)

        // ComposeNSViewDelegate set the panel's contentView to the Compose
        // NSView. Re-parent it so an NSVisualEffectView sits underneath —
        // that's the blur backdrop. Sized later via observeSwitcherPanelSize.
        if let composeView = panel.contentView {
            panel.installBlurBackdrop(under: composeView)
        }
        ComposeViewKt.observeSwitcherPanelSize(
            onChange: { [weak panel] w, h in
                panel?.updateBlurFrame(widthPts: CGFloat(w.doubleValue), heightPts: CGFloat(h.doubleValue))
            },
            onCleared: { [weak panel] in
                panel?.updateBlurFrame(widthPts: nil, heightPts: nil)
            },
        )

        panel.onCommandReleased = { [weak controller] in
            controller?.onModifierReleased()
        }
        panel.onShortcutKeyReleased = { [weak controller] in
            controller?.onShortcutKeyReleased()
        }
        // cmd+Q/W/M/H/F intercepted by the panel's `performKeyEquivalent`
        // so they fire on the switcher's selected target instead of being
        // swallowed by NSApp.mainMenu (which would, e.g., terminate
        // KAltSwitch on cmd+Q rather than quitting the highlighted app).
        panel.onAction = { [weak controller] action in
            controller?.onAction(action: action)
        }
        // Safety net for the panel losing key status mid-session. Three
        // ways this fires; we want to close on user actions and stay
        // open on system events:
        //
        // 1. User clicked another window (anywhere) → close. The cmd
        //    state alone can't tell us this happened — the user can
        //    click while still holding cmd, in which case cmd is held
        //    AND the click should close the switcher. The reliable
        //    signal is "did a mouseDown fire just now?" — tracked via
        //    `lastMouseDownTime` that the global+local mouse monitors
        //    write on every click anywhere.
        //
        // 2. cmd NOT held, no recent click → typically OS-driven
        //    cmd+tab or another app's `NSApp.activate` — treat as
        //    user-equivalent and close.
        //
        // 3. cmd HELD, no recent click → system reassigned focus while
        //    the user is mid-gesture (new window appeared, focused
        //    window closed). Don't close — re-take key.
        //
        // The decision is **deferred by ~50 ms**. Global mouse monitors
        // are documented to fire asynchronously on the main queue —
        // empirically they often land AFTER the resignKey notification
        // even though the click physically came first. Reading
        // `lastMouseDownTime` immediately would see stale data and
        // misclassify clicks on other apps as system events. The defer
        // gives the monitor's callback a turn of the runloop to write.
        // The original event timestamp (`event.timestamp`) is used for
        // the recency check, so the 50 ms wait doesn't bias the
        // 200 ms freshness threshold.
        let resignObs = NotificationCenter.default.addObserver(
            forName: NSWindow.didResignKeyNotification,
            object: panel,
            queue: .main
        ) { [weak controller, weak panel, weak self] _ in
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { [weak controller, weak panel, weak self] in
                // Panel ordered out by setOverlayActive(false) after a
                // commit / cancel? resignKey fired as a side-effect; nothing
                // to do — the session is already gone. Both branches below
                // would be wrong: makeKey on a hidden panel re-shows it
                // without a session, onEsc on a closed session is a no-op
                // but the bail keeps the log clean.
                guard panel?.isVisible == true else { return }
                let cmdHeld = NSEvent.modifierFlags.contains(.command)
                let now = ProcessInfo.processInfo.systemUptime
                let lastClick = self?.lastMouseDownTime ?? -1
                let recentClick = lastClick >= 0 && (now - lastClick) < 0.2
                if cmdHeld && !recentClick {
                    panel?.makeKey()
                } else {
                    controller?.onEsc()
                }
            }
        }
        frameObservers.append(resignObs)

        ComposeViewKt.observeSwitcherSession { [weak self] active in
            self?.setOverlayActive(active.boolValue)
        }
        ComposeViewKt.observeSwitcherVisibility { [weak self] visible in
            self?.overlayWindow?.alphaValue = visible.boolValue ? 1 : 0
        }
        ComposeViewKt.observeInspectorVisible { [weak self] visible in
            self?.applyInspectorVisibility(visible.boolValue)
        }
        ComposeViewKt.observeShowMenubarIcon { [weak self] show in
            self?.applyShowMenubarIcon(show.boolValue)
        }
        ComposeViewKt.observeLaunchAtLogin { [weak self] enabled in
            self?.applyLaunchAtLogin(enabled.boolValue)
        }

        installGlobalKeyMonitor()
        installMouseDownMonitor()
    }

    /// Record every mouseDown delivered to another app — used by the
    /// panel's resignKey safety net to tell user clicks apart from
    /// system focus reassignment. In-app clicks are intentionally NOT
    /// monitored: a click on a switcher cell drives onCommit, which
    /// closes the session before resignKey fires (the deferred
    /// resignObs guards on `panel.isVisible` so the notification is a
    /// no-op there); an unusual click on the inspector while the
    /// switcher is open is rare enough that we don't optimise for it.
    private func installMouseDownMonitor() {
        let mask: NSEvent.EventTypeMask = [.leftMouseDown, .rightMouseDown, .otherMouseDown]
        globalMouseMonitor = NSEvent.addGlobalMonitorForEvents(matching: mask) { [weak self] event in
            self?.lastMouseDownTime = event.timestamp
        }
    }

    /// Watch tab/grave keyUp at the system level. Belt-and-braces with the
    /// Carbon kEventHotKeyReleased path and SwitcherOverlayWindow.sendEvent —
    /// either of those can silently miss events when the app isn't frontmost
    /// or when macOS doesn't deliver a hot-key Released for the combo. This
    /// global monitor reliably fires for any keyUp anywhere in the system,
    /// since AX permission is granted.
    private func installGlobalKeyMonitor() {
        globalKeyMonitor = NSEvent.addGlobalMonitorForEvents(matching: [.keyUp]) { [weak self] event in
            _ = self
            let kc = Int(event.keyCode)
            guard kc == kVK_Tab || kc == kVK_ANSI_Grave else { return }
            log("[global] keyup keyCode=\(kc)")
            DispatchQueue.main.async {
                ComposeViewKt.switcherController.onShortcutKeyReleased()
            }
        }
        if globalKeyMonitor == nil {
            log("[global] keyUp monitor failed (AX permission?)")
        }
    }

    /// Install the menubar icon on demand (also tears it down). Called from
    /// the showMenubarIcon flow observer.
    private func applyShowMenubarIcon(_ show: Bool) {
        let prev = showMenubarIconApplied
        showMenubarIconApplied = show
        if prev == show { return }
        if show {
            let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
            if let button = item.button {
                if let img = NSImage(named: "MenubarIcon") {
                    img.isTemplate = true
                    button.image = img
                } else {
                    button.title = "K⌥"
                }
                button.toolTip = "KAltSwitch"
            }
            item.menu = buildStatusItemMenu()
            statusItem = item
            log("[swift] menubar icon installed")
        } else {
            if let s = statusItem {
                NSStatusBar.system.removeStatusItem(s)
                statusItem = nil
                log("[swift] menubar icon removed")
            }
            menubarIconMenuItem = nil
            launchAtLoginMenuItem = nil
        }
    }

    /// Build the menubar item's menu. Stores refs to the toggle items so
    /// settings-side flips can update the checkmark via NSMenuItem.state.
    private func buildStatusItemMenu() -> NSMenu {
        let menu = NSMenu()

        let openItem = NSMenuItem(
            title: "Settings",
            action: #selector(openInspectorFromMenu),
            keyEquivalent: "")
        openItem.target = self
        menu.addItem(openItem)

        menu.addItem(.separator())

        let menubarToggle = NSMenuItem(
            title: "Show Menubar Icon",
            action: #selector(toggleMenubarIcon),
            keyEquivalent: "")
        menubarToggle.target = self
        menubarToggle.state = (showMenubarIconApplied ?? true) ? .on : .off
        menu.addItem(menubarToggle)
        menubarIconMenuItem = menubarToggle

        let launchToggle = NSMenuItem(
            title: "Launch at Login",
            action: #selector(toggleLaunchAtLogin),
            keyEquivalent: "")
        launchToggle.target = self
        launchToggle.state = (launchAtLoginApplied ?? false) ? .on : .off
        menu.addItem(launchToggle)
        launchAtLoginMenuItem = launchToggle

        menu.addItem(.separator())
        menu.addItem(NSMenuItem(
            title: "Quit KAltSwitch",
            action: #selector(NSApplication.terminate(_:)),
            keyEquivalent: "q"))
        return menu
    }

    @objc private func openInspectorFromMenu() {
        log("[swift] openInspector via menubar")
        showInspector()
    }

    @objc private func toggleMenubarIcon() {
        // Flip the persisted setting; the observer will tear down the
        // status item and stop the menu from being reachable until the user
        // re-enables it via the settings panel.
        let next = !(showMenubarIconApplied ?? true)
        log("[swift] toggleMenubarIcon -> \(next)")
        ComposeViewKt.store.setShowMenubarIcon(show: next)
    }

    @objc private func toggleLaunchAtLogin() {
        let next = !(launchAtLoginApplied ?? false)
        log("[swift] toggleLaunchAtLogin -> \(next)")
        ComposeViewKt.store.setLaunchAtLogin(enabled: next)
    }

    /// Sync `SMAppService` with the current `launchAtLogin` setting. Idempotent;
    /// also reflects the new state in the menu's checkmark.
    private func applyLaunchAtLogin(_ enabled: Bool) {
        let prev = launchAtLoginApplied
        launchAtLoginApplied = enabled
        launchAtLoginMenuItem?.state = enabled ? .on : .off

        // Don't poke SMAppService on the seed value if it already matches —
        // avoids a redundant register/unregister at every launch.
        let smState = SMAppService.mainApp.status
        let alreadyRegistered = (smState == .enabled)
        if prev == nil && alreadyRegistered == enabled {
            return
        }

        do {
            if enabled {
                try SMAppService.mainApp.register()
            } else if alreadyRegistered || smState == .requiresApproval {
                try SMAppService.mainApp.unregister()
            }
            log("[swift] launchAtLogin set to \(enabled), SMAppService.status=\(SMAppService.mainApp.status.rawValue)")
        } catch {
            log("[swift] launchAtLogin \(enabled) failed: \(error.localizedDescription)")
        }
    }

    /// Bring the inspector to front. Used by the menubar Open action and by
    /// `applicationShouldHandleReopen` (Spotlight / Dock / `open -a`).
    private func showInspector() {
        guard let window = window else { return }
        // If a switcher session is in flight, cancel it before stealing focus —
        // settings always win over an in-flight switch (per PLAN.md).
        ComposeViewKt.switcherController.onEsc()
        // Inspector is a heavyweight UI window — promote to .regular so
        // it gets a Dock icon + system cmd+tab presence while it's
        // open. Drops back to .accessory on willClose. Calling
        // setActivationPolicy is idempotent if we're already .regular,
        // so the menubar-while-already-open path is a no-op.
        setActivationPolicyForInspector(visible: true)
        window.makeKeyAndOrderFront(nil)
        NSApp.activate()
    }

    /// Toggle our activation policy in step with the inspector window's
    /// visibility. `.regular` while the window is on screen → Dock icon
    /// shows up, KAltSwitch appears in the system cmd+tab list. `.accessory`
    /// once it's closed → pure menubar utility again.
    private func setActivationPolicyForInspector(visible: Bool) {
        let target: NSApplication.ActivationPolicy = visible ? .regular : .accessory
        if NSApp.activationPolicy() == target { return }
        NSApp.setActivationPolicy(target)
        log("[swift] activation policy → \(visible ? "regular" : "accessory")")
    }

    /// Spotlight / Dock relaunch / `open -a` while we're already running.
    /// Surfaces the inspector — same path as the menubar Open action. Returning
    /// `true` tells AppKit we handled the reopen; otherwise it tries default
    /// behaviour (which does nothing useful for an LSUIElement-style agent).
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        log("[swift] applicationShouldHandleReopen hasVisibleWindows=\(flag)")
        showInspector()
        return true
    }

    private func setOverlayActive(_ active: Bool) {
        guard let panel = overlayWindow else { return }
        if active {
            panel.alphaValue = 0
            panel.sizeAndCenterOnActiveScreen()
            panel.orderFrontRegardless()
            panel.makeKey()
        } else {
            panel.orderOut(nil)
        }
    }

    private func currentInspectorWidth() -> Double {
        return (ComposeViewKt.store.inspectorWidth.value as? KotlinDouble)?.doubleValue ?? 480.0
    }

    /// Width the window should collapse to when the inspector is hidden.
    /// Reads from the saved frame; falls back to a sensible default for
    /// first-launch persistence.
    private func currentSettingsWidth() -> Double {
        return ((ComposeViewKt.store.windowFrame.value as? WindowFrame)?.width) ?? defaultSettingsOnlyWidth
    }

    private func persistFrame() {
        // Split the live window frame back into the persisted shape via the
        // Kotlin helper. Same min-inspector-width clamp (120) the inline
        // Swift version used to apply.
        let persisted = InspectorWindowLayoutKt.persistInspectorWindowLayout(
            currentFrame: window.frame.windowFrame,
            inspectorVisible: inspectorVisibleApplied == true,
            settingsWidth: currentSettingsWidth(),
            currentInspectorWidth: currentInspectorWidth(),
            minInspectorWidth: 120.0
        )
        ComposeViewKt.store.setWindowFrame(frame: persisted.settingsFrame)
        ComposeViewKt.store.saveInspectorWidth(width: persisted.inspectorWidth)
    }

    /// Inspector visibility transitioned (or just received its initial seed
    /// from the StateFlow). On first call only update the title and record
    /// the state. On subsequent state-changing calls, instantly grow or
    /// shrink the window by exactly `inspectorWidth` — origin.x/y/height
    /// unchanged so the window stretches/contracts toward the right edge.
    private func applyInspectorVisibility(_ visible: Bool) {
        guard let window = window else { return }
        // Title stays "Settings" whether the live-state right pane is
        // showing or not — the toggle in the sidebar is what tells the
        // user which mode they're in.
        window.title = "KAltSwitch — Settings"

        let prev = inspectorVisibleApplied
        inspectorVisibleApplied = visible
        if prev == nil || prev == visible {
            log("[swift] applyInspectorVisibility seed visible=\(visible)")
            return
        }

        let f = window.frame
        let targetFrame = InspectorWindowLayoutKt.inspectorVisibilityTargetFrame(
            currentFrame: f.windowFrame,
            visible: visible,
            inspectorWidth: currentInspectorWidth(),
            minSettingsWidth: 200.0
        )
        let target = targetFrame.nsRect
        log("[swift] applyInspectorVisibility prev=\(prev ?? false) -> visible=\(visible) " +
            "width \(f.size.width) -> \(target.size.width) (inspW=\(currentInspectorWidth()))")
        window.setFrame(target, display: true, animate: false)
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        // We're an alt-tab replacement — Carbon hotkeys keep working without
        // any window visible. Keep the process alive so cmd+tab still fires
        // after the user closes the inspector. The menubar `NSStatusItem` is
        // the way back in.
        return false
    }

    func applicationWillFinishLaunching(_ notification: Notification) {
        StderrRedirectKt.redirectStderrToLogFile()
        // Take over cmd+tab / cmd+shift+tab / cmd+` from the system. The
        // setting persists past process exit, so we pair this with three
        // restoration paths:
        //  1. `applicationWillTerminate` — normal Cocoa shutdown.
        //  2. `installSignalHandlers` — SIGINT/SIGTERM/SIGHUP via
        //     DispatchSource (off the signal context).
        //  3. `launchHotkeyWatchdog` — separate process that survives
        //     SIGSEGV / SIGKILL / kernel panic and restores via the same
        //     CGS API. This is the **only** layer that covers hard
        //     crashes; without it, after a crash the user is stuck
        //     without system cmd+tab until they relaunch + quit
        //     KAltSwitch once.
        setSymbolicHotKeysEnabled(false)
        installSignalHandlers()
        launchHotkeyWatchdog()
        installMainMenu()
    }

    /// Spawn `KAltSwitchWatchdog` (bundled in `Contents/MacOS/`). The
    /// watchdog uses `kqueue NOTE_EXIT` to wait for our pid to die and
    /// then calls `_CGSSetSymbolicHotKeyEnabled(_, true)` for the same
    /// hot keys we disabled. See `macosApp/Watchdog/main.swift`.
    ///
    /// Failures here (binary missing, spawn refused) are non-fatal —
    /// log and proceed; we just degrade to the pre-iter25 behaviour
    /// (graceful + signal exits restore; hard crashes don't).
    private func launchHotkeyWatchdog() {
        guard let path = Bundle.main.path(forAuxiliaryExecutable: "KAltSwitchWatchdog") else {
            log("[swift] watchdog binary not found in bundle — hard-crash recovery disabled")
            return
        }
        let process = Process()
        process.executableURL = URL(fileURLWithPath: path)
        process.arguments = [String(ProcessInfo.processInfo.processIdentifier)]
        // Don't share stdio. The watchdog logs via NSLog and we don't
        // want its output mingled with ours, plus inheriting our
        // redirected file descriptors complicates things.
        process.standardInput = FileHandle.nullDevice
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        do {
            try process.run()
            watchdogProcess = process
            log("[swift] watchdog launched, pid=\(process.processIdentifier)")
        } catch {
            log("[swift] watchdog launch failed: \(error.localizedDescription)")
        }
    }

    /// Catch SIGINT/SIGTERM/SIGHUP (e.g. `kill <pid>`, terminal Ctrl-C, logout)
    /// and run the same teardown as applicationWillTerminate before exiting.
    /// Pattern from alt-tab-macos's `Sigtrap`: ignore the default action with
    /// `signal(_:SIG_IGN)` so the process isn't killed before our handler runs,
    /// then observe via `DispatchSourceSignal` (off the actual signal context,
    /// so it's safe to call non-async-signal-safe APIs like CGS).
    private func installSignalHandlers() {
        let sigs: [Int32] = [SIGINT, SIGTERM, SIGHUP]
        for sig in sigs { signal(sig, SIG_IGN) }
        for sig in sigs {
            let src = DispatchSource.makeSignalSource(signal: sig, queue: .main)
            src.setEventHandler { [weak self] in
                NSLog("KAltSwitch: received signal %d, restoring symbolic hotkeys and exiting", sig)
                self?.gracefulShutdown()
                exit(0)
            }
            src.resume()
            signalSources.append(src)
        }
    }

    /// Tear down anything that has cross-process side-effects. Must be safe
    /// to run from a dispatch queue (i.e. not a raw signal handler).
    private func gracefulShutdown() {
        hotkeyController?.stop()
        // Most important: restore the system's command-tab so the user isn't
        // stuck without a switcher after we're gone.
        setSymbolicHotKeysEnabled(true)
    }

    /// Standard macOS main menu: app menu (About / Hide / Quit) + Window menu
    /// (Close / Minimize / Zoom). Without it, cmd+Q/cmd+W/cmd+M don't reach
    /// the responder chain. Only fires when our app is frontmost (i.e. the
    /// inspector is in front) — the switcher panel is `.nonactivatingPanel`,
    /// so our menu doesn't show during a switcher session and the menu
    /// shortcuts can't accidentally fire on the inspected app.
    private func installMainMenu() {
        let appName = ProcessInfo.processInfo.processName
        let mainMenu = NSMenu()

        let appMenuItem = NSMenuItem()
        mainMenu.addItem(appMenuItem)
        let appMenu = NSMenu()
        appMenu.addItem(NSMenuItem(
            title: "About \(appName)",
            action: #selector(NSApplication.orderFrontStandardAboutPanel(_:)),
            keyEquivalent: ""))
        appMenu.addItem(.separator())
        appMenu.addItem(NSMenuItem(
            title: "Hide \(appName)",
            action: #selector(NSApplication.hide(_:)),
            keyEquivalent: "h"))
        let hideOthers = NSMenuItem(
            title: "Hide Others",
            action: #selector(NSApplication.hideOtherApplications(_:)),
            keyEquivalent: "h")
        hideOthers.keyEquivalentModifierMask = [.command, .option]
        appMenu.addItem(hideOthers)
        appMenu.addItem(NSMenuItem(
            title: "Show All",
            action: #selector(NSApplication.unhideAllApplications(_:)),
            keyEquivalent: ""))
        appMenu.addItem(.separator())
        appMenu.addItem(NSMenuItem(
            title: "Quit \(appName)",
            action: #selector(NSApplication.terminate(_:)),
            keyEquivalent: "q"))
        appMenuItem.submenu = appMenu

        let windowMenuItem = NSMenuItem()
        mainMenu.addItem(windowMenuItem)
        let windowMenu = NSMenu(title: "Window")
        windowMenu.addItem(NSMenuItem(
            title: "Close",
            action: #selector(NSWindow.performClose(_:)),
            keyEquivalent: "w"))
        windowMenu.addItem(NSMenuItem(
            title: "Minimize",
            action: #selector(NSWindow.performMiniaturize(_:)),
            keyEquivalent: "m"))
        windowMenu.addItem(NSMenuItem(
            title: "Zoom",
            action: #selector(NSWindow.performZoom(_:)),
            keyEquivalent: ""))
        windowMenuItem.submenu = windowMenu
        NSApp.windowsMenu = windowMenu

        NSApp.mainMenu = mainMenu
    }

    func applicationWillTerminate(_ notification: Notification) {
        let center = NotificationCenter.default
        for obs in frameObservers { center.removeObserver(obs) }
        frameObservers.removeAll()
        if let m = globalKeyMonitor {
            NSEvent.removeMonitor(m)
            globalKeyMonitor = nil
        }
        if let m = globalMouseMonitor {
            NSEvent.removeMonitor(m)
            globalMouseMonitor = nil
        }
        hotkeyController?.stop()
        appRegistry?.stop()
        overlayComposeDelegate?.destroy()
        composeDelegate?.destroy()
        // Hand cmd+tab / cmd+shift+tab / cmd+` back to macOS.
        setSymbolicHotKeysEnabled(true)
    }
}

/// AppKit `NSRect` ↔ Kotlin `WindowFrame` adapters. The math itself lives in
/// `InspectorWindowLayout.kt`; these are just primitive-shape converters that
/// keep the Swift host's `NSWindow` access tidy.
private extension NSRect {
    var windowFrame: WindowFrame {
        WindowFrame(
            x: Double(origin.x),
            y: Double(origin.y),
            width: Double(size.width),
            height: Double(size.height)
        )
    }
}

private extension WindowFrame {
    var nsRect: NSRect {
        NSRect(x: x, y: y, width: width, height: height)
    }
}
