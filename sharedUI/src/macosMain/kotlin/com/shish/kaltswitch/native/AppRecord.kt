@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.shish.kaltswitch.native

import com.shish.kaltswitch.store.WorldStore
import platform.AppKit.NSRunningApplication
import platform.Foundation.NSNumber
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.valueForKey

/** `NSApplicationActivationPolicyProhibited` — what an unreadable policy folds into. */
private const val ACTIVATION_POLICY_PROHIBITED = 2L

/**
 * Raw `NSRunningApplication.activationPolicy`, read through KVC rather than the
 * cinterop property.
 *
 * The generated `nsApp.activationPolicy` getter funnels the `NSInteger` through
 * `NSApplicationActivationPolicy.byValue`, a non-null lookup over the three
 * declared cases that throws `NullPointerException` for anything else. An
 * `NSRunningApplication` stays alive after its process exits, and AppKit only
 * promises that on a terminated app "most properties lose their significance,
 * and some properties may not be available" — so a pid that dies between the AX
 * callback and this read hands back an out-of-range integer. Since
 * `upsertAppRecord` is not `@Throws`, that NPE unwinding into Swift aborts the
 * whole process instead of surfacing as an error.
 *
 * KVC boxes the same integer into an `NSNumber` without ever constructing the
 * enum, so an unknown value stays a number all the way to `upsertAppFields`,
 * which already folds anything outside {0, 1} into `Prohibited`.
 */
internal fun NSRunningApplication.activationPolicyRaw(): Long =
    (valueForKey("activationPolicy") as? NSNumber)?.longValue ?: ACTIVATION_POLICY_PROHIBITED

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
    val launchMs: Long = nsApp.launchDate?.let { (it.timeIntervalSince1970() * 1000).toLong() } ?: 0L
    store.upsertAppFields(
        pid = pid,
        bundleId = nsApp.bundleIdentifier,
        name = nsApp.localizedName ?: nsApp.bundleIdentifier ?: "Unknown",
        activationPolicyRaw = nsApp.activationPolicyRaw(),
        isHidden = nsApp.hidden,
        isFinishedLaunching = nsApp.finishedLaunching,
        executablePath = nsApp.executableURL?.path,
        launchDateMillis = launchMs,
    )
    renderAndStoreAppIcon(pid, store)
    return true
}
