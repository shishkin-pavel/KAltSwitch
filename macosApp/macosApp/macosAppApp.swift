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
    private var settingsWindow: SettingsWindow? = nil
    private var inspectorWindow: InspectorWindow? = nil
    private var settingsCompose: ComposeNSViewDelegate? = nil
    private var inspectorCompose: ComposeNSViewDelegate? = nil
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
    /// Global keyboard monitor — third path for detecting tab/grave keyUp
    /// (after Carbon Released and panel sendEvent). Needs AX permission to
    /// receive global events.
    private var globalKeyMonitor: Any? = nil
    /// Global NSEvent mouse-down monitor. Writes `lastMouseDownTime`
    /// so the panel's resignKey safety net can tell a user-driven click
    /// from a system focus reassignment, even when cmd is still held.
    private var globalMouseMonitor: Any? = nil
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
    /// KVO context for `NSApp.effectiveAppearance`.
    private var appearanceObservation: NSKeyValueObservation? = nil

    func applicationDidFinishLaunching(_ aNotification: Notification) {
        // Bring up the per-app AX watchers. Store is the singleton owned by the framework.
        let registry = AppRegistry(store: ComposeViewKt.store)
        appRegistry = registry
        registry.start()

        // Wire the Kotlin switcher controller's AX-side actions to AppRegistry.
        let controller = ComposeViewKt.switcherController
        controller.onCommitActivation = { [weak self, weak registry] pid, windowId in
            // Hide the overlay BEFORE asking macOS to activate the target app.
            // `orderOut` only marks the panel removed; the WindowServer takes
            // pixels off-screen on the next CA tick. Yielding one runloop
            // turn between orderOut and the synchronous SkyLight/AX/NSApp
            // activate calls lets the panel disappear before the main thread
            // blocks on a slow target.
            let widStr = windowId.map { String($0.int64Value) } ?? "nil"
            log("[swift] onCommitActivation pid=\(pid.int32Value) wid=\(widStr)")
            self?.overlayWindow?.orderOut(nil)
            let p = pid_t(truncatingIfNeeded: pid.int32Value)
            let wid = windowId?.int64Value
            DispatchQueue.main.async { [weak registry] in
                registry?.commit(pid: p, windowId: wid)
            }
        }
        controller.onRaiseFocusedWindow = { [weak registry] pid, windowId in
            registry?.raise(pid: pid_t(truncatingIfNeeded: pid.int32Value),
                            windowId: windowId.int64Value)
        }
        controller.onPerformAction = { [weak registry] action, pid, windowId in
            guard let registry = registry else { return }
            let p = pid_t(truncatingIfNeeded: pid.int32Value)
            let wid = windowId?.int64Value
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

        registry.onAxTrustChanged = { [weak self] trusted in
            guard trusted else { return }
            self?.hotkeyController?.start()
            self?.hotkeyController?.reinstallFlagsChangedTap()
        }

        // Switcher overlay panel. Built eagerly so showing has zero startup cost.
        let panel = SwitcherOverlayWindow()
        overlayWindow = panel
        overlayComposeDelegate = ComposeViewKt.AttachSwitcherOverlay(window: panel)
        if let composeView = panel.contentView {
            panel.installComposeView(composeView)
        }
        ComposeViewKt.observeSwitcherPanelSize(
            onChange: { [weak panel] w, h in
                panel?.setContentSize(widthPts: CGFloat(w.doubleValue), heightPts: CGFloat(h.doubleValue))
            },
            onCleared: { /* no-op */ },
        )

        panel.onCommandReleased = { [weak controller] in
            controller?.onModifierReleased()
        }
        panel.onShortcutKeyReleased = { [weak controller] in
            controller?.onShortcutKeyReleased()
        }
        panel.onAction = { [weak controller] action in
            controller?.onAction(action: action)
        }
        let resignObs = NotificationCenter.default.addObserver(
            forName: NSWindow.didResignKeyNotification,
            object: panel,
            queue: .main
        ) { [weak controller, weak panel, weak self] _ in
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { [weak controller, weak panel, weak self] in
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
            if visible.boolValue {
                self?.overlayWindow?.requestAlphaVisible()
            } else {
                self?.overlayWindow?.requestAlphaHidden()
            }
        }
        ComposeViewKt.observeShowMenubarIcon { [weak self] show in
            self?.applyShowMenubarIcon(show.boolValue)
        }
        ComposeViewKt.observeLaunchAtLogin { [weak self] enabled in
            self?.applyLaunchAtLogin(enabled.boolValue)
        }

        installGlobalKeyMonitor()
        installMouseDownMonitor()
        installAppearanceObserver()
    }

    // MARK: - Settings + Inspector windows

    private func ensureSettingsWindow() -> SettingsWindow {
        if let w = settingsWindow { return w }
        let w = SettingsWindow(
            contentRect: NSRect(x: 0, y: 0, width: 640, height: 540),
            styleMask: [.titled, .closable, .miniaturizable, .resizable],
            backing: .buffered,
            defer: false
        )
        w.title = "KAltSwitch — Settings"
        w.isReleasedWhenClosed = false
        settingsCompose = ComposeViewKt.AttachSettingsView(window: w)
        // Restore persisted frame.
        if let saved = ComposeViewKt.store.settingsWindowFrame.value as? WindowFrame {
            let rect = saved.nsRect
            if NSScreen.screens.contains(where: { $0.visibleFrame.intersects(rect) }) {
                w.setFrame(rect, display: false)
            } else {
                w.center()
            }
        } else {
            w.center()
        }
        // Persist user-driven moves and resizes back to config.json.
        let center = NotificationCenter.default
        let move = center.addObserver(forName: NSWindow.didMoveNotification, object: w, queue: .main) { [weak self] _ in
            self?.persistSettingsFrame()
        }
        let resize = center.addObserver(forName: NSWindow.didEndLiveResizeNotification, object: w, queue: .main) { [weak self] _ in
            self?.persistSettingsFrame()
        }
        let willClose = center.addObserver(forName: NSWindow.willCloseNotification, object: w, queue: .main) { [weak self] _ in
            self?.refreshActivationPolicy()
        }
        frameObservers.append(contentsOf: [move, resize, willClose])
        settingsWindow = w
        return w
    }

    private func ensureInspectorWindow() -> InspectorWindow {
        if let w = inspectorWindow { return w }
        let w = InspectorWindow(
            contentRect: NSRect(x: 0, y: 0, width: 720, height: 600),
            styleMask: [.titled, .closable, .miniaturizable, .resizable],
            backing: .buffered,
            defer: false
        )
        w.title = "KAltSwitch — Inspector"
        w.isReleasedWhenClosed = false
        inspectorCompose = ComposeViewKt.AttachInspectorView(window: w)
        if let saved = ComposeViewKt.store.inspectorWindowFrame.value as? WindowFrame {
            let rect = saved.nsRect
            if NSScreen.screens.contains(where: { $0.visibleFrame.intersects(rect) }) {
                w.setFrame(rect, display: false)
            } else {
                w.center()
            }
        } else {
            w.center()
        }
        let center = NotificationCenter.default
        let move = center.addObserver(forName: NSWindow.didMoveNotification, object: w, queue: .main) { [weak self] _ in
            self?.persistInspectorFrame()
        }
        let resize = center.addObserver(forName: NSWindow.didEndLiveResizeNotification, object: w, queue: .main) { [weak self] _ in
            self?.persistInspectorFrame()
        }
        let willClose = center.addObserver(forName: NSWindow.willCloseNotification, object: w, queue: .main) { [weak self] _ in
            self?.refreshActivationPolicy()
        }
        frameObservers.append(contentsOf: [move, resize, willClose])
        inspectorWindow = w
        return w
    }

    private func persistSettingsFrame() {
        guard let w = settingsWindow else { return }
        let f = w.frame
        ComposeViewKt.store.saveSettingsWindowFrame(
            x: Double(f.origin.x),
            y: Double(f.origin.y),
            width: Double(f.size.width),
            height: Double(f.size.height)
        )
    }

    private func persistInspectorFrame() {
        guard let w = inspectorWindow else { return }
        let f = w.frame
        ComposeViewKt.store.saveInspectorWindowFrame(
            x: Double(f.origin.x),
            y: Double(f.origin.y),
            width: Double(f.size.width),
            height: Double(f.size.height)
        )
    }

    @objc private func openSettingsFromMenu() {
        showSettings()
    }

    @objc private func openInspectorFromMenu() {
        showInspector()
    }

    private func showSettings() {
        ComposeViewKt.switcherController.onEsc()
        let w = ensureSettingsWindow()
        refreshActivationPolicy(promoteForVisibleWindow: true)
        w.makeKeyAndOrderFront(nil)
        NSApp.activate()
    }

    private func showInspector() {
        ComposeViewKt.switcherController.onEsc()
        let w = ensureInspectorWindow()
        refreshActivationPolicy(promoteForVisibleWindow: true)
        w.makeKeyAndOrderFront(nil)
        NSApp.activate()
    }

    /// Bring activation policy in line with the union of "any of our heavy
    /// windows is on screen". `.regular` while one is open → Dock icon
    /// shows up; `.accessory` once both are closed → pure menubar utility.
    private func refreshActivationPolicy(promoteForVisibleWindow: Bool = false) {
        let anyVisible = (settingsWindow?.isVisible == true) ||
                         (inspectorWindow?.isVisible == true) ||
                         promoteForVisibleWindow
        let target: NSApplication.ActivationPolicy = anyVisible ? .regular : .accessory
        if NSApp.activationPolicy() == target { return }
        NSApp.setActivationPolicy(target)
    }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        // Default reopen surface = Settings.
        showSettings()
        return true
    }

    // MARK: - Theme observation

    /// KVO on `NSApp.effectiveAppearance` so the Compose-side palette
    /// flips live when the user toggles macOS Dark Mode (or the system
    /// follows time-of-day). The seed is pushed before any of our
    /// windows is shown so the first paint already matches the user's
    /// system theme.
    private func installAppearanceObserver() {
        pushAppearance(NSApp.effectiveAppearance)
        appearanceObservation = NSApp.observe(\.effectiveAppearance, options: [.new]) { [weak self] _, change in
            guard let appearance = change.newValue ?? NSApp.effectiveAppearance as NSAppearance? else { return }
            self?.pushAppearance(appearance)
        }
    }

    private func pushAppearance(_ appearance: NSAppearance) {
        let isDark = appearance.bestMatch(from: [.aqua, .darkAqua]) == .darkAqua
        ComposeViewKt.store.setIsDarkMode(dark: isDark)
    }

    // MARK: - Misc monitors

    private func installMouseDownMonitor() {
        let mask: NSEvent.EventTypeMask = [.leftMouseDown, .rightMouseDown, .otherMouseDown]
        globalMouseMonitor = NSEvent.addGlobalMonitorForEvents(matching: mask) { [weak self] event in
            self?.lastMouseDownTime = event.timestamp
        }
    }

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

    // MARK: - Menubar status item

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

    private func buildStatusItemMenu() -> NSMenu {
        let menu = NSMenu()

        let settingsItem = NSMenuItem(
            title: "Settings…",
            action: #selector(openSettingsFromMenu),
            keyEquivalent: ",")
        settingsItem.target = self
        menu.addItem(settingsItem)

        let inspectorItem = NSMenuItem(
            title: "Inspector",
            action: #selector(openInspectorFromMenu),
            keyEquivalent: "")
        inspectorItem.target = self
        menu.addItem(inspectorItem)

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

    @objc private func toggleMenubarIcon() {
        let next = !(showMenubarIconApplied ?? true)
        ComposeViewKt.store.setShowMenubarIcon(show: next)
    }

    @objc private func toggleLaunchAtLogin() {
        let next = !(launchAtLoginApplied ?? false)
        ComposeViewKt.store.setLaunchAtLogin(enabled: next)
    }

    private func applyLaunchAtLogin(_ enabled: Bool) {
        let prev = launchAtLoginApplied
        launchAtLoginApplied = enabled
        launchAtLoginMenuItem?.state = enabled ? .on : .off

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

    // MARK: - Switcher overlay

    private func setOverlayActive(_ active: Bool) {
        guard let panel = overlayWindow else { return }
        if active {
            panel.alphaValue = 0
            panel.ignoresMouseEvents = true
            panel.captureSessionScreen()
            panel.orderFrontRegardless()
            panel.makeKey()
        } else {
            panel.orderOut(nil)
            panel.clearSessionState()
        }
    }

    // MARK: - Lifecycle

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        return false
    }

    func applicationWillFinishLaunching(_ notification: Notification) {
        StderrRedirectKt.redirectStderrToLogFile()
        setSymbolicHotKeysEnabled(false)
        installSignalHandlers()
        launchHotkeyWatchdog()
        installMainMenu()
    }

    private func launchHotkeyWatchdog() {
        guard let path = Bundle.main.path(forAuxiliaryExecutable: "KAltSwitchWatchdog") else {
            log("[swift] watchdog binary not found in bundle — hard-crash recovery disabled")
            return
        }
        let process = Process()
        process.executableURL = URL(fileURLWithPath: path)
        process.arguments = [String(ProcessInfo.processInfo.processIdentifier)]
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

    private func gracefulShutdown() {
        hotkeyController?.stop()
        setSymbolicHotKeysEnabled(true)
    }

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
        // Settings… (cmd+,) — standard macOS shortcut.
        let settingsMenuItem = NSMenuItem(
            title: "Settings…",
            action: #selector(openSettingsFromMenu),
            keyEquivalent: ",")
        settingsMenuItem.target = self
        appMenu.addItem(settingsMenuItem)
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
        overlayWindow?.clearSessionState()
        if let m = globalKeyMonitor {
            NSEvent.removeMonitor(m)
            globalKeyMonitor = nil
        }
        if let m = globalMouseMonitor {
            NSEvent.removeMonitor(m)
            globalMouseMonitor = nil
        }
        appearanceObservation?.invalidate()
        appearanceObservation = nil
        hotkeyController?.stop()
        appRegistry?.stop()
        overlayComposeDelegate?.destroy()
        settingsCompose?.destroy()
        inspectorCompose?.destroy()
        setSymbolicHotKeysEnabled(true)
    }
}

/// AppKit `NSRect` ↔ Kotlin `WindowFrame` adapters.
private extension WindowFrame {
    var nsRect: NSRect {
        NSRect(x: x, y: y, width: width, height: height)
    }
}
