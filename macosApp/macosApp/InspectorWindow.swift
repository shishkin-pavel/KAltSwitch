import AppKit

/// `NSWindow` subclass for the Settings/Inspector. The only reason it
/// exists is to intercept `cmd+W`, `cmd+Q`, `cmd+M`, `cmd+H` before they
/// reach the responder chain.
///
/// **Why an override is needed.** The shortcuts are present in
/// `NSApp.mainMenu` (`installMainMenu` in `AppDelegate`) with the standard
/// AppKit actions, and *should* fire via the normal performKeyEquivalent
/// chain — first responder → responder chain → window → NSApp's main
/// menu. In practice, with the Compose-Multiplatform `NSView` host
/// (Skia-rendered scene + custom keyDown override) sitting as the first
/// responder, those cmd-key events are silently consumed before the main
/// menu sees them. Concretely the user reported that the four shortcuts
/// worked fine inside the switcher overlay (which has its own
/// `onPreviewKeyEvent` interception of Q/W/M/H/F → `SwitcherAction`) but
/// were no-ops on the inspector window.
///
/// Overriding `performKeyEquivalent` on the inspector window puts our
/// hook *before* the Compose NSView gets a turn (window-level dispatch
/// runs first). We only intercept exactly cmd+key — cmd+shift+W,
/// cmd+option+H ("Hide Others") and similar combos still walk the
/// standard chain so the main menu's existing key equivalents for those
/// keep working.
final class InspectorWindow: NSWindow {

    /// macOS cmd-key combo modifiers we care about. Anything outside this
    /// set (capsLock, function key, etc.) is ignored when comparing.
    private static let relevantModifiers: NSEvent.ModifierFlags =
        [.command, .shift, .option, .control]

    override func performKeyEquivalent(with event: NSEvent) -> Bool {
        let modifiers = event.modifierFlags.intersection(InspectorWindow.relevantModifiers)
        guard modifiers == .command else {
            return super.performKeyEquivalent(with: event)
        }
        switch event.charactersIgnoringModifiers?.lowercased() {
        case "w":
            performClose(nil); return true
        case "q":
            NSApp.terminate(nil); return true
        case "m":
            performMiniaturize(nil); return true
        case "h":
            NSApp.hide(nil); return true
        default:
            return super.performKeyEquivalent(with: event)
        }
    }
}
