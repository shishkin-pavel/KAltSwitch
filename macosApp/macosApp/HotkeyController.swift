import Foundation
import AppKit
import Carbon.HIToolbox
import CoreGraphics
import ComposeAppMac

/// Bridges global keyboard input to the Kotlin [SwitcherController]:
///   - Carbon `RegisterEventHotKey` for `cmd+tab` / `cmd+\``. These continue to fire
///     on every press while `cmd` stays held, which we use to advance app/window selection.
///   - `CGEventTap` on `kCGEventFlagsChanged` to detect `cmd` being released
///     (the commit signal).
///
/// Carbon hotkey registrations are per-process and torn down by the kernel on
/// process exit (including crashes), so the system defaults are restored
/// automatically; we still call `UnregisterEventHotKey` from `stop()` for cleanliness.
final class HotkeyController {
    private let controller: SwitcherController
    private var hotKeyRefs: [EventHotKeyRef] = []
    private var eventHandler: EventHandlerRef?
    private var eventTap: CFMachPort?
    private var runLoopSource: CFRunLoopSource?
    private var lastCmdHeld = false

    private static let signature: OSType = 0x4B414C54  // 'KALT'
    private static let idCmdTab: UInt32 = 1
    private static let idCmdGrave: UInt32 = 2

    init(controller: SwitcherController) {
        self.controller = controller
    }

    func start() {
        installEventHandler()
        registerHotkeys()
        installFlagsChangedTap()
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
            if let src = runLoopSource {
                CFRunLoopRemoveSource(CFRunLoopGetCurrent(), src, .commonModes)
            }
            eventTap = nil
            runLoopSource = nil
        }
    }

    // MARK: - Carbon hot keys

    private func installEventHandler() {
        var spec = EventTypeSpec(
            eventClass: OSType(kEventClassKeyboard),
            eventKind: UInt32(kEventHotKeyPressed)
        )
        let selfPtr = Unmanaged.passUnretained(self).toOpaque()
        let status = InstallEventHandler(
            GetApplicationEventTarget(),
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
        register(keyCode: UInt32(kVK_Tab), id: HotkeyController.idCmdTab)
        register(keyCode: UInt32(kVK_ANSI_Grave), id: HotkeyController.idCmdGrave)
    }

    private func register(keyCode: UInt32, id: UInt32) {
        let hotKeyID = EventHotKeyID(signature: HotkeyController.signature, id: id)
        var ref: EventHotKeyRef?
        let status = RegisterEventHotKey(
            keyCode,
            UInt32(cmdKey),
            hotKeyID,
            GetApplicationEventTarget(),
            0,
            &ref
        )
        if status == noErr, let ref = ref {
            hotKeyRefs.append(ref)
        } else {
            NSLog("KAltSwitch: RegisterEventHotKey(keyCode=%u) failed: %d", keyCode, status)
        }
    }

    fileprivate func handleHotkey(id: UInt32) {
        let entry: SwitcherEntry
        switch id {
        case HotkeyController.idCmdTab: entry = .app
        case HotkeyController.idCmdGrave: entry = .window
        default: return
        }
        DispatchQueue.main.async { [weak self] in
            self?.controller.onShortcut(entry: entry)
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
        CFRunLoopAddSource(CFRunLoopGetCurrent(), src, .commonModes)
        CGEvent.tapEnable(tap: tap, enable: true)
        eventTap = tap
        runLoopSource = src
    }

    fileprivate func handleFlags(_ flags: CGEventFlags) {
        let cmdHeld = flags.contains(.maskCommand)
        guard cmdHeld != lastCmdHeld else { return }
        lastCmdHeld = cmdHeld
        if !cmdHeld {
            DispatchQueue.main.async { [weak self] in
                self?.controller.onModifierReleased()
            }
        }
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
private let flagsChangedCallback: CGEventTapCallBack = { _, _, event, refcon in
    guard let refcon = refcon else { return Unmanaged.passUnretained(event) }
    let me = Unmanaged<HotkeyController>.fromOpaque(refcon).takeUnretainedValue()
    me.handleFlags(event.flags)
    return Unmanaged.passUnretained(event)
}
