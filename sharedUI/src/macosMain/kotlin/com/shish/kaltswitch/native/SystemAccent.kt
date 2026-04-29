@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.shish.kaltswitch.native

import com.shish.kaltswitch.log.log
import com.shish.kaltswitch.store.WorldStore
import platform.AppKit.NSColor
import platform.AppKit.NSColorSpace
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import kotlin.math.roundToInt

/**
 * Read `NSColor.controlAccentColor`, convert to sRGB, pack into a `0xRRGGBB`
 * `Long` matching the format the Compose UI expects in `WorldStore.systemAccentRgb`.
 * Returns `null` if the colour cannot be converted (an unusual macOS configuration
 * where the accent isn't expressible in sRGB).
 */
fun readSystemAccentRgb(): Long? {
    val srgb = NSColor.controlAccentColor.colorUsingColorSpace(NSColorSpace.sRGBColorSpace) ?: run {
        log("[accent] failed to convert system accent to sRGB")
        return null
    }
    val r = (srgb.redComponent * 255.0).roundToInt().coerceIn(0, 255)
    val g = (srgb.greenComponent * 255.0).roundToInt().coerceIn(0, 255)
    val b = (srgb.blueComponent * 255.0).roundToInt().coerceIn(0, 255)
    return ((r shl 16) or (g shl 8) or b).toLong()
}

/**
 * Mirror the system accent colour into [store]: read it once now, then keep it
 * fresh by observing `NSSystemColorsDidChangeNotification` (fires when the user
 * picks a new highlight colour in System Settings → Appearance). Returns the
 * NSObjectProtocol observer token so the caller can detach it on shutdown.
 */
fun observeSystemAccent(store: WorldStore): platform.darwin.NSObjectProtocol {
    fun push() {
        readSystemAccentRgb()?.let { store.setSystemAccentRgb(it) }
    }
    push()
    return NSNotificationCenter.defaultCenter.addObserverForName(
        name = "NSSystemColorsDidChangeNotification",
        `object` = null,
        queue = NSOperationQueue.mainQueue,
    ) { _ -> push() }
}
