package com.shish.kaltswitch.log

/**
 * Stable Swift-facing entry point so the AppKit shell can route Swift-side
 * diagnostics through the same single-line formatter as the Kotlin code,
 * keeping the merged log file's timestamps consistent.
 *
 * Filename without a dot so the Kotlin/Native ObjC export class name is
 * predictable (`LogBridgeKt`).
 */
fun macosLog(message: String) = log(message)
