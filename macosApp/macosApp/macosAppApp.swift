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

    func applicationDidFinishLaunching(_ aNotification: Notification) {
        window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 800, height: 600),
            styleMask: [.titled, .closable, .miniaturizable, .resizable],
            backing: .buffered,
            defer: false
        )
        window.title = "KAltSwitch"
        window.center()

        composeDelegate = ComposeViewKt.AttachMainComposeView(window: window)

        // Restore the inspector window's last position/size, if any. K/N's StateFlow
        // exposes `value` as `Any?`, so cast to the concrete frame type.
        if let saved = ComposeViewKt.store.inspectorFrame.value as? WindowFrame {
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
        ComposeViewKt.store.saveInspectorFrame(
            x: Double(f.origin.x),
            y: Double(f.origin.y),
            width: Double(f.size.width),
            height: Double(f.size.height)
        )
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        return true
    }

    func applicationWillFinishLaunching(_ notification: Notification) {
        redirectStderrToLogFile()
        // Take over cmd+tab / cmd+shift+tab / cmd+` from the system. The setting
        // persists past process exit, so we always pair this with restoration in
        // applicationWillTerminate (and accept that a crash leaves the user in
        // a broken state until next launch).
        setSymbolicHotKeysEnabled(false)
        installMainMenu()
        composeDelegate?.create()
        composeDelegate?.start()
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
