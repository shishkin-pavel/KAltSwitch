import Foundation
import CoreGraphics
import ApplicationServices
import Carbon.HIToolbox

/// Private SkyLight / CGS / HIServices APIs.
///
/// macOS exposes no public way to:
///   1. Disable the system's built-in cmd+tab / cmd+shift+tab / cmd+\` hot
///      keys (we want our Carbon registrations to win) — `CGSSetSymbolicHotKeyEnabled`.
///   2. Switch which app is frontmost from a background, non-active process
///      — `_SLPSSetFrontProcessWithOptions` + `SLPSPostEventRecordTo`. The
///      public `NSRunningApplication.activate` silently no-ops on macOS 14+
///      when the caller isn't already the active app, which is exactly our
///      situation (we're a non-activating panel host).
///   3. Resolve a `CGWindowID` from an `AXUIElement` so we can pass the
///      target window to the CGS focus call — `_AXUIElementGetWindow`.
///   4. Convert a pid to a `ProcessSerialNumber` for the CGS call —
///      `GetProcessForPID` (deprecated since 10.9, still present).
///
/// All four signatures and the `SLPSMode` constants are taken from
/// alt-tab-macos's `SkyLight.framework.swift` and
/// `ApplicationServices.HIServices.framework.swift`. The `makeKeyWindow`
/// byte-event trick traces to https://github.com/Hammerspoon/hammerspoon/issues/370
/// .
///
/// We accept these as MVP-era private dependencies. The fully public
/// alternative (LSUIElement agent app + activating panel) is documented as a
/// post-MVP option in `docs/window-state-attributes.md` §8.

// MARK: - Symbolic hot keys

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

// MARK: - Cross-process focus

@_silgen_name("_AXUIElementGetWindow")
@discardableResult
func _AXUIElementGetWindow(_ axUiElement: AXUIElement, _ wid: UnsafeMutablePointer<CGWindowID>) -> AXError

@_silgen_name("GetProcessForPID")
@discardableResult
func GetProcessForPID(_ pid: pid_t, _ psn: UnsafeMutablePointer<ProcessSerialNumber>) -> OSStatus

@_silgen_name("_SLPSSetFrontProcessWithOptions")
@discardableResult
func _SLPSSetFrontProcessWithOptions(
    _ psn: UnsafeMutablePointer<ProcessSerialNumber>,
    _ wid: CGWindowID,
    _ mode: UInt32
) -> CGError

@_silgen_name("SLPSPostEventRecordTo")
@discardableResult
func SLPSPostEventRecordTo(
    _ psn: UnsafeMutablePointer<ProcessSerialNumber>,
    _ bytes: UnsafeMutablePointer<UInt8>
) -> CGError

enum SLPSMode: UInt32 {
    case allWindows = 0x100
    case userGenerated = 0x200
    case noWindows = 0x400
}

/// Bring `pid` to frontmost. If `cgWindowId` is non-nil and non-zero, also
/// makes that specific window the receiver of subsequent input events,
/// matching alt-tab-macos's `Window.focus()` for the windowed case.
///
/// Returns `true` if the CGS call accepted; this is a best-effort signal —
/// a `false` return means the private API is broken on this macOS version
/// and the caller should fall back to `NSRunningApplication.activate`.
@discardableResult
func bringAppToFront(pid: pid_t, cgWindowId: CGWindowID?) -> Bool {
    var psn = ProcessSerialNumber()
    let psnErr = GetProcessForPID(pid, &psn)
    guard psnErr == noErr else {
        NSLog("KAltSwitch: GetProcessForPID(pid=%d) failed: %d", pid, psnErr)
        return false
    }
    let wid = cgWindowId ?? 0
    let mode: UInt32 = wid == 0 ? SLPSMode.allWindows.rawValue : SLPSMode.userGenerated.rawValue
    let err = _SLPSSetFrontProcessWithOptions(&psn, wid, mode)
    if err != .success {
        NSLog("KAltSwitch: _SLPSSetFrontProcessWithOptions(pid=%d, wid=%u) failed: %d",
              pid, wid, err.rawValue)
        return false
    }
    if wid != 0 {
        postKeyWindowEvent(psn: &psn, cgWindowId: wid)
    }
    return true
}

/// Two byte-record events (`SLPSPostEventRecordTo`) that tell the WindowServer
/// "this window is now key in its process". Without this the app is frontmost
/// but key-window status sometimes lingers on the previously-focused window
/// of the same process. Bytes layout copied verbatim from
/// https://github.com/Hammerspoon/hammerspoon/issues/370#issuecomment-545545468 .
private func postKeyWindowEvent(psn: UnsafeMutablePointer<ProcessSerialNumber>, cgWindowId: CGWindowID) {
    var bytes = [UInt8](repeating: 0, count: 0xf8)
    bytes[0x04] = 0xf8
    bytes[0x3a] = 0x10
    var wid = cgWindowId
    withUnsafeBytes(of: &wid) { src in
        for i in 0..<MemoryLayout<UInt32>.size {
            bytes[0x3c + i] = src[i]
        }
    }
    for i in 0..<0x10 { bytes[0x20 + i] = 0xff }
    bytes[0x08] = 0x01
    SLPSPostEventRecordTo(psn, &bytes)
    bytes[0x08] = 0x02
    SLPSPostEventRecordTo(psn, &bytes)
}
