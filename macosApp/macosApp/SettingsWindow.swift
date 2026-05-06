import AppKit
import Carbon.HIToolbox

/// `NSWindow` subclass for the Settings window. Same rationale as
/// `InspectorWindow`: intercept cmd+W / cmd+Q / cmd+M / cmd+H before
/// the Compose NSView's keyDown override silently consumes them. See
/// the `InspectorWindow` doc comment for the full story.
final class SettingsWindow: NSWindow {

    private static let relevantModifiers: NSEvent.ModifierFlags =
        [.command, .shift, .option, .control]

    override func performKeyEquivalent(with event: NSEvent) -> Bool {
        let modifiers = event.modifierFlags.intersection(SettingsWindow.relevantModifiers)
        guard modifiers == .command else {
            return super.performKeyEquivalent(with: event)
        }
        switch Int(event.keyCode) {
        case kVK_ANSI_W:
            performClose(nil); return true
        case kVK_ANSI_Q:
            NSApp.terminate(nil); return true
        case kVK_ANSI_M:
            performMiniaturize(nil); return true
        case kVK_ANSI_H:
            NSApp.hide(nil); return true
        default:
            return super.performKeyEquivalent(with: event)
        }
    }
}
