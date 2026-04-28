@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.shish.kaltswitch.native

import com.shish.kaltswitch.model.Window
import kotlinx.cinterop.CPointerVarOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.ApplicationServices.AXIsProcessTrusted
import platform.ApplicationServices.AXUIElementCopyAttributeValue
import platform.ApplicationServices.AXUIElementCreateApplication
import platform.ApplicationServices.AXUIElementRef
import platform.ApplicationServices.kAXErrorSuccess
import platform.ApplicationServices.kAXTitleAttribute
import platform.ApplicationServices.kAXWindowsAttribute
import platform.CoreFoundation.CFArrayGetCount
import platform.CoreFoundation.CFArrayGetValueAtIndex
import platform.CoreFoundation.CFArrayRef
import platform.CoreFoundation.CFHash
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.Foundation.CFBridgingRelease

/** Whether the host process has Accessibility permission granted. */
fun isAxTrusted(): Boolean = AXIsProcessTrusted()

/**
 * One-shot AX query: enumerate windows of [pid] and read their titles.
 * Returns empty list if AX isn't trusted, the app has no AX-visible windows, or the query fails.
 *
 * Window IDs come from CFHash of the AXUIElement, so repeated queries return stable IDs
 * for the same underlying window.
 */
fun queryWindows(pid: Int): List<Window> {
    val app = AXUIElementCreateApplication(pid) ?: return emptyList()
    val windowsAttr = kAXWindowsAttribute.toCFString() ?: run {
        CFRelease(app); return emptyList()
    }
    return try {
        memScoped {
            val out = alloc<CPointerVarOf<CFTypeRef>>()
            val err = AXUIElementCopyAttributeValue(app, windowsAttr, out.ptr)
            if (err != kAXErrorSuccess) return@memScoped emptyList()
            val arrayRef = out.value ?: return@memScoped emptyList()
            try {
                val array: CFArrayRef = arrayRef.reinterpret()
                val count = CFArrayGetCount(array)
                buildList(count.toInt()) {
                    for (i in 0 until count) {
                        val rawWindow = CFArrayGetValueAtIndex(array, i) ?: continue
                        @Suppress("UNCHECKED_CAST")
                        val winRef = rawWindow as AXUIElementRef
                        val title = readStringAttribute(winRef, kAXTitleAttribute) ?: ""
                        val id = CFHash(winRef).toLong()
                        add(Window(id = id, pid = pid, title = title))
                    }
                }
            } finally {
                CFRelease(arrayRef)
            }
        }
    } finally {
        CFRelease(windowsAttr)
        CFRelease(app)
    }
}

private fun readStringAttribute(element: AXUIElementRef, attribute: String): String? {
    val cfAttr = attribute.toCFString() ?: return null
    return try {
        memScoped {
            val out = alloc<CPointerVarOf<CFTypeRef>>()
            val err = AXUIElementCopyAttributeValue(element, cfAttr, out.ptr)
            if (err != kAXErrorSuccess) return@memScoped null
            val ref = out.value ?: return@memScoped null
            // CFBridgingRelease consumes the +1 retain and bridges CFStringRef → String.
            CFBridgingRelease(ref) as? String
        }
    } finally {
        CFRelease(cfAttr)
    }
}

private fun String.toCFString(): CFStringRef? =
    CFStringCreateWithCString(null, this, kCFStringEncodingUTF8)
