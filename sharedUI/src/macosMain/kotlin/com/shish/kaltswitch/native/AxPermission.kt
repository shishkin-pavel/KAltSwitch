@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.shish.kaltswitch.native

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.reinterpret
import platform.ApplicationServices.AXIsProcessTrustedWithOptions
import platform.ApplicationServices.kAXTrustedCheckOptionPrompt
import platform.CoreFoundation.CFDictionaryCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFBooleanTrue

/**
 * Trigger macOS's "would like to control your computer" prompt for Accessibility.
 *
 * If the process isn't yet trusted, macOS shows its standard dialog with a button
 * that opens Settings → Privacy → Accessibility with our app pre-selected.
 * The user still has to flip the toggle and relaunch — the prompt is just a shortcut.
 *
 * Returns the trust state at call time (always `false` if a fresh prompt is shown).
 */
fun requestAxPermission(): Boolean {
    val key: COpaquePointer = kAXTrustedCheckOptionPrompt?.reinterpret() ?: return false
    val value: COpaquePointer = kCFBooleanTrue?.reinterpret() ?: return false
    return memScoped {
        val keys = allocArrayOf(key)
        val values = allocArrayOf(value)
        val dict = CFDictionaryCreate(null, keys, values, 1, null, null)
        try {
            AXIsProcessTrustedWithOptions(dict)
        } finally {
            if (dict != null) CFRelease(dict)
        }
    }
}
