import Foundation
import AppKit
import Carbon.HIToolbox
import CoreGraphics
import ComposeAppMac

/// Bridges global keyboard input to the Kotlin [SwitcherController]:
///   - Carbon `RegisterEventHotKey` for `cmd+tab` / `cmd+shift+tab` / `cmd+\`` /
///     `cmd+shift+\``. These continue to fire on every press while `cmd` stays
///     held, which we use to advance / step-back app/window selection.
///   - `CGEventTap` on `kCGEventFlagsChanged` to detect `cmd` being released
///     (the commit signal). Runs on a dedicated background thread so the main
///     run loop stalling under Compose render pressure doesn't trip the tap's
///     timeout watchdog.
///
/// The tap installation requires Accessibility permission; we make `start()`
/// idempotent so AppRegistry can re-call it after the user grants AX.
///
/// The Carbon hot keys collide with macOS's own command-tab / command-shift-tab
/// and key-above-tab handlers. We disable those system hotkeys via the
/// private `CGSSetSymbolicHotKeyEnabled` API (see `SkyLight.swift`); the disable
/// happens at AppDelegate startup so it covers the brief window before this
/// controller initialises.
final class HotkeyController {
    private let controller: SwitcherController
    private var hotKeyRefs: [EventHotKeyRef] = []
    private var eventHandler: EventHandlerRef?
    private var eventTap: CFMachPort?
    private var runLoopSource: CFRunLoopSource?
    private var tapThread: TapThread?
    private var lastCmdHeld = false

    private static let signature: OSType = 0x4B414C54  // 'KALT'
    private static let idCmdTab: UInt32 = 1
    private static let idCmdShiftTab: UInt32 = 2
    private static let idCmdGrave: UInt32 = 3
    private static let idCmdShiftGrave: UInt32 = 4

    init(controller: SwitcherController) {
        self.controller = controller
    }

    /// Idempotent — safe to re-call after AX trust flips so the previously-failed
    /// CGEventTap can be created. Each piece guards on its own state.
    func start() {
        if eventHandler == nil { installEventHandler() }
        if hotKeyRefs.isEmpty { registerHotkeys() }
        if eventTap == nil { installFlagsChangedTap() }
    }

    func stop() {
        for ref in hotKeyRefs { UnregisterEventHotKey(ref) }
        hotKeyRefs.removeAll()
        if let h = eventHandler {
            RemoveEventHandler(h)
            eventHandler = nil
        }
        if let tap = eventTap {
            CGEvent.tapEnable(tap: tap, enable: false)
            if let src = runLoopSource, let loop = tapThread?.runLoop {
                CFRunLoopRemoveSource(loop, src, .commonModes)
            }
            eventTap = nil
            runLoopSource = nil
        }
        tapThread?.stop()
        tapThread = nil
    }

    // MARK: - Carbon hot keys

    private func installEventHandler() {
        var spec = EventTypeSpec(
            eventClass: OSType(kEventClassKeyboard),
            eventKind: UInt32(kEventHotKeyPressed)
        )
        let selfPtr = Unmanaged.passUnretained(self).toOpaque()
        // GetEventDispatcherTarget is the canonical target for global hot keys —
        // GetApplicationEventTarget worked but per alt-tab-macos's notes it
        // requires AX permission to be effective in some configurations.
        let status = InstallEventHandler(
            GetEventDispatcherTarget(),
            hotkeyHandlerCallback,
            1,
            &spec,
            selfPtr,
            &eventHandler
        )
        if status != noErr {
            NSLog("KAltSwitch: InstallEventHandler failed: %d", status)
        }
    }

    private func registerHotkeys() {
        register(keyCode: UInt32(kVK_Tab), mods: UInt32(cmdKey), id: HotkeyController.idCmdTab)
        register(keyCode: UInt32(kVK_Tab), mods: UInt32(cmdKey | shiftKey), id: HotkeyController.idCmdShiftTab)
        register(keyCode: UInt32(kVK_ANSI_Grave), mods: UInt32(cmdKey), id: HotkeyController.idCmdGrave)
        register(keyCode: UInt32(kVK_ANSI_Grave), mods: UInt32(cmdKey | shiftKey), id: HotkeyController.idCmdShiftGrave)
    }

    private func register(keyCode: UInt32, mods: UInt32, id: UInt32) {
        let hotKeyID = EventHotKeyID(signature: HotkeyController.signature, id: id)
        var ref: EventHotKeyRef?
        let status = RegisterEventHotKey(
            keyCode,
            mods,
            hotKeyID,
            GetEventDispatcherTarget(),
            0,
            &ref
        )
        if status == noErr, let ref = ref {
            hotKeyRefs.append(ref)
        } else {
            NSLog("KAltSwitch: RegisterEventHotKey(keyCode=%u, mods=0x%x) failed: %d",
                  keyCode, mods, status)
        }
    }

    fileprivate func handleHotkey(id: UInt32) {
        let entry: SwitcherEntry
        let reverse: Bool
        switch id {
        case HotkeyController.idCmdTab:        entry = .app;    reverse = false
        case HotkeyController.idCmdShiftTab:   entry = .app;    reverse = true
        case HotkeyController.idCmdGrave:      entry = .window; reverse = false
        case HotkeyController.idCmdShiftGrave: entry = .window; reverse = true
        default: return
        }
        DispatchQueue.main.async { [weak self] in
            self?.controller.onShortcut(entry: entry, reverse: reverse)
        }
    }

    // MARK: - Modifier release detection

    private func installFlagsChangedTap() {
        let mask = (1 << CGEventType.flagsChanged.rawValue)
        let selfPtr = Unmanaged.passUnretained(self).toOpaque()
        guard let tap = CGEvent.tapCreate(
            tap: .cgSessionEventTap,
            place: .headInsertEventTap,
            options: .listenOnly,
            eventsOfInterest: CGEventMask(mask),
            callback: flagsChangedCallback,
            userInfo: selfPtr
        ) else {
            NSLog("KAltSwitch: failed to create flagsChanged event tap (AX permission?)")
            return
        }
        let src = CFMachPortCreateRunLoopSource(nil, tap, 0)

        // Off-main: if the main run loop stalls (Compose layout, AX call) the
        // tap gets disabled by the kernel watchdog and stops delivering events
        // entirely. Putting it on a userInteractive thread is what alt-tab-macos
        // does and it makes the modifier-release detection rock-solid.
        let thread = TapThread()
        thread.startAndWaitForRunLoop()
        CFRunLoopAddSource(thread.runLoop, src, .commonModes)
        CGEvent.tapEnable(tap: tap, enable: true)

        eventTap = tap
        runLoopSource = src
        tapThread = thread
    }

    fileprivate func handleTapEvent(type: CGEventType, event: CGEvent) {
        if type == .tapDisabledByUserInput || type == .tapDisabledByTimeout {
            // Re-enable; this is a known watchdog quirk and not a fatal error.
            if let tap = eventTap {
                CGEvent.tapEnable(tap: tap, enable: true)
            }
            return
        }
        if type == .flagsChanged {
            let cmdHeld = event.flags.contains(.maskCommand)
            guard cmdHeld != lastCmdHeld else { return }
            lastCmdHeld = cmdHeld
            if !cmdHeld {
                DispatchQueue.main.async { [weak self] in
                    self?.controller.onModifierReleased()
                }
            }
        }
    }
}

/// Dedicated NSThread that owns a CFRunLoop, used to host the flagsChanged tap.
/// Pattern copied from alt-tab-macos: a no-op source keeps the loop alive
/// until we add the real tap source.
private final class TapThread {
    private(set) var runLoop: CFRunLoop!
    private let started = DispatchSemaphore(value: 0)
    private var thread: Thread?

    func startAndWaitForRunLoop() {
        let t = Thread { [weak self] in self?.body() }
        t.name = "kaltswitch-flagsChanged"
        t.qualityOfService = .userInteractive
        t.start()
        thread = t
        started.wait()
    }

    func stop() {
        if let loop = runLoop { CFRunLoopStop(loop) }
    }

    private func body() {
        runLoop = CFRunLoopGetCurrent()
        // Dummy source so CFRunLoopRun doesn't return immediately if no real
        // sources are attached yet.
        var ctx = CFRunLoopSourceContext()
        ctx.perform = { _ in }
        let dummy = CFRunLoopSourceCreate(nil, 0, &ctx)
        CFRunLoopAddSource(runLoop, dummy, .commonModes)
        started.signal()
        CFRunLoopRun()
    }
}

/// C handler for Carbon hot-key events. Marshals the parameters out of the OSStatus
/// world and into a Swift method on the controller.
private let hotkeyHandlerCallback: EventHandlerUPP = { _, eventRef, refcon -> OSStatus in
    guard let eventRef = eventRef, let refcon = refcon else { return OSStatus(eventNotHandledErr) }
    var hotKeyID = EventHotKeyID()
    let status = GetEventParameter(
        eventRef,
        EventParamName(kEventParamDirectObject),
        EventParamType(typeEventHotKeyID),
        nil,
        MemoryLayout<EventHotKeyID>.size,
        nil,
        &hotKeyID
    )
    if status != noErr { return status }
    guard hotKeyID.signature == 0x4B414C54 else { return OSStatus(eventNotHandledErr) }
    let me = Unmanaged<HotkeyController>.fromOpaque(refcon).takeUnretainedValue()
    me.handleHotkey(id: hotKeyID.id)
    return noErr
}

/// C callback for the `flagsChanged` event tap.
private let flagsChangedCallback: CGEventTapCallBack = { _, type, event, refcon in
    guard let refcon = refcon else { return Unmanaged.passUnretained(event) }
    let me = Unmanaged<HotkeyController>.fromOpaque(refcon).takeUnretainedValue()
    me.handleTapEvent(type: type, event: event)
    return Unmanaged.passUnretained(event)
}
