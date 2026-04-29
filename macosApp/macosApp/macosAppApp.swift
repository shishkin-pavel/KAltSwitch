import SwiftUI
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
    /// Latest applied inspector-visibility state. `nil` until the first
    /// observeInspectorVisible callback fires; afterwards used by
    /// persistFrame() to route saves to the right slot, and by the toggle
    /// observer to detect state transitions vs the initial seed emission.
    private var inspectorVisibleApplied: Bool? = nil
    /// Default widths used the first time the user toggles to a state we
    /// have no saved frame for. Origin/height are kept from the current
    /// window so only the width changes.
    private let defaultWithInspectorWidth: CGFloat = 800
    private let defaultSettingsOnlyWidth: CGFloat = 320

    func applicationDidFinishLaunching(_ aNotification: Notification) {
        window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 800, height: 600),
            styleMask: [.titled, .closable, .miniaturizable, .resizable],
            backing: .buffered,
            defer: false
        )
        // Initial title — overwritten as soon as observeInspectorVisible
        // delivers the persisted state below.
        window.title = "KAltSwitch — Settings/Inspector"
        window.center()
        // Closing or cmd+W on the inspector hides it instead of releasing.
        // The app keeps running (see applicationShouldTerminateAfterLastWindowClosed)
        // so cmd+tab still works; the menubar item or Spotlight relaunch can
        // bring the inspector back.
        window.isReleasedWhenClosed = false

        composeDelegate = ComposeViewKt.AttachMainComposeView(window: window)

        // Restore the appropriate window frame for whichever pane layout
        // (settings-only vs settings+inspector) was last in use. K/N's
        // StateFlow exposes `value` as `Any?`, so cast to the concrete type.
        let initialInspectorVisible = (ComposeViewKt.store.inspectorVisible.value as? KotlinBoolean)?.boolValue ?? true
        let initialFrame: WindowFrame? = initialInspectorVisible
            ? ComposeViewKt.store.inspectorFrame.value as? WindowFrame
            : ComposeViewKt.store.settingsFrame.value as? WindowFrame
        if let saved = initialFrame {
            let restored = NSRect(x: saved.x, y: saved.y, width: saved.width, height: saved.height)
            if NSScreen.screens.contains(where: { $0.visibleFrame.intersects(restored) }) {
                window.setFrame(restored, display: false)
            }
        }

        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)

        // Persist user-driven moves and resizes back to config.json.
        let center = NotificationCenter.default
        let move = center.addObserver(forName: NSWindow.didMoveNotification, object: window, queue: .main) { [weak self] _ in
            self?.persistFrame()
        }
        let resize = center.addObserver(forName: NSWindow.didEndLiveResizeNotification, object: window, queue: .main) { [weak self] _ in
            self?.persistFrame()
        }
        frameObservers = [move, resize]

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
            NSLog("KAltSwitch: onCommitActivation pid=%d wid=%@",
                  pid.int32Value,
                  windowId.map { String($0.int64Value) } ?? "nil")
            self?.overlayWindow?.orderOut(nil)
            registry?.commit(pid: pid_t(truncatingIfNeeded: pid.int32Value),
                             windowId: windowId?.int64Value)
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
        overlayComposeDelegate?.create()
        overlayComposeDelegate?.start()

        panel.onCommandReleased = { [weak controller] in
            controller?.onModifierReleased()
        }
        panel.onShortcutKeyReleased = { [weak controller] in
            controller?.onShortcutKeyReleased()
        }
        // Safety net: if the panel ever loses key status while a session is
        // live (e.g. user clicked another app's window with the mouse), close
        // the session via Esc semantics. Without this the controller's
        // switcherActive flag could stay true indefinitely, blocking all
        // subsequent activation events from reaching the inspector and
        // freezing the row order on stale state.
        let resignObs = NotificationCenter.default.addObserver(
            forName: NSWindow.didResignKeyNotification,
            object: panel,
            queue: .main
        ) { [weak controller] _ in
            controller?.onEsc()
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

        installStatusItem()
    }

    /// Add a menubar icon so the user can reopen the inspector after closing
    /// it (the app stays alive without any visible windows since
    /// `applicationShouldTerminateAfterLastWindowClosed` returns false).
    private func installStatusItem() {
        let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        if let button = item.button {
            // Bundled MenubarIcon imageset (white-on-transparent, marked
            // template-rendering-intent in Contents.json) — macOS auto-tints
            // for light/dark menubar.
            if let img = NSImage(named: "MenubarIcon") {
                img.isTemplate = true
                button.image = img
            } else {
                button.title = "K⌥"
            }
            button.toolTip = "KAltSwitch"
        }
        let menu = NSMenu()
        let openItem = NSMenuItem(
            title: "Open Inspector",
            action: #selector(openInspectorFromMenu),
            keyEquivalent: "")
        openItem.target = self
        menu.addItem(openItem)
        menu.addItem(.separator())
        menu.addItem(NSMenuItem(
            title: "Quit KAltSwitch",
            action: #selector(NSApplication.terminate(_:)),
            keyEquivalent: "q"))
        item.menu = menu
        statusItem = item
    }

    @objc private func openInspectorFromMenu() {
        showInspector()
    }

    /// Bring the inspector to front. Used by the menubar Open action and by
    /// `applicationShouldHandleReopen` (Spotlight / Dock / `open -a`).
    private func showInspector() {
        guard let window = window else { return }
        // If a switcher session is in flight, cancel it before stealing focus —
        // settings always win over an in-flight switch (per PLAN.md).
        ComposeViewKt.switcherController.onEsc()
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    /// Spotlight / Dock relaunch / `open -a` while we're already running.
    /// Surfaces the inspector — same path as the menubar Open action. Returning
    /// `true` tells AppKit we handled the reopen; otherwise it tries default
    /// behaviour (which does nothing useful for an LSUIElement-style agent).
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
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

    private func persistFrame() {
        let f = window.frame
        // Route the save to whichever slot matches the *currently displayed*
        // pane layout. Until the first observeInspectorVisible callback
        // settles inspectorVisibleApplied, default to the with-inspector
        // slot — that's the launch-time layout if we have any saved frame
        // at all.
        let toInspectorSlot = inspectorVisibleApplied ?? true
        if toInspectorSlot {
            ComposeViewKt.store.saveInspectorFrame(
                x: Double(f.origin.x),
                y: Double(f.origin.y),
                width: Double(f.size.width),
                height: Double(f.size.height)
            )
        } else {
            ComposeViewKt.store.saveSettingsFrame(
                x: Double(f.origin.x),
                y: Double(f.origin.y),
                width: Double(f.size.width),
                height: Double(f.size.height)
            )
        }
    }

    /// Inspector visibility transitioned (or just received its initial seed
    /// from the StateFlow). On first call, only update the title and record
    /// the state. On subsequent calls that change state, save the current
    /// frame to the *outgoing* slot, then resize the window to the
    /// *incoming* slot's frame (or a sensible default if none saved yet).
    private func applyInspectorVisibility(_ visible: Bool) {
        guard let window = window else { return }
        window.title = visible
            ? "KAltSwitch — Settings/Inspector"
            : "KAltSwitch — Settings"

        let prev = inspectorVisibleApplied
        inspectorVisibleApplied = visible
        if prev == nil || prev == visible { return }

        // Save current frame to the OUTGOING slot before switching.
        let f = window.frame
        if prev == true {
            ComposeViewKt.store.saveInspectorFrame(
                x: Double(f.origin.x), y: Double(f.origin.y),
                width: Double(f.size.width), height: Double(f.size.height))
        } else {
            ComposeViewKt.store.saveSettingsFrame(
                x: Double(f.origin.x), y: Double(f.origin.y),
                width: Double(f.size.width), height: Double(f.size.height))
        }

        // Load the INCOMING slot's frame, or fall back to a width-only
        // default. Keep origin.x stable so the window grows/shrinks toward
        // the right edge — origin.y unchanged so vertical position holds.
        let incoming: WindowFrame? = visible
            ? ComposeViewKt.store.inspectorFrame.value as? WindowFrame
            : ComposeViewKt.store.settingsFrame.value as? WindowFrame
        let target: NSRect
        if let saved = incoming,
           NSScreen.screens.contains(where: { $0.visibleFrame.intersects(NSRect(x: saved.x, y: saved.y, width: saved.width, height: saved.height)) }) {
            target = NSRect(x: saved.x, y: saved.y, width: saved.width, height: saved.height)
        } else {
            var r = window.frame
            r.size.width = visible ? defaultWithInspectorWidth : defaultSettingsOnlyWidth
            target = r
        }
        window.setFrame(target, display: true, animate: true)
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        // We're an alt-tab replacement — Carbon hotkeys keep working without
        // any window visible. Keep the process alive so cmd+tab still fires
        // after the user closes the inspector. The menubar `NSStatusItem` is
        // the way back in.
        return false
    }

    func applicationWillFinishLaunching(_ notification: Notification) {
        redirectStderrToLogFile()
        // Take over cmd+tab / cmd+shift+tab / cmd+` from the system. The setting
        // persists past process exit, so we always pair this with restoration
        // in applicationWillTerminate AND in our SIGINT/SIGTERM/SIGHUP handler.
        // A hard crash (SIGSEGV, SIGKILL, kernel panic) still leaves the user
        // without system cmd+tab until next launch — documented in
        // docs/decisions.md.
        setSymbolicHotKeysEnabled(false)
        installSignalHandlers()
        installMainMenu()
        composeDelegate?.create()
        composeDelegate?.start()
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

    /// Force NSLog/print/stderr to ~/Library/Logs/KAltSwitch.log, regardless of how
    /// the app was launched. Without this, double-clicking the .app or running it
    /// from the IDE sends stderr to /dev/null, hiding all our diagnostics.
    ///
    /// Each launch starts with a clearly-greppable separator so a multi-day
    /// log can be sliced down to the most recent session in one shell command:
    /// `awk '/^=== SESSION START/{buf=""}{buf=buf$0"\n"}END{print buf}' KAltSwitch.log`
    /// or just `tail -n +"$(grep -n '^=== SESSION START' KAltSwitch.log | tail -1 | cut -d: -f1)" KAltSwitch.log`.
    private func redirectStderrToLogFile() {
        let fm = FileManager.default
        guard let libraryLogs = fm.urls(for: .libraryDirectory, in: .userDomainMask).first?
            .appendingPathComponent("Logs", isDirectory: true) else { return }
        try? fm.createDirectory(at: libraryLogs, withIntermediateDirectories: true)
        let logPath = libraryLogs.appendingPathComponent("KAltSwitch.log").path
        // "a" = append. Both stderr and stdout get the same file so NSLog output
        // (stderr) and any print() output (stdout) sit side-by-side.
        freopen(logPath, "a", stderr)
        freopen(logPath, "a", stdout)
        setbuf(stderr, nil)
        setbuf(stdout, nil)
        let pid = ProcessInfo.processInfo.processIdentifier
        let bundleVersion = (Bundle.main.infoDictionary?["CFBundleVersion"] as? String) ?? "unknown"
        let bundleShortVersion = (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String) ?? "unknown"
        let date = ISO8601DateFormatter().string(from: Date())
        // print() with explicit \n so the marker stands alone on a line; NSLog
        // would prefix every line with a timestamp + pid.
        print("=== SESSION START at \(date) pid=\(pid) version=\(bundleShortVersion) build=\(bundleVersion) log=\(logPath)")
        fflush(stdout)
    }

    func applicationDidBecomeActive(_ notification: Notification) {
        composeDelegate?.resume()
    }

    func applicationDidResignActive(_ notification: Notification) {
        composeDelegate?.pause()
    }

    func applicationWillTerminate(_ notification: Notification) {
        let center = NotificationCenter.default
        for obs in frameObservers { center.removeObserver(obs) }
        frameObservers.removeAll()
        hotkeyController?.stop()
        appRegistry?.stop()
        overlayComposeDelegate?.stop()
        overlayComposeDelegate?.destroy()
        composeDelegate?.stop()
        composeDelegate?.destroy()
        // Hand cmd+tab / cmd+shift+tab / cmd+` back to macOS.
        setSymbolicHotKeysEnabled(true)
    }
}
