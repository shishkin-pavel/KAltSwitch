@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.shish.kaltswitch.native

import com.shish.kaltswitch.store.WorldStore
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.AppKit.NSBitmapImageRep
import platform.AppKit.NSCompositeCopy
import platform.AppKit.NSDeviceRGBColorSpace
import platform.AppKit.NSGraphicsContext
import platform.AppKit.NSPNGFileType
import platform.AppKit.NSRunningApplication
import platform.AppKit.representationUsingType
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.posix.memcpy

/**
 * Render `NSRunningApplication.icon` (a vector `NSImage`) to a 128×128 PNG
 * and push the bytes into [store].
 *
 * Skia (Compose's renderer) decodes PNG natively; `NSImage.tiffRepresentation`
 * — the default — would also need a PNG-encoded bitmap somewhere along the
 * way. Doing the encode here keeps the boundary clean: `WorldStore` only
 * ever stores PNG bytes per-pid.
 */
fun renderAndStoreAppIcon(pid: Int, store: WorldStore) {
    val nsApp = NSRunningApplication.runningApplicationWithProcessIdentifier(pid) ?: return
    val image = nsApp.icon ?: return

    val side: Long = 128
    val bitmap = NSBitmapImageRep(
        bitmapDataPlanes = null,
        pixelsWide = side,
        pixelsHigh = side,
        bitsPerSample = 8,
        samplesPerPixel = 4,
        hasAlpha = true,
        isPlanar = false,
        colorSpaceName = NSDeviceRGBColorSpace,
        bytesPerRow = 0,
        bitsPerPixel = 32,
    )
    bitmap.setSize(CGSizeMake(side.toDouble(), side.toDouble()))

    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.setCurrentContext(NSGraphicsContext.graphicsContextWithBitmapImageRep(bitmap))
    image.drawInRect(
        rect = CGRectMake(0.0, 0.0, side.toDouble(), side.toDouble()),
        fromRect = CGRectMake(0.0, 0.0, 0.0, 0.0),
        operation = NSCompositeCopy,
        fraction = 1.0,
    )
    NSGraphicsContext.restoreGraphicsState()

    val pngData: NSData = bitmap.representationUsingType(NSPNGFileType, emptyMap<Any?, Any?>()) ?: return
    store.setAppIconPng(pid, pngData.toByteArray())
}

/** NSData → Kotlin ByteArray via a single pinned `memcpy`. */
private fun NSData.toByteArray(): ByteArray {
    val len = length.toInt()
    if (len == 0) return ByteArray(0)
    val out = ByteArray(len)
    out.usePinned { pinned ->
        memcpy(pinned.addressOf(0), this.bytes, length)
    }
    return out
}
