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
            if !cmdHeld { onCommandReleased?() }
        } else if event.type == .keyUp {
            let kc = Int(event.keyCode)
            if kc == kVK_Tab || kc == kVK_ANSI_Grave {
                onShortcutKeyReleased?()
            }
        }
        super.sendEvent(event)
    }
}
