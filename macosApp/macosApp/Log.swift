import Foundation
import ComposeAppMac

/// Single point of entry for diagnostic logging from Swift. Forwards to the
/// shared Kotlin formatter (`LogBridgeKt.macosLog`) so timestamps and the
/// stdout/stderr redirect to `~/Library/Logs/KAltSwitch.log` are configured
/// in one place. Swift call sites stay unchanged: `log("[swift] ...")`.
func log(_ message: String) {
    LogBridgeKt.macosLog(message: message)
}
