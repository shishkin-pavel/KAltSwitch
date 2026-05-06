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
 * Render `NSRunningApplication.icon` (a vector `NSImage`) to a square PNG
 * and push the bytes into [store].
 *
 * Skia (Compose's renderer) decodes PNG natively; `NSImage.tiffRepresentation`
 * — the default — would also need a PNG-encoded bitmap somewhere along the
 * way. Doing the encode here keeps the boundary clean: `WorldStore` only
 * ever stores PNG bytes per-pid.
 *
 * Side picked at 384 px (= 128 × 3) so the user's "Cell size" slider can
 * scale the overlay up to 300 % without the rasterised icon visibly
 * blurring. macOS app icons are typically multi-rep `.icns` (up to
 * 1024 px), so drawing into a 384-px bitmap rasterises a high-fidelity
 * representation rather than upscaling a 128-px one. At 100 % the
 * 384-px PNG is downscaled by Compose with bilinear filtering — the
 * extra few KB per app outweighs the cost of dynamic re-renders on
 * every slider tick.
 */
fun renderAndStoreAppIcon(pid: Int, store: WorldStore) {
    val nsApp = NSRunningApplication.runningApplicationWithProcessIdentifier(pid) ?: return
    val image = nsApp.icon ?: return

    val side: Long = 384
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
