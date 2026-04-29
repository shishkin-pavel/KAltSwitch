@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.shish.kaltswitch.log

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

actual fun log(message: String) {
    val now = formatter.stringFromDate(NSDate())
    // Each log call writes a single line; setbuf(stdout, nil) on the Swift
    // side flushes immediately so events from Kotlin and NSLog stay in
    // chronological order.
    println("$now $message")
}
