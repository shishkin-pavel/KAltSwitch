import Foundation

/// Single point of entry for diagnostic event logging from Swift.
///
/// Mirrors the Kotlin `com.shish.kaltswitch.log.log` function — same format
/// `2026-04-29 17:52:03.991 message` written to stdout, which the
/// AppDelegate redirects to `~/Library/Logs/KAltSwitch.log`. Using `print`
/// instead of `NSLog` avoids the `KAltSwitch[pid:tid] KAltSwitch:` prefix
/// `NSLog` injects — that prefix made interleaving Kotlin and Swift lines
/// inconsistent for parsing.
///
/// Reuses one `DateFormatter` instance — constructing one per call is
/// expensive enough to show up under tight log volume.
private let logFormatter: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "yyyy-MM-dd HH:mm:ss.SSS"
    f.locale = Locale(identifier: "en_US_POSIX")
    f.timeZone = TimeZone.current
    return f
}()

func log(_ message: String) {
    print("\(logFormatter.string(from: Date())) \(message)")
}
