import AppKit
import ComposeAppMac

/// Borderless, transparent, non-activating `NSPanel` that hosts the Compose
/// switcher overlay. Floats above other windows at `.popUpMenu` level. Becomes
/// key on show so we can intercept Esc / arrow / ` keystrokes without stealing
/// app focus from the user's frontmost app.
///
/// Constructed once at launch and kept alive for the lifetime of the process —
/// shown/hidden via `orderFrontRegardless` / `orderOut` driven by
/// `ComposeViewKt.observeSwitcherVisibility`.
final class SwitcherOverlayWindow: NSPanel {
    init() {
        super.init(
            contentRect: NSRect(x: 0, y: 0, width: 1100, height: 220),
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )
        level = .popUpMenu
        isOpaque = false
        backgroundColor = .clear
        hasShadow = true
        isFloatingPanel = true
        hidesOnDeactivate = false
        isMovableByWindowBackground = false
        collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .transient]
        // We set the actual frame on each show — initial origin doesn't matter.
        center()
    }

    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { false }

    /// Position centred on the screen that currently owns the cursor — that is
    /// almost always the screen the user is interacting with.
    func centerOnActiveScreen() {
        let mouse = NSEvent.mouseLocation
        let screen = NSScreen.screens.first(where: { NSMouseInRect(mouse, $0.frame, false) })
            ?? NSScreen.main
            ?? NSScreen.screens.first
        guard let screen = screen else { return }
        let f = frame
        let s = screen.visibleFrame
        let origin = NSPoint(
            x: s.origin.x + (s.size.width - f.size.width) / 2,
            y: s.origin.y + (s.size.height - f.size.height) / 2
        )
        setFrameOrigin(origin)
    }
}
