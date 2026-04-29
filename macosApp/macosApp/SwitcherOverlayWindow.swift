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

    /// NSVisualEffectView placed behind the Compose-rendered rounded
    /// panel. Sized and positioned dynamically from a Compose-side flow
    /// (`observeSwitcherPanelSize`) so it tracks whatever the layout
    /// produces — number of apps, FlowRow wrap behaviour, etc. Hidden
    /// (alphaValue = 0) when there's no active session so the empty panel
    /// margins don't show a stray blur halo.
    private(set) var blurView: NSVisualEffectView?

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
    /// is attached: we re-parent the Compose NSView into a wrapper that has
    /// an [NSVisualEffectView] underneath. The blur frame is driven from
    /// Compose's reported panel size so the rounded backdrop hugs the
    /// visible Compose surface rather than smearing across the whole panel.
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

        let blur = NSVisualEffectView(frame: .zero)
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

    /// Position and size the blur backdrop. `sizeDp` comes from Compose,
    /// converted to points (Compose dp == AppKit point on macOS). Pass
    /// nil to hide the backdrop (between sessions).
    func updateBlurFrame(widthPts: CGFloat?, heightPts: CGFloat?) {
        guard let blur = blurView else { return }
        guard let w = widthPts, let h = heightPts, w > 0, h > 0 else {
            blur.alphaValue = 0
            return
        }
        let bounds = contentView?.bounds ?? frame
        let origin = NSPoint(
            x: (bounds.size.width - w) / 2,
            y: (bounds.size.height - h) / 2,
        )
        blur.frame = NSRect(origin: origin, size: NSSize(width: w, height: h))
        blur.alphaValue = 1
        log("[panel] blur frame -> \(blur.frame)")
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

    /// Resize to a generous fraction of the active screen and center on it.
    /// The Compose layout inside wraps to the actual content size so excess
    /// area stays fully transparent.
    func sizeAndCenterOnActiveScreen() {
        let mouse = NSEvent.mouseLocation
        let screen = NSScreen.screens.first(where: { NSMouseInRect(mouse, $0.frame, false) })
            ?? NSScreen.main
            ?? NSScreen.screens.first
        guard let screen = screen else { return }
        let s = screen.visibleFrame
        let width = s.size.width * 0.9
        let height = min(640, s.size.height * 0.7)
        let origin = NSPoint(
            x: s.origin.x + (s.size.width - width) / 2,
            y: s.origin.y + (s.size.height - height) / 2
        )
        setFrame(NSRect(origin: origin, size: NSSize(width: width, height: height)), display: false)
    }

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
