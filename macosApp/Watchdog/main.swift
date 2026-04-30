// KAltSwitchWatchdog — restores macOS's system cmd+tab on KAltSwitch death.
//
// The main app disables three private CGS "symbolic hot keys" at launch
// (commandTab, commandShiftTab, commandKeyAboveTab) so its own Carbon
// registrations win the race. That setting persists across the main app's
// process death — graceful (`applicationWillTerminate`), via signal
// (`installSignalHandlers`), or otherwise. For graceful + signal exits the
// main app re-enables the keys before quitting; **for SIGSEGV / SIGKILL /
// force-quit / kernel panic, no in-process handler can run**, and the user
// is left without system cmd+tab until they relaunch and quit KAltSwitch
// once.
//
// This watchdog plugs that hole. The main app spawns it once at launch
// (passing its own pid in argv); the watchdog uses `kqueue`'s
// `EVFILT_PROC` / `NOTE_EXIT` filter to wait — without polling — for the
// main process to die for **any** reason, then re-enables the symbolic
// hot keys via the same private API the main app uses (`SkyLight.swift`'s
// `_CGSSetSymbolicHotKeyEnabled`) and exits.
//
// Constraints kept the binary tiny on purpose:
//   - no AppKit / Foundation observers / dispatch sources — we want this
//     thing to not crash itself
//   - fixed list of three hot key IDs, mirroring the main app's
//     `SymbolicHotKey.allCases`
//   - kqueue's NOTE_EXIT works cross-process when both run as the same
//     user; KAltSwitch and watchdog do, so no privilege issues
//
// Caveat: if someone explicitly `kill -9`s the watchdog *before* killing
// the main app, the main app's symbolic-hotkey takeover survives without
// restoration on its eventual death. We accept that — the watchdog is
// already a best-effort second line of defence, and explicitly killing
// it is an unusual user action.

import Foundation
import CoreGraphics

@_silgen_name("CGSSetSymbolicHotKeyEnabled")
private func _CGSSetSymbolicHotKeyEnabled(_ hotKey: Int32, _ isEnabled: Bool) -> CGError

private let logTag = "KAltSwitchWatchdog"

private func logLine(_ message: String) {
    // NSLog so output ends up in the unified system log even if our
    // stdout/stderr aren't redirected. The main app *does* redirect its
    // own streams to ~/Library/Logs/KAltSwitch.log, but the watchdog
    // inherits those redirections only if launched in-process — which we
    // are, but Foundation Process resets stdio per child by default.
    // NSLog is the lowest-friction "show up somewhere readable" path.
    NSLog("\(logTag): \(message)")
}

guard CommandLine.arguments.count >= 2,
      let parentPid = pid_t(CommandLine.arguments[1]),
      parentPid > 0
else {
    logLine("missing or invalid parent pid argument; exiting")
    exit(1)
}

logLine("watching parent pid=\(parentPid)")

let kq = kqueue()
guard kq >= 0 else {
    logLine("kqueue() failed: errno=\(errno)")
    exit(1)
}

// EV_ONESHOT — fire once, then auto-remove. NOTE_EXIT — wake when the
// target process exits. ident is the parent pid (cast to UInt because
// kevent stores ident as uintptr_t).
var registration = kevent(
    ident: UInt(parentPid),
    filter: Int16(EVFILT_PROC),
    flags: UInt16(EV_ADD | EV_ENABLE | EV_ONESHOT),
    fflags: NOTE_EXIT,
    data: 0,
    udata: nil
)

if kevent(kq, &registration, 1, nil, 0, nil) < 0 {
    // ESRCH (3) here means the parent already exited between the spawn
    // and our registration call — race condition. Best to restore
    // hotkeys immediately rather than wait forever.
    let savedErrno = errno
    if savedErrno == ESRCH {
        logLine("parent already gone at registration; restoring hot keys immediately")
    } else {
        logLine("kevent register failed: errno=\(savedErrno); attempting restoration anyway")
    }
    restoreSymbolicHotKeys()
    exit(0)
}

// Block until the parent dies. No timeout — the watchdog's whole job is
// to wait. If launchd or the user kills US, we exit silently (and the
// user gets the "main app crashed" failure mode this watchdog is meant
// to prevent). Documented above.
var triggered = kevent()
let n = kevent(kq, nil, 0, &triggered, 1, nil)
if n < 0 {
    logLine("kevent wait failed: errno=\(errno); attempting restoration anyway")
}

logLine("parent pid=\(parentPid) exited; restoring symbolic hot keys")
restoreSymbolicHotKeys()
exit(0)

func restoreSymbolicHotKeys() {
    // IDs match `SkyLight.swift`'s `SymbolicHotKey` enum:
    //   1 = commandTab, 2 = commandShiftTab, 6 = commandKeyAboveTab
    // (cmd+`/§ etc. — covers non-US layouts).
    let keys: [Int32] = [1, 2, 6]
    for k in keys {
        let err = _CGSSetSymbolicHotKeyEnabled(k, true)
        if err != .success {
            logLine("CGSSetSymbolicHotKeyEnabled(\(k), true) failed: \(err.rawValue)")
        }
    }
}
