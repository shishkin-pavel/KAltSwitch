import Foundation
import CoreGraphics

/// Private SkyLight (CGS) APIs we use to disable macOS's built-in command-tab /
/// command-shift-tab / command-key-above-tab hot keys, so our Carbon
/// registrations get the events instead.
///
/// The "symbolic hotkey" IDs (1, 2, 6) and the `CGSSetSymbolicHotKeyEnabled`
/// signature are documented by the alt-tab-macos project — there is no
/// public Apple documentation. The setting **persists past process exit**, so
/// we always restore on `applicationWillTerminate`. If the app crashes mid-run
/// the user is left with a broken cmd+tab; relaunching the app fixes it.

@_silgen_name("CGSSetSymbolicHotKeyEnabled")
@discardableResult
private func _CGSSetSymbolicHotKeyEnabled(_ hotKey: Int32, _ isEnabled: Bool) -> CGError

enum SymbolicHotKey: Int32, CaseIterable {
    case commandTab = 1
    case commandShiftTab = 2
    /// "key above tab" — covers cmd+` (US/UK) and cmd+§ etc. on other layouts.
    case commandKeyAboveTab = 6
}

func setSymbolicHotKeysEnabled(_ enabled: Bool, _ keys: [SymbolicHotKey] = SymbolicHotKey.allCases) {
    for key in keys {
        let err = _CGSSetSymbolicHotKeyEnabled(key.rawValue, enabled)
        if err != .success {
            NSLog("KAltSwitch: CGSSetSymbolicHotKeyEnabled(%d, %@) failed: %d",
                  key.rawValue, enabled ? "true" : "false", err.rawValue)
        }
    }
}
