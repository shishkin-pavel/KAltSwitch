@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.shish.kaltswitch.log

import kotlin.concurrent.Volatile
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.localTimeZone

/**
 * Reuse a single formatter instance — `NSDateFormatter` is expensive to
 * construct. Configured to match the timestamp prefix that NSLog uses on
 * the Swift side so a `tail -f` stream interleaves coherently.
 */
private val formatter: NSDateFormatter = NSDateFormatter().apply {
    dateFormat = "yyyy-MM-dd HH:mm:ss.SSS"
    locale = NSLocale("en_US_POSIX")
    timeZone = NSTimeZone.localTimeZone
}

/**
 * Master switch for diagnostic logging. Settings → General → Behaviour pushes
 * the persisted [com.shish.kaltswitch.config.AppConfig.loggingEnabled] here
 * whenever it changes. Defaults to `true` so the early-launch path
 * (`applicationWillFinishLaunching`, AX-registry boot) still records events
 * before the config flow has had a chance to apply.
 *
 * The SESSION START banner in [redirectStderrToLogFile] is intentionally not
 * gated by this flag — keeping the marker in the file lets a later toggle-on
 * still slice the log to "this session" via the standard awk recipe.
 */
@Volatile
var loggingEnabled: Boolean = true

actual fun log(message: String) {
    if (!loggingEnabled) return
    val now = formatter.stringFromDate(NSDate())
    // Each log call writes a single line; setbuf(stdout, nil) on the Swift
    // side flushes immediately so events from Kotlin and NSLog stay in
    // chronological order.
    println("$now $message")
}
