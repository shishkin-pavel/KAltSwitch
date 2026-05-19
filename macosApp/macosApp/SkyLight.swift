import Foundation
import AppKit
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

// MARK: - Spaces (Mission Control virtual desktops)
//
// Public API gives us nothing: `NSWindow.collectionBehavior` only describes
// the host app's own windows, and there's no published call to ask "which
// spaces does CGWindowID X belong to?" alt-tab-macos relies on the same
// private CGS calls we forward-declare here.
//
// Note re. macOS versions: `CGSCopySpacesForWindows` and
// `CGSCopyManagedDisplaySpaces` have been stable since 10.10. Apple has
// made noises about hardening private APIs in future macOS releases; if
// these stop returning data, we drop back to "all spaces" silently and
// surface a one-shot warning in logs.

typealias CGSConnectionID = UInt32
typealias CGSSpaceID = UInt64

@_silgen_name("CGSMainConnectionID")
func CGSMainConnectionID() -> CGSConnectionID

/// `mask` chooses which space relationships to return. `7 = all` matches
/// alt-tab's usage; `5 = current-only` and `6 = other-only` exist too.
@_silgen_name("CGSCopySpacesForWindows")
func CGSCopySpacesForWindows(
    _ cid: CGSConnectionID,
    _ mask: Int,
    _ wids: CFArray
) -> CFArray

/// Returns an array of dicts, one per display, each describing its space
/// list and current space — used to find every "currently visible" space
/// across all attached displays.
@_silgen_name("CGSCopyManagedDisplaySpaces")
func CGSCopyManagedDisplaySpaces(_ cid: CGSConnectionID) -> CFArray

private let cgsConnection: CGSConnectionID = CGSMainConnectionID()

/// Spaces this window currently belongs to. Empty array on failure or if
/// the window has been destroyed between the AX observation and now.
func spaceIdsFor(cgWindowId: CGWindowID) -> [Int64] {
    let result = CGSCopySpacesForWindows(cgsConnection, 7, [cgWindowId] as CFArray)
    guard let arr = result as? [NSNumber] else { return [] }
    return arr.map { Int64($0.uint64Value) }
}

/// Union of "Current Space.id64" across every connected display. A window
/// counts as on-current-space iff at least one of its [spaceIdsFor] entries
/// is in this set. Falls back to empty on failure (callers treat empty as
/// "feature unavailable" and skip the filter).
func currentVisibleSpaceIds() -> [Int64] {
    let raw = CGSCopyManagedDisplaySpaces(cgsConnection)
    guard let displays = raw as? [NSDictionary] else { return [] }
    var out: [Int64] = []
    for display in displays {
        if let current = display["Current Space"] as? NSDictionary,
           let id = (current["id64"] as? NSNumber)?.uint64Value {
            out.append(Int64(id))
        }
    }
    return out
}

/// Private SkyLight: switch the given display to the given Space, with
/// the standard Mission Control swoosh animation. The `display`
/// argument is the per-display UUID string returned under the
/// `"Display Identifier"` key by `CGSCopyManagedDisplaySpaces` — NOT a
/// `CGDirectDisplayID`. Stable since 10.10; same private-API caveat
/// as the other CGS symbols in this file.
@_silgen_name("CGSManagedDisplaySetCurrentSpace")
func CGSManagedDisplaySetCurrentSpace(_ cid: CGSConnectionID, _ display: CFString, _ space: CGSSpaceID)

/// Walk `CGSCopyManagedDisplaySpaces` and find the "Display Identifier"
/// of the display whose Spaces list claims [spaceId]. Returns nil if
/// no display claims it (deleted Space, transient state, or
/// CGS-data-unavailable). Used by [switchToSpaceFor].
private func displayIdentifierForSpace(_ spaceId: CGSSpaceID) -> String? {
    let raw = CGSCopyManagedDisplaySpaces(cgsConnection)
    guard let displays = raw as? [NSDictionary] else { return nil }
    for display in displays {
        guard let spaces = display["Spaces"] as? [NSDictionary],
              let displayId = display["Display Identifier"] as? String else { continue }
        for space in spaces {
            if let id = (space["id64"] as? NSNumber)?.uint64Value,
               CGSSpaceID(id) == spaceId {
                return displayId
            }
        }
    }
    return nil
}

/// If [cgWindowId] lives on a Space that isn't currently visible on
/// its owning display, switch that display to the window's Space.
///
/// Why this exists: `_SLPSSetFrontProcessWithOptions` (the workhorse
/// of [bringAppToFront]) makes the target app frontmost and targets a
/// specific window for subsequent input, but does **not** trigger a
/// Mission Control space change. So if the user picks an off-space
/// window in the switcher, focus moves to that window invisibly —
/// `cmd+T` opens a new tab in it, but the screen stays on the user's
/// current Space with no visual confirmation. This call closes the
/// gap, with the same private symbol that alt-tab-macos / AeroSpace
/// rely on.
///
/// Order in [AppRegistry.commit] is: switch space first, then
/// activate. Switching after activation invites apps (Safari, Finder)
/// to pull their window to the current Space instead of staying put,
/// because the activation-frontmost handshake can fire `bring-to-
/// current-space` semantics in `userGenerated` SLPS mode.
///
/// Returns `true` if a switch call was issued, `false` if no switch
/// was needed or possible (callers don't need to react — the
/// activation path is the same either way; the return value is just
/// for the log line).
@discardableResult
func switchToSpaceFor(cgWindowId: CGWindowID) -> Bool {
    let spaces = spaceIdsFor(cgWindowId: cgWindowId)
    if spaces.isEmpty {
        log("[space-switch] cgwid=\(cgWindowId) skip: no space data")
        return false
    }
    let visible = Set(currentVisibleSpaceIds())
    if spaces.contains(where: { visible.contains($0) }) {
        log("[space-switch] cgwid=\(cgWindowId) skip: already visible " +
            "(window spaces=\(spaces), visible=\(Array(visible).sorted()))")
        return false
    }
    // Pick the first space the window claims. Multi-space windows
    // (.canJoinAllSpaces / sticky panels) would short-circuit on the
    // visible-intersection check above — if we got here, this window
    // is pinned to exactly one Space.
    let target = CGSSpaceID(spaces[0])
    guard let displayId = displayIdentifierForSpace(target) else {
        log("[space-switch] cgwid=\(cgWindowId) skip: no display claims space=\(target). " +
            "Topology: \(describeSpaceTopology())")
        return false
    }
    log("[space-switch] cgwid=\(cgWindowId) → space=\(target) display=\(displayId). " +
        "Pre-state: window spaces=\(spaces), visible=\(Array(visible).sorted()). " +
        "Topology: \(describeSpaceTopology())")
    CGSManagedDisplaySetCurrentSpace(cgsConnection, displayId as CFString, target)
    // Read the immediate post-call state. WindowServer updates its
    // logical current-space pointer synchronously; the animation is
    // async, but the pointer being already-flipped is what the next
    // SLPS activation will see.
    let postVisible = Set(currentVisibleSpaceIds())
    log("[space-switch] post-call visible=\(Array(postVisible).sorted())")
    return true
}

/// One entry per (display, space) pair — used to populate the
/// menubar's Spaces submenu. The full UUID is kept for the
/// SetCurrentSpace call; the truncated form drives the friendly
/// label.
struct SpaceMenubarEntry {
    let displayId: String       // full "Display Identifier" UUID
    let displayShort: String    // first 8 chars + ellipsis, for labels
    let displayIndex: Int       // 0, 1, … in CGSCopyManagedDisplaySpaces order
    let spaceId: CGSSpaceID
    let isCurrent: Bool
}

/// Snapshot of every (display, space) pair currently known to
/// SkyLight, with the per-display Current-Space flagged. Used by the
/// menubar Spaces submenu to render up-to-date items on each open.
func collectMenubarSpaceTopology() -> [SpaceMenubarEntry] {
    let raw = CGSCopyManagedDisplaySpaces(cgsConnection)
    guard let displays = raw as? [NSDictionary] else { return [] }
    var entries: [SpaceMenubarEntry] = []
    for (i, display) in displays.enumerated() {
        let id = (display["Display Identifier"] as? String) ?? "?"
        let short = String(id.prefix(8)) + "…"
        var currentId: UInt64 = 0
        if let cur = display["Current Space"] as? NSDictionary,
           let n = cur["id64"] as? NSNumber {
            currentId = n.uint64Value
        }
        guard let spaces = display["Spaces"] as? [NSDictionary] else { continue }
        for s in spaces {
            guard let n = s["id64"] as? NSNumber else { continue }
            let id64 = n.uint64Value
            entries.append(SpaceMenubarEntry(
                displayId: id,
                displayShort: short,
                displayIndex: i,
                spaceId: id64,
                isCurrent: id64 == currentId
            ))
        }
    }
    return entries
}

// MARK: - Dock-swipe gesture synthesis (private CGEvent fields)
//
// The neutralised `CGSManagedDisplaySetCurrentSpace` setter and the
// silently-dropped ctrl+arrow keyboard simulation are both dead ends
// on recent macOS. But there's a third path: synthesise the exact
// private CGEvent the trackpad driver posts for a 3-finger Space
// swipe — `kCGSEventDockControl` (event type 30) carrying
// `kIOHIDEventTypeDockSwipe` (HID type 23) — and `CGEventPost` it
// to the session tap. The Dock processes it identically to a real
// gesture and runs the standard Space-switch swoosh.
//
// Two open-source references prove this is stable since at least
// macOS 10.11 and works under SIP without entitlements:
//
//   * `iss` (~/projects/iss, C, ~230 lines): intercepts real
//      gestures to skip animation. The synthetic-post core is
//      `make_dock_event` + `post_pair` + `post_switch`.
//   * `InstantSpaceSwitcher` (~/projects/InstantSpaceSwitcher,
//      C lib + Swift app): exposes `iss_perform_switch_gesture`
//      that posts a 3-phase Began/Changed/Ended sequence.
//
// Both use the same private CGEvent field indices. Discovered via
// reverse-engineering the WindowServer-Dock event stream; stable
// since 3-finger swipe was introduced.

/// `CGEventField` is `CF_ENUM`-bridged so the runtime accepts
/// arbitrary integer indices — but the Swift `init(rawValue:)`
/// is failable. These fields aren't part of the named enum, so
/// the bang is unavoidable; runtime never returns nil for a
/// `CF_ENUM` initialiser.
private let kCGSEventTypeField           = CGEventField(rawValue: 55)!
private let kCGEventGestureHIDType       = CGEventField(rawValue: 110)!
private let kCGEventGestureSwipeMotion   = CGEventField(rawValue: 123)!
private let kCGEventGestureSwipeProgress = CGEventField(rawValue: 124)!
private let kCGEventGestureSwipeVelocityX = CGEventField(rawValue: 129)!
private let kCGEventGestureSwipeVelocityY = CGEventField(rawValue: 130)!
private let kCGEventGesturePhase         = CGEventField(rawValue: 132)!

private let kCGSEventDockControl: Int64       = 30
private let kIOHIDEventTypeDockSwipe: Int64   = 23
private let kCGGestureMotionHorizontal: Int64 = 1
private let kCGSGesturePhaseBegan: Int64      = 1
private let kCGSGesturePhaseChanged: Int64    = 2
private let kCGSGesturePhaseEnded: Int64      = 4

/// Post one synthetic 3-finger Space swipe via the session event
/// tap. The Dock treats this as a real gesture and runs its standard
/// Space-switch animation (or skips it, depending on velocity — we
/// pass a high one so the swoosh is instant).
///
/// `rightward = true` → next Space on the active display.
/// `rightward = false` → previous Space.
///
/// Returns `true` if all three phase events were posted. Failure
/// modes are essentially limited to `CGEvent(source: nil)` returning
/// nil under memory pressure.
@discardableResult
func postDockSwipe(rightward: Bool, velocity: Double = 1000.0) -> Bool {
    // `FLT_TRUE_MIN` is the empirically-required progress magnitude
    // — non-zero but as close to zero as a float can represent.
    let progress = rightward
        ? Double(Float.leastNonzeroMagnitude)
        : -Double(Float.leastNonzeroMagnitude)
    let vel = rightward ? velocity : -velocity

    for phase in [kCGSGesturePhaseBegan, kCGSGesturePhaseChanged, kCGSGesturePhaseEnded] {
        guard let ev = CGEvent(source: nil) else { return false }
        ev.setIntegerValueField(kCGSEventTypeField,           value: kCGSEventDockControl)
        ev.setIntegerValueField(kCGEventGestureHIDType,       value: kIOHIDEventTypeDockSwipe)
        ev.setIntegerValueField(kCGEventGesturePhase,         value: phase)
        ev.setDoubleValueField(kCGEventGestureSwipeProgress,  value: progress)
        ev.setIntegerValueField(kCGEventGestureSwipeMotion,   value: kCGGestureMotionHorizontal)
        ev.setDoubleValueField(kCGEventGestureSwipeVelocityX, value: vel)
        ev.setDoubleValueField(kCGEventGestureSwipeVelocityY, value: vel)
        ev.post(tap: .cgSessionEventTap)
    }
    return true
}

/// Look up the `CGDirectDisplayID` whose UUID matches `uuid`. Used
/// to derive `CGDisplayBounds` for the cursor-warp logic in
/// [SwipeOrchestrator] (the Dock processes synthetic dock-swipes
/// against whichever display has the cursor; multi-monitor setups
/// need the cursor parked on the target display first).
private func cgDisplayIDFor(uuid: String) -> CGDirectDisplayID? {
    var displays = [CGDirectDisplayID](repeating: 0, count: 16)
    var count: UInt32 = 0
    let err = CGGetActiveDisplayList(16, &displays, &count)
    guard err == .success else { return nil }
    for i in 0..<Int(count) {
        let id = displays[i]
        guard let cf = CGDisplayCreateUUIDFromDisplayID(id)?.takeRetainedValue() else { continue }
        let s = CFUUIDCreateString(nil, cf) as String
        if s == uuid { return id }
    }
    return nil
}

/// Orchestrates a multi-step dock-swipe sequence. Posts one swipe,
/// waits for either `NSWorkspace.activeSpaceDidChangeNotification`
/// (typically arrives ~40–80 ms after the post) or a per-swipe
/// 250 ms safety timeout, then posts the next — repeating until
/// `count` swipes have been issued. After the final swipe + a
/// short settle window (`finalSettleMs`), invokes `completion` on
/// the main queue.
///
/// On multi-monitor setups, the Dock processes synthetic dock
/// swipes against whichever display has the cursor. The cursor is
/// warped *briefly* to the target display only for the duration of
/// each `postDockSwipe` call, then immediately warped back so the
/// user never sees the cursor leave its original location. Any
/// motion the user produces during that ~microsecond window is
/// preserved by computing `delta = postWarp - centre` and applying
/// it to the original saved position on restore. No-op when the
/// cursor is already on the target display.
///
/// Held alive for the duration of the sequence via the
/// module-level `activeSwipeOrchestrator` reference — the
/// orchestrator deinits when that reference is cleared from inside
/// `done`. A second `swipeBetweenSpaces` call before completion
/// supersedes the previous orchestrator; its observer is held weakly,
/// so it no-ops after deinit.
private final class SwipeOrchestrator {
    private var remaining: Int
    private let rightward: Bool
    private let targetDisplayBounds: CGRect?
    private let completion: () -> Void
    private var observer: NSObjectProtocol?
    private var timeoutItem: DispatchWorkItem?
    private static let perSwipeTimeoutMs = 250
    private static let finalSettleMs = 80

    init(count: Int, rightward: Bool, targetDisplayBounds: CGRect?, completion: @escaping () -> Void) {
        self.remaining = count
        self.rightward = rightward
        self.targetDisplayBounds = targetDisplayBounds
        self.completion = completion
    }

    func start() {
        observer = NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.activeSpaceDidChangeNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in self?.onActiveSpaceChanged() }
        postNext()
    }

    private func postNext() {
        guard remaining > 0 else { done(); return }
        remaining -= 1
        postSwipePreservingCursor()
        let item = DispatchWorkItem { [weak self] in self?.onTimeout() }
        timeoutItem = item
        DispatchQueue.main.asyncAfter(
            deadline: .now() + .milliseconds(Self.perSwipeTimeoutMs),
            execute: item)
    }

    /// Briefly warp the cursor onto the target display so the Dock
    /// processes the synthetic dock-swipe against that display,
    /// then immediately warp back with delta-compensation so any
    /// physical user motion during the warp window is preserved.
    /// Skips the warp entirely when the cursor is already on the
    /// target display or when `targetDisplayBounds` is nil (no
    /// multi-monitor lookup data).
    private func postSwipePreservingCursor() {
        guard let bounds = targetDisplayBounds else {
            postDockSwipe(rightward: rightward)
            return
        }
        let saved = CGEvent(source: nil)?.location ?? .zero
        if bounds.contains(saved) {
            postDockSwipe(rightward: rightward)
            return
        }
        let centre = CGPoint(x: bounds.midX, y: bounds.midY)
        CGWarpMouseCursorPosition(centre)
        CGAssociateMouseAndMouseCursorPosition(1)
        postDockSwipe(rightward: rightward)
        let afterPost = CGEvent(source: nil)?.location ?? centre
        let delta = CGPoint(x: afterPost.x - centre.x, y: afterPost.y - centre.y)
        let restored = CGPoint(x: saved.x + delta.x, y: saved.y + delta.y)
        CGWarpMouseCursorPosition(restored)
        CGAssociateMouseAndMouseCursorPosition(1)
        log("[space-swipe] cursor briefly warped \(saved) → \(centre), restored to \(restored) (delta=\(delta))")
    }

    private func onActiveSpaceChanged() {
        timeoutItem?.cancel(); timeoutItem = nil
        if remaining > 0 { postNext() } else { done() }
    }

    private func onTimeout() {
        log("[space-swipe] timeout (\(Self.perSwipeTimeoutMs)ms) — no activeSpaceDidChange after swipe, advancing")
        if remaining > 0 { postNext() } else { done() }
    }

    private func done() {
        if let o = observer {
            NSWorkspace.shared.notificationCenter.removeObserver(o)
            observer = nil
        }
        timeoutItem?.cancel(); timeoutItem = nil
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(Self.finalSettleMs)) { [weak self] in
            self?.completion()
            if activeSwipeOrchestrator === self { activeSwipeOrchestrator = nil }
        }
    }
}

/// Module-level strong reference that keeps the orchestrator alive
/// for the duration of its sequence. Cleared in `SwipeOrchestrator.done`.
private var activeSwipeOrchestrator: SwipeOrchestrator?

/// Find current and target Space indices in [displayId]'s Spaces
/// list, then walk Mission Control to [targetSpaceId] by posting
/// `|delta|` dock swipes through a [SwipeOrchestrator]. Returns
/// `true` if a swipe sequence was scheduled (caller should defer
/// any follow-up activation to `completion`); `false` if already on
/// the target Space or the topology lookup failed (caller should
/// proceed synchronously).
@discardableResult
func swipeBetweenSpaces(displayId: String, targetSpaceId: CGSSpaceID, completion: @escaping () -> Void) -> Bool {
    let raw = CGSCopyManagedDisplaySpaces(cgsConnection)
    guard let displays = raw as? [NSDictionary] else {
        log("[space-swipe] CGS topology unavailable")
        return false
    }
    var foundDisplay = false
    var currentIndex: Int? = nil
    var targetIndex: Int? = nil
    for display in displays {
        guard let id = display["Display Identifier"] as? String, id == displayId,
              let spaces = display["Spaces"] as? [NSDictionary] else { continue }
        foundDisplay = true
        var currentId: UInt64 = 0
        if let cur = display["Current Space"] as? NSDictionary,
           let n = cur["id64"] as? NSNumber {
            currentId = n.uint64Value
        }
        for (i, s) in spaces.enumerated() {
            guard let n = s["id64"] as? NSNumber else { continue }
            let id64 = n.uint64Value
            if id64 == currentId { currentIndex = i }
            if id64 == targetSpaceId { targetIndex = i }
        }
        break
    }
    if !foundDisplay {
        log("[space-swipe] display=\(String(displayId.prefix(8)))… not found in topology")
        return false
    }
    guard let curI = currentIndex, let tgtI = targetIndex else {
        log("[space-swipe] display=\(String(displayId.prefix(8)))… curr=\(currentIndex ?? -1) tgt=\(targetIndex ?? -1) — index missing")
        return false
    }
    let delta = tgtI - curI
    if delta == 0 { return false }
    let count = abs(delta)
    let rightward = delta > 0
    let bounds = cgDisplayIDFor(uuid: displayId).map { CGDisplayBounds($0) }
    log("[space-swipe] display=\(String(displayId.prefix(8)))… curr=\(curI) tgt=\(tgtI) delta=\(delta) posting \(count) swipe\(count > 1 ? "s" : "") \(rightward ? "→" : "←")")
    let orch = SwipeOrchestrator(
        count: count,
        rightward: rightward,
        targetDisplayBounds: bounds,
        completion: completion)
    activeSwipeOrchestrator = orch
    orch.start()
    return true
}

/// If [cgWindowId] lives on a Space not currently visible on its
/// owning display, walk the display to the window's Space via
/// [swipeBetweenSpaces]. Returns `true` if a sequence was scheduled
/// (caller should defer activation to `completion`); `false` if no
/// swipe was needed or the lookup failed.
@discardableResult
func swipeToSpaceFor(cgWindowId: CGWindowID, completion: @escaping () -> Void) -> Bool {
    let spaces = spaceIdsFor(cgWindowId: cgWindowId)
    if spaces.isEmpty { return false }
    let visible = Set(currentVisibleSpaceIds())
    if spaces.contains(where: { visible.contains($0) }) { return false }
    let target = CGSSpaceID(spaces[0])
    guard let displayId = displayIdentifierForSpace(target) else {
        log("[space-swipe] cgwid=\(cgWindowId) skip: no display claims space=\(target)")
        return false
    }
    return swipeBetweenSpaces(displayId: displayId, targetSpaceId: target, completion: completion)
}

/// Diagnostic entry point invoked from the menubar Spaces submenu.
/// Logs before/after `currentVisibleSpaceIds` so we can tell from
/// the log tail alone whether the dock-swipe path is reachable on
/// this macOS — distinct from the CGS-setter path that's also in
/// the same submenu for A/B comparison.
func diagnosticDockSwipe(rightward: Bool) {
    let before = currentVisibleSpaceIds()
    log("[diag-space] dock-swipe \(rightward ? "→" : "←") pre-call visible=\(before)")
    let ok = postDockSwipe(rightward: rightward)
    let immediate = currentVisibleSpaceIds()
    log("[diag-space] dock-swipe posted=\(ok) post-call visible=\(immediate)")
    DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(150)) {
        log("[diag-space] dock-swipe +150ms visible=\(currentVisibleSpaceIds())")
    }
    DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(500)) {
        log("[diag-space] dock-swipe +500ms visible=\(currentVisibleSpaceIds())")
    }
}

/// Diagnostic-only programmatic space switch invoked from the menubar
/// Spaces submenu. Calls `CGSManagedDisplaySetCurrentSpace` in
/// isolation — no SLPS, no NSApp.activate, no AXRaise — so the
/// bench-test answers exactly one question: does this private API
/// actually trigger a visible space change on this macOS / monitor
/// setup at all? Heavy logging captures the before/after pointer
/// state and a delayed re-sample so eventually-consistent flips
/// surface in the log tail.
func diagnosticSwitchToSpace(displayId: String, spaceId: CGSSpaceID) {
    let before = currentVisibleSpaceIds()
    log("[diag-space] click → display=\(String(displayId.prefix(8)))… space=\(spaceId)")
    log("[diag-space] pre-call visible=\(before) topology=\(describeSpaceTopology())")
    CGSManagedDisplaySetCurrentSpace(cgsConnection, displayId as CFString, spaceId)
    let immediate = currentVisibleSpaceIds()
    log("[diag-space] post-call visible=\(immediate)")
    DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(150)) {
        log("[diag-space] +150ms visible=\(currentVisibleSpaceIds())")
    }
    DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(500)) {
        log("[diag-space] +500ms visible=\(currentVisibleSpaceIds())")
    }
}

/// Human-readable dump of the per-display space topology, for
/// debug-log spelunking. Returns a single-line string like:
/// `displays=[CA2D…(cur=42, spaces=[42,99,339]), 8B7F…(cur=17, spaces=[17])]`.
private func describeSpaceTopology() -> String {
    let raw = CGSCopyManagedDisplaySpaces(cgsConnection)
    guard let displays = raw as? [NSDictionary] else { return "displays=<unavailable>" }
    var parts: [String] = []
    for display in displays {
        let id = (display["Display Identifier"] as? String) ?? "?"
        let idShort = String(id.prefix(8))
        var curId: UInt64 = 0
        if let cur = display["Current Space"] as? NSDictionary,
           let n = (cur["id64"] as? NSNumber) {
            curId = n.uint64Value
        }
        var spaceIds: [UInt64] = []
        if let spaces = display["Spaces"] as? [NSDictionary] {
            for s in spaces {
                if let n = s["id64"] as? NSNumber {
                    spaceIds.append(n.uint64Value)
                }
            }
        }
        parts.append("\(idShort)…(cur=\(curId), spaces=\(spaceIds))")
    }
    return "displays=[\(parts.joined(separator: ", "))]"
}

/// Of the given [cgWindowIds], return the subset the WindowServer still
/// reports as alive. Synchronous and cheap — `CGWindowListCreateDescription
/// FromArray` is a single Mach IPC into WindowServer that fetches just the
/// requested entries (no full window-list scan), sub-millisecond on a
/// typical desktop. Public CoreGraphics API, no private symbol required.
///
/// Used by `AxAppWatcher` to disambiguate a `kAXUIElementDestroyed` event:
/// the AX-server-side proxy being gone doesn't necessarily mean the
/// underlying window is gone (apps like Slack / Electron / fullscreen
/// transitions recycle AX trees). One probe here tells us authoritatively
/// whether the WindowServer also lost the window — if not, we keep the
/// row in the store with `sources={CG}` until AX re-attaches.
func aliveCgWindowIds(_ cgWindowIds: [CGWindowID]) -> Set<CGWindowID> {
    if cgWindowIds.isEmpty { return [] }
    let candidates = cgWindowIds.map { NSNumber(value: $0) } as CFArray
    let descs = (CGWindowListCreateDescriptionFromArray(candidates) as? [[String: Any]]) ?? []
    var out: Set<CGWindowID> = []
    out.reserveCapacity(descs.count)
    for desc in descs {
        if let n = desc[kCGWindowNumber as String] as? NSNumber {
            out.insert(n.uint32Value)
        }
    }
    return out
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
