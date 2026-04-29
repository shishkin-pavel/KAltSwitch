@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.shish.kaltswitch.native

import com.shish.kaltswitch.store.WorldStore
import platform.AppKit.NSApplicationActivationPolicy
import platform.AppKit.NSRunningApplication
import platform.Foundation.timeIntervalSince1970

/**
 * Read all the fields the inspector + switcher need from a running app
 * (via `NSRunningApplication.runningApplicationWithProcessIdentifier`),
 * convert them to the primitive shape `WorldStore.upsertAppFields` expects,
 * and push the icon PNG. One Swift→Kotlin round-trip instead of one per
 * field.
 *
 * Returns `false` if the pid no longer maps to a running application —
 * useful for the spawn path which filters those out.
 */
fun upsertAppRecord(pid: Int, store: WorldStore): Boolean {
    val nsApp = NSRunningApplication.runningApplicationWithProcessIdentifier(pid) ?: return false
    val policy: Long = when (nsApp.activationPolicy) {
        NSApplicationActivationPolicy.NSApplicationActivationPolicyRegular -> 0L
        NSApplicationActivationPolicy.NSApplicationActivationPolicyAccessory -> 1L
        else -> 2L
    }
    val launchMs: Long = nsApp.launchDate?.let { (it.timeIntervalSince1970() * 1000).toLong() } ?: 0L
    store.upsertAppFields(
        pid = pid,
        bundleId = nsApp.bundleIdentifier,
        name = nsApp.localizedName ?: nsApp.bundleIdentifier ?: "Unknown",
        activationPolicyRaw = policy,
        isHidden = nsApp.hidden,
        isFinishedLaunching = nsApp.finishedLaunching,
        executablePath = nsApp.executableURL?.path,
        launchDateMillis = launchMs,
    )
    renderAndStoreAppIcon(pid, store)
    return true
}
