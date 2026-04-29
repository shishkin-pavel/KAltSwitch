@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.shish.kaltswitch.log

import platform.Foundation.NSBundle
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSISO8601DateFormatter
import platform.Foundation.NSLibraryDirectory
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.posix.fflush
import platform.posix.freopen
import platform.posix.setbuf
import platform.posix.stderr
import platform.posix.stdout

/**
 * Redirect stdout + stderr to `~/Library/Logs/KAltSwitch.log` and write a
 * session-start marker. Idempotent enough — re-calls open the file in append
 * mode and overwrite the C library streams again with the same target. Mirrors
 * the prior Swift implementation; the only reason this lives on the Kotlin
 * side now is to keep platform glue uniformly in `macosMain` rather than
 * scattered across Swift and cinterop.
 *
 * Each launch starts with a clearly-greppable separator so a multi-day log can
 * be sliced down to the most recent session in one shell command:
 * `awk '/^=== SESSION START/{buf=""}{buf=buf$0"\n"}END{print buf}' KAltSwitch.log`
 */
fun redirectStderrToLogFile() {
    val fm = NSFileManager.defaultManager
    @Suppress("UNCHECKED_CAST")
    val urls = fm.URLsForDirectory(
        directory = NSLibraryDirectory,
        inDomains = NSUserDomainMask,
    ) as List<NSURL>
    val library = urls.firstOrNull() ?: return
    val logsDir = library.URLByAppendingPathComponent("Logs", isDirectory = true) ?: return
    fm.createDirectoryAtURL(logsDir, withIntermediateDirectories = true, attributes = null, error = null)
    val logURL = logsDir.URLByAppendingPathComponent("KAltSwitch.log") ?: return
    val logPath = logURL.path ?: return

    // "a" = append. Both stderr and stdout get the same file so NSLog output
    // (stderr) and any println() output (stdout) sit side-by-side.
    freopen(logPath, "a", stderr)
    freopen(logPath, "a", stdout)
    setbuf(stderr, null)
    setbuf(stdout, null)

    val info = NSBundle.mainBundle.infoDictionary
    val bundleVersion = info?.get("CFBundleVersion") as? String ?: "unknown"
    val bundleShort = info?.get("CFBundleShortVersionString") as? String ?: "unknown"
    val pid = NSProcessInfo.processInfo.processIdentifier
    val date = NSISO8601DateFormatter().stringFromDate(NSDate())
    println("=== SESSION START at $date pid=$pid version=$bundleShort build=$bundleVersion log=$logPath")
    fflush(stdout)
}
