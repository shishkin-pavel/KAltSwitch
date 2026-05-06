import AppKit
import ApplicationServices
import Carbon.HIToolbox
import ComposeAppMac

/// Borderless, transparent, non-activating `NSPanel` that hosts the Compose
/// switcher overlay. Floats above other windows at `.popUpMenu` level.
///
/// Becomes key as soon as a switcher session starts (even before `showDelay`
/// elapses) so we can observe modifier-state changes via [sendEvent] — the
/// flagsChanged events are delivered to whichever window holds key status, so
/// being key from t=0 is how we detect a quick cmd-tap-and-release without
/// needing an AX-gated `CGEventTap`.
///
/// During the `showDelay` window the panel is kept at `alphaValue = 0`
/// so it's both invisible and shadow-less while Compose lays out and
/// drives `setContentSize`. The Kotlin `observeSwitcherVisibility` flow
/// flips alpha to 1 once the delay has elapsed.
final class SwitcherOverlayWindow: NSPanel {
    /// Called when a `.flagsChanged` event observed via `sendEvent` reports
    /// that command is no longer held. The AppDelegate wires this to
    /// `SwitcherController.onModifierReleased`.
    var onCommandReleased: (() -> Void)?

    /// Called when the alt-key (tab or grave/backtick) is released while the
    /// command modifier is still held. Lets the Kotlin controller distinguish
    /// "user finished holding the shortcut" from "OS keyboard auto-repeat
    /// firing the Carbon hotkey again" — both look identical at the Carbon
    /// API level. AppDelegate wires this to
    /// `SwitcherController.onShortcutKeyReleased`.
    var onShortcutKeyReleased: (() -> Void)?

    /// Called from `performKeyEquivalent` for cmd+Q / cmd+W / cmd+M /
    /// cmd+H / cmd+F while the panel is key. AppDelegate wires this to
    /// `SwitcherController.onAction(action:)` so the action fires on the
    /// switcher's currently-selected target. See the `performKeyEquivalent`
    /// override below for why we intercept here rather than relying on
    /// Compose's `onPreviewKeyEvent` path.
    var onAction: ((SwitcherAction) -> Void)?

    /// The Compose-host NSView (set up by `ComposeNSViewDelegate`,
    /// re-parented into our wrapper by `installComposeView`). Since
    /// iter48 it is sized to the *captured screen's visibleFrame* and
    /// repositioned within the wrapper on every `setFrame` so its
    /// origin in screen coordinates stays pinned to that screen rect.
    /// That decouples the Compose scene's coordinate system from the
    /// NSPanel's window position — resizing/recentering the panel
    /// no longer drags Compose content along, which used to manifest
    /// as a brief left/right shimmer when the window's left edge
    /// shifted to keep the panel centered on screen.
    private(set) var composeView: NSView?

    /// `visibleFrame` of the screen the cursor was on at session start.
    /// Used by `setContentSize` to recenter the panel after each
    /// content-driven resize. Captured once per session so the panel
    /// doesn't follow the cursor onto a different display mid-session.
    private var sessionScreenFrame: NSRect?

    /// Latest "where to open" preference pushed from
    /// `observeSwitcherWindowPlacement`. Read at session start by
    /// `captureSessionScreen`. Carried as the Kotlin enum's `.name`
    /// — `"MouseScreen"`, `"ActiveWindowScreen"`, `"MainScreen"` —
    /// so the K/N bridge stays a plain String. Default is the
    /// historical mouse-cursor behaviour, used until the first push
    /// from the observer arrives.
    var placementMode: String = "MouseScreen"

    /// Pending shrink work item. When Compose reports a smaller target
    /// size we keep the panel at the larger envelope until the cell
    /// `animateBounds` motion completes (≈200 ms in
    /// `SwitcherOverlay.kt`'s `tileMotion`), then shrink. New size
    /// reports cancel any in-flight shrink and reschedule.
    private var pendingShrink: DispatchWorkItem?

    /// Window top-Y (in screen coordinates) captured on the first
    /// `setContentSize` call of a session. Computed as though the
    /// panel were vertically centred on `sessionScreenFrame` at that
    /// freshly-measured content size; every subsequent placement
    /// during the same session keeps this top edge stable so existing
    /// rows don't visually drift when a new row appears below or
    /// vanishes from the bottom. `nil` between sessions.
    private var capturedTopY: CGFloat?

    /// Cell-animation duration matching `SwitcherOverlay.kt`'s
    /// `tileMotion = tween(200ms)`, plus a small safety margin so the
    /// last interpolation tick has rendered before the panel snaps.
    private static let shrinkDelay: TimeInterval = 0.22

    /// Size the panel was last set to during this process run. Used
    /// as the initial frame in the next session's
    /// `captureSessionScreen` so consecutive opens of the switcher
    /// re-use the previously settled size. Updated by the
    /// `setFrame` override below on every frame change. Persists
    /// for the SwitcherOverlayWindow instance's lifetime (= the app
    /// process) — not written to disk. Initial value: 90 % × 90 %
    /// of `NSScreen.main`'s visible frame at app launch.
    private var lastUsedSize: NSSize = {
        let s = NSScreen.main?.visibleFrame.size
            ?? NSScreen.screens.first?.visibleFrame.size
            ?? NSSize(width: 1280, height: 800)
        return NSSize(width: s.width * 0.9, height: s.height * 0.9)
    }()

    init() {
        super.init(
            contentRect: NSRect(x: 0, y: 0, width: 1200, height: 480),
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )
        level = .popUpMenu
        isOpaque = false
        backgroundColor = .clear
        // Compose draws its own shadow inside its rounded box — disable the
        // window-level shadow so the empty panel area doesn't leak a shadow
        // outline through transparent content.
        hasShadow = false
        isFloatingPanel = true
        hidesOnDeactivate = false
        isMovableByWindowBackground = false
        ignoresMouseEvents = false
        alphaValue = 0
        collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .transient]
    }

    /// Install the panel's content hierarchy. Called once after the
    /// Compose contentView is attached: we re-parent the Compose
    /// NSView into a plain wrapper that autoresizes with the panel.
    /// Compose's own `.background(Color(0xFF1B1B1F))` + `.border(...)`
    /// inside the visible Box draws the panel's backdrop.
    ///
    /// Compose host (composeView) is screen-sized (set in
    /// `captureSessionScreen`) with `autoresizingMask = []`; the
    /// `setFrame` override repositions it on every NSPanel frame
    /// change so its screen origin stays pinned to the session
    /// screen's `visibleFrame.origin`.
    func installComposeView(_ composeView: NSView) {
        let wrapper = NSView(frame: contentView?.bounds ?? composeView.bounds)
        wrapper.autoresizingMask = [.width, .height]

        composeView.autoresizingMask = []
        wrapper.addSubview(composeView)

        contentView = wrapper
        self.composeView = composeView
        log("[panel] compose view installed wrapperBounds=\(wrapper.bounds)")
    }

    /// Resolve which screen this session should open on, honouring the
    /// user's [placementMode] preference.
    ///
    /// * `MouseScreen` (default) — display under the cursor at session start.
    /// * `ActiveWindowScreen` — display containing the frontmost app's AX
    ///   focused window. Falls through to `MouseScreen` if AX is denied,
    ///   the front app has no focused window, or the window's centre lands
    ///   off every screen (e.g. minimised, off-screen).
    /// * `MainScreen` — the macOS *primary* display (`NSScreen.screens[0]`),
    ///   the one with the menu bar in the Displays arrangement panel.
    ///   Note: this is intentionally NOT `NSScreen.main`, which Apple
    ///   defines as "screen of the key window" — that's effectively the
    ///   same as `ActiveWindowScreen` and would make this option redundant.
    ///
    /// Every branch ends in the same `NSScreen.main ?? NSScreen.screens.first`
    /// fallback so a misconfigured / disconnected setup still yields *some*
    /// screen rather than refusing to open the panel.
    private func pickSessionScreen() -> NSScreen? {
        let fallback = NSScreen.main ?? NSScreen.screens.first
        switch placementMode {
        case "MainScreen":
            return NSScreen.screens.first ?? fallback
        case "ActiveWindowScreen":
            if let s = screenForFrontmostFocusedWindow() { return s }
            return screenUnderMouse() ?? fallback
        default: // "MouseScreen" + any unrecognised value
            return screenUnderMouse() ?? fallback
        }
    }

    private func screenUnderMouse() -> NSScreen? {
        let mouse = NSEvent.mouseLocation
        return NSScreen.screens.first(where: { NSMouseInRect(mouse, $0.frame, false) })
            ?? NSScreen.main
            ?? NSScreen.screens.first
    }

    /// Read the frontmost app's AX focused window, compute its centre point,
    /// and return the `NSScreen` whose `frame` contains that point. Returns
    /// `nil` for any failure mode (no front app, no AX permission, no focused
    /// window, missing pos/size, point outside every screen) — the caller
    /// falls back to the mouse-cursor screen, matching the long-standing
    /// behaviour of every other AX path in this app.
    ///
    /// AX uses top-left-origin "screen" coordinates (y grows downward, anchored
    /// to the primary screen's TOP edge). `NSScreen.frame` uses bottom-left
    /// Cocoa coordinates (y grows upward). We convert via the primary screen's
    /// height before doing `containsPoint`.
    private func screenForFrontmostFocusedWindow() -> NSScreen? {
        guard let frontmost = NSWorkspace.shared.frontmostApplication else { return nil }
        let pid = frontmost.processIdentifier
        let appEl = AXUIElementCreateApplication(pid)
        var focusedRef: AnyObject?
        let err = AXUIElementCopyAttributeValue(appEl, kAXFocusedWindowAttribute as CFString, &focusedRef)
        guard err == .success,
              let v = focusedRef,
              CFGetTypeID(v as CFTypeRef) == AXUIElementGetTypeID() else { return nil }
        let win = v as! AXUIElement

        var posRef: AnyObject?
        var sizeRef: AnyObject?
        guard AXUIElementCopyAttributeValue(win, kAXPositionAttribute as CFString, &posRef) == .success,
              AXUIElementCopyAttributeValue(win, kAXSizeAttribute as CFString, &sizeRef) == .success,
              let posV = posRef, let sizeV = sizeRef,
              CFGetTypeID(posV as CFTypeRef) == AXValueGetTypeID(),
              CFGetTypeID(sizeV as CFTypeRef) == AXValueGetTypeID() else { return nil }

        var p = CGPoint.zero
        var s = CGSize.zero
        AXValueGetValue(posV as! AXValue, .cgPoint, &p)
        AXValueGetValue(sizeV as! AXValue, .cgSize, &s)

        // AX → Cocoa coordinate flip, anchored to the primary screen height.
        guard let primary = NSScreen.screens.first else { return nil }
        let primaryHeight = primary.frame.size.height
        let centerCocoa = NSPoint(
            x: p.x + s.width / 2,
            y: primaryHeight - (p.y + s.height / 2)
        )
        return NSScreen.screens.first(where: { NSMouseInRect(centerCocoa, $0.frame, false) })
    }

    /// Cache the screen chosen by [pickSessionScreen] so subsequent
    /// `setContentSize` calls can recenter the panel without following
    /// the cursor (or any other live signal) to a different display
    /// mid-session, and pre-size the panel to a generous fraction of
    /// that screen so the first Compose composition has room to lay
    /// out cells in their natural single-row arrangement. Without the
    /// pre-size, Compose's first layout would inherit whatever
    /// (potentially small) width the panel ended up at after the
    /// previous session, and FlowRow would wrap prematurely.
    /// Subsequent `setContentSize` calls shrink the panel to the
    /// actual content width.
    ///
    /// Resets `capturedTopY` so the next `setContentSize` will sample
    /// it from the screen-centred placement at the freshly-measured
    /// first content size.
    func captureSessionScreen() {
        let screen = pickSessionScreen()
        guard let screen = screen else { return }
        let s = screen.visibleFrame
        sessionScreenFrame = s
        capturedTopY = nil
        // Compose host spans the full session screen so its scene
        // coordinate system covers everywhere the panel might end
        // up. setFrame's override below pins this view's *screen*
        // origin to s.origin regardless of where the NSPanel itself
        // sits in window-server coords.
        composeView?.frame = NSRect(origin: .zero, size: s.size)
        // Re-use the panel size from the previous session — every
        // setFrame writes lastUsedSize, and on first launch it's
        // initialized to 0.9 × NSScreen.main. The initial frame is
        // invisible anyway (alpha=0 during showDelay) and gets
        // replaced by the first onPanelSize from Compose, but
        // matching the previous size means consecutive opens start
        // from the right shape — no transient flash on the path to
        // the cached natural width.
        let origin = NSPoint(
            x: s.origin.x + (s.size.width - lastUsedSize.width) / 2,
            y: s.origin.y + (s.size.height - lastUsedSize.height) / 2
        )
        setFrame(NSRect(origin: origin, size: lastUsedSize), display: false)
    }

    /// Reposition the Compose host NSView inside our wrapper so its
    /// origin in screen coordinates stays anchored to
    /// `sessionScreenFrame.origin`. Called from `setFrame` (override
    /// below) on every NSPanel frame change. With this in place,
    /// Compose's scene coordinates are screen-stable: when the NSPanel
    /// resizes/recenters during a content change, the Compose buffer
    /// doesn't visually drag along — fixes the brief left/right
    /// shimmer the user reported on multi-row transitions.
    private func updateComposeViewPosition() {
        guard let scene = sessionScreenFrame, let cv = composeView else { return }
        cv.frame.origin = NSPoint(
            x: scene.origin.x - frame.origin.x,
            y: scene.origin.y - frame.origin.y
        )
    }

    override func setFrame(_ frameRect: NSRect, display flag: Bool) {
        super.setFrame(frameRect, display: flag)
        lastUsedSize = frameRect.size
        updateComposeViewPosition()
    }

    /// Reset session-screen state and cancel any pending shrink.
    /// Called on `setOverlayActive(false)` so a cleared session can't
    /// later wake up and resize a hidden panel.
    func clearSessionState() {
        pendingShrink?.cancel()
        pendingShrink = nil
        sessionScreenFrame = nil
        capturedTopY = nil
    }

    /// Called from observeSwitcherVisibility when Compose flips
    /// `ui.visible` to true (i.e. showDelay has elapsed). Reveals
    /// the panel and stops swallowing mouse events. Until Compose's
    /// first render lands the metal layer holds the previous (empty)
    /// frame, so alpha=1 before that render is a transparent panel —
    /// no visible artefact to gate on.
    func requestAlphaVisible() {
        alphaValue = 1
        ignoresMouseEvents = false
    }

    /// Called from observeSwitcherVisibility when `ui.visible` flips
    /// to false (session ended). Hides + re-enables click-through.
    func requestAlphaHidden() {
        alphaValue = 0
        ignoresMouseEvents = true
    }

    /// Resize the panel to the Compose-reported visible content size,
    /// recentering on the session screen. Runs on every `onPanelSize`
    /// callback. Two-step grow/shrink envelope:
    ///
    /// * Grow: panel jumps to the new (larger) size immediately so the
    ///   incoming Compose layout fits.
    /// * Shrink: panel stays at the current (envelope) size while
    ///   Compose's `tileMotion` animates each cell to its new
    ///   position via `animateBounds`. After ~220 ms (matching the
    ///   200 ms tween + safety margin) the panel snaps to the smaller
    ///   target. Without this delay the cells in flight would be
    ///   clipped at the panel's right/bottom edges as it shrinks.
    ///
    /// Successive shrinks reschedule the pending work item, so a fast
    /// burst of layout changes still ends with one final resize at
    /// the right size. During `showDelay` (panel alpha=0) we skip
    /// the delay and resize immediately — the user can't see the
    /// transition, and Compose has no in-flight `animateBounds` on
    /// the very first layout.
    func setContentSize(widthPts: CGFloat, heightPts: CGFloat) {
        guard widthPts > 0, heightPts > 0 else { return }
        guard let screen = sessionScreenFrame else { return }

        let target = NSSize(width: widthPts, height: heightPts)
        let current = frame.size

        pendingShrink?.cancel()
        pendingShrink = nil

        // First call after session start (Compose's first layout):
        // sample `capturedTopY` from the screen-centred placement at
        // the freshly-measured content size, then jump straight to it.
        // No envelope dance — there are no in-flight cells to protect
        // on the very first layout.
        if capturedTopY == nil {
            capturedTopY = screen.origin.y + (screen.size.height + target.height) / 2
            applyFrame(size: target)
            return
        }

        let envelope = NSSize(
            width: max(current.width, target.width),
            height: max(current.height, target.height)
        )

        if envelope != current {
            applyFrame(size: envelope)
        }

        if target != envelope {
            let work = DispatchWorkItem { [weak self] in
                guard let self = self else { return }
                self.applyFrame(size: target)
                self.pendingShrink = nil
            }
            pendingShrink = work
            DispatchQueue.main.asyncAfter(
                deadline: .now() + SwitcherOverlayWindow.shrinkDelay,
                execute: work
            )
        }
    }

    /// Place the panel at `size`, horizontally centred on the captured
    /// session screen and with its top edge anchored at `capturedTopY`.
    /// Both anchors are constant for the lifetime of the session, so
    /// growing/shrinking expands and contracts the panel only along
    /// its bottom edge — existing rows keep their on-screen position.
    private func applyFrame(size: NSSize) {
        guard let screen = sessionScreenFrame, let topY = capturedTopY else { return }
        let centerX = screen.origin.x + screen.size.width / 2
        let newOrigin = NSPoint(
            x: centerX - size.width / 2,
            y: topY - size.height
        )
        setFrame(NSRect(origin: newOrigin, size: size), display: false)
    }

    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { false }

    /// Catch modifier-state transitions and tab/grave keyUp events globally.
    /// `flagsChanged` and key events flow to the key window; with
    /// `canBecomeKey = true` and the panel made key on session start, both
    /// fire for every relevant press/release while the session is live — no
    /// AX permission required.
    ///
    /// Carbon's `RegisterEventHotKey` consumes the cmd+tab keyDown at the
    /// dispatcher level, but the matching keyUp is *not* a hotkey event and
    /// reaches us normally. That's the signal we need to tell auto-repeat
    /// from a held key.
    /// Intercept cmd+Q/W/M/H/F while the panel is key and route them to
    /// the switcher controller's `onAction`. Without this, NSApp's main
    /// menu (set up in `AppDelegate.installMainMenu`) sees `cmd+Q` first
    /// and **terminates KAltSwitch itself** instead of quitting the app
    /// the switcher is highlighting. Same hazard for `cmd+W` (Close
    /// would close our overlay panel), `cmd+M` (Minimize), `cmd+H`
    /// (Hide).
    ///
    /// Window-level `performKeyEquivalent` runs **before** NSApp's main
    /// menu lookup, so returning `true` here pre-empts the menu handler.
    /// Modifier check is exact-`.command`-only — `cmd+option+H`
    /// ("Hide Others") and similar combos fall through to standard
    /// dispatch.
    override func performKeyEquivalent(with event: NSEvent) -> Bool {
        let modifiers = event.modifierFlags.intersection(
            [.command, .shift, .option, .control]
        )
        guard modifiers == .command else {
            return super.performKeyEquivalent(with: event)
        }
        // Match by physical keyCode (kVK_ANSI_*), not by
        // `charactersIgnoringModifiers`. The latter is layout-dependent —
        // on the Russian (or any non-ANSI) layout, physical Q emits "й"
        // and the switch silently falls through to NSApp.mainMenu →
        // terminate, killing KAltSwitch instead of the highlighted app.
        // kVK_* codes are raw scan codes, identical across all layouts.
        let action: SwitcherAction? = {
            switch Int(event.keyCode) {
            case kVK_ANSI_Q: return .quitapp
            case kVK_ANSI_W: return .closewindow
            case kVK_ANSI_M: return .toggleminimize
            case kVK_ANSI_H: return .togglehide
            case kVK_ANSI_F: return .togglefullscreen
            default: return nil
            }
        }()
        if let action = action {
            log("[panel] cmd-key keyCode=\(event.keyCode) → action=\(action)")
            onAction?(action)
            return true
        }
        return super.performKeyEquivalent(with: event)
    }

    override func sendEvent(_ event: NSEvent) {
        if event.type == .flagsChanged {
            let cmdHeld = event.modifierFlags.contains(.command)
            log("[diag-panel] sendEvent flagsChanged cmdHeld=\(cmdHeld) isKey=\(isKeyWindow) isVisible=\(isVisible)")
            if !cmdHeld {
                log("[panel] cmd released")
                onCommandReleased?()
            }
        } else if event.type == .keyUp {
            let kc = Int(event.keyCode)
            if kc == kVK_Tab || kc == kVK_ANSI_Grave {
                log("[panel] keyup keyCode=\(kc)")
                onShortcutKeyReleased?()
            }
        }
        super.sendEvent(event)
    }
}
