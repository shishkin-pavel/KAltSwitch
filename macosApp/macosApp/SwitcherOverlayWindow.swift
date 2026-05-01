import AppKit
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
/// During the `showDelay` window the panel is sized large but kept at
/// `alphaValue = 0` so it's both invisible and shadow-less. The Kotlin
/// `observeSwitcherVisibility` flow flips alpha to 1 once the delay has elapsed.
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

    /// NSVisualEffectView placed behind the Compose-rendered rounded
    /// panel. Since iter40 the panel itself is sized to the visible
    /// content via `setContentSize`, so the blur fills the entire
    /// content view via autoresizing — no separate frame math. Toggled
    /// to alpha=0 between sessions via `setBlurVisible`.
    private(set) var blurView: NSVisualEffectView?

    /// `visibleFrame` of the screen the cursor was on at session start.
    /// Used by `setContentSize` to recenter the panel after each
    /// content-driven resize. Captured once per session so the panel
    /// doesn't follow the cursor onto a different display mid-session.
    private var sessionScreenFrame: NSRect?

    /// Pending shrink work item. When Compose reports a smaller target
    /// size we keep the panel at the larger envelope until the cell
    /// `animateBounds` motion completes (≈200 ms in
    /// `SwitcherOverlay.kt`'s `tileMotion`), then shrink. New size
    /// reports cancel any in-flight shrink and reschedule.
    private var pendingShrink: DispatchWorkItem?

    /// Cell-animation duration matching `SwitcherOverlay.kt`'s
    /// `tileMotion = tween(200ms)`, plus a small safety margin so the
    /// last interpolation tick has rendered before the panel snaps.
    private static let shrinkDelay: TimeInterval = 0.22

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

    /// Install the blur backdrop. Called once after the Compose contentView
    /// is attached: we re-parent the Compose NSView into a wrapper that
    /// has an [NSVisualEffectView] underneath. The blur fills the wrapper
    /// via autoresizing — since iter40 the panel itself is sized to the
    /// visible content, so panel-bounds == visible-rect.
    ///
    /// Rounded corners go via `maskImage` (a 9-slice rounded rect) instead
    /// of `layer.cornerRadius + masksToBounds`. The `cornerRadius` route
    /// breaks NSVisualEffectView's blending on some macOS versions —
    /// behaviour ranges from "no blur, just solid colour" to "rectangular
    /// blur with rounded shadow". `maskImage` is the documented API and
    /// works consistently from 10.10 onwards.
    func installBlurBackdrop(under composeView: NSView) {
        let wrapper = NSView(frame: contentView?.bounds ?? composeView.bounds)
        wrapper.autoresizingMask = [.width, .height]

        let blur = NSVisualEffectView(frame: wrapper.bounds)
        blur.autoresizingMask = [.width, .height]
        blur.material = .hudWindow
        blur.blendingMode = .behindWindow
        blur.state = .active
        blur.maskImage = SwitcherOverlayWindow.roundedMaskImage(radius: 16)
        blur.alphaValue = 0   // session not active yet
        wrapper.addSubview(blur)

        composeView.frame = wrapper.bounds
        composeView.autoresizingMask = [.width, .height]
        wrapper.addSubview(composeView)

        contentView = wrapper
        blurView = blur
        log("[panel] blur backdrop installed wrapperBounds=\(wrapper.bounds)")
    }

    /// Show/hide the blur backdrop. Since the panel is sized to the
    /// visible content, "hide" is just alpha=0 — no frame math.
    func setBlurVisible(_ visible: Bool) {
        blurView?.alphaValue = visible ? 1 : 0
    }

    /// Cache the screen the cursor is on at session start so subsequent
    /// `setContentSize` calls can recenter the panel without following
    /// the cursor to a different display mid-session.
    func captureSessionScreen() {
        let mouse = NSEvent.mouseLocation
        let screen = NSScreen.screens.first(where: { NSMouseInRect(mouse, $0.frame, false) })
            ?? NSScreen.main
            ?? NSScreen.screens.first
        sessionScreenFrame = screen?.visibleFrame
    }

    /// Reset session-screen state and cancel any pending shrink.
    /// Called on `setOverlayActive(false)` so a cleared session can't
    /// later wake up and resize a hidden panel.
    func clearSessionState() {
        pendingShrink?.cancel()
        pendingShrink = nil
        sessionScreenFrame = nil
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
    /// the right size. During `showDelay` (panel alpha=0, blur alpha=0)
    /// we skip the delay and resize immediately — the user can't see
    /// the transition, and Compose has no in-flight `animateBounds`
    /// on the very first layout.
    func setContentSize(widthPts: CGFloat, heightPts: CGFloat) {
        guard widthPts > 0, heightPts > 0 else { return }
        guard let screen = sessionScreenFrame ?? fallbackScreenFrame() else { return }

        let target = NSSize(width: widthPts, height: heightPts)
        let current = frame.size

        pendingShrink?.cancel()
        pendingShrink = nil

        // showDelay or first layout of a fresh session: blur is still
        // invisible, no animateBounds is in flight inside Compose, so
        // there's nothing to keep the panel large for. Snap straight
        // to target.
        let blurVisible = (blurView?.alphaValue ?? 0) > 0.5
        if !blurVisible {
            applyFrame(size: target, on: screen)
            return
        }

        let envelope = NSSize(
            width: max(current.width, target.width),
            height: max(current.height, target.height)
        )

        if envelope != current {
            applyFrame(size: envelope, on: screen)
        }

        if target != envelope {
            let work = DispatchWorkItem { [weak self] in
                guard let self = self, let s = self.sessionScreenFrame else { return }
                self.applyFrame(size: target, on: s)
                self.pendingShrink = nil
            }
            pendingShrink = work
            DispatchQueue.main.asyncAfter(
                deadline: .now() + SwitcherOverlayWindow.shrinkDelay,
                execute: work
            )
        }
    }

    private func applyFrame(size: NSSize, on screen: NSRect) {
        let origin = NSPoint(
            x: screen.origin.x + (screen.size.width - size.width) / 2,
            y: screen.origin.y + (screen.size.height - size.height) / 2
        )
        setFrame(NSRect(origin: origin, size: size), display: false)
    }

    private func fallbackScreenFrame() -> NSRect? {
        let mouse = NSEvent.mouseLocation
        return (NSScreen.screens.first(where: { NSMouseInRect(mouse, $0.frame, false) })
            ?? NSScreen.main
            ?? NSScreen.screens.first)?.visibleFrame
    }

    /// 9-slice rounded-rect mask image for [NSVisualEffectView.maskImage].
    /// `capInsets` matching `radius` keeps the corners crisp regardless of
    /// the view's runtime size.
    private static func roundedMaskImage(radius: CGFloat) -> NSImage {
        let edge = radius * 2 + 1
        let img = NSImage(size: NSSize(width: edge, height: edge), flipped: false) { rect in
            NSColor.black.setFill()
            NSBezierPath(roundedRect: rect, xRadius: radius, yRadius: radius).fill()
            return true
        }
        img.capInsets = NSEdgeInsets(top: radius, left: radius, bottom: radius, right: radius)
        img.resizingMode = .stretch
        return img
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
