@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.shish.kaltswitch.native

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import platform.AppKit.NSRunningApplication
import platform.AppKit.NSWorkspace
import platform.AppKit.runningApplications

class AppRecordTest {

    /**
     * [activationPolicyRaw] exists only to survive the values the cinterop enum
     * cannot represent; for everything it *can* represent it has to agree with
     * the enum accessor, or the switcher would start misclassifying live apps.
     */
    @Test
    fun activationPolicyRaw_agreesWithEnumAccessor_forLiveApps() {
        val apps = NSWorkspace.sharedWorkspace.runningApplications
            .filterIsInstance<NSRunningApplication>()
        assertTrue(apps.isNotEmpty(), "expected at least one running application to compare against")

        var compared = 0
        for (app in apps) {
            // The enum accessor is the one that throws on an out-of-range
            // value, so an app that exits mid-test must not decide whether
            // this test passes — skip it instead.
            val viaEnum = runCatching { app.activationPolicy.value }.getOrNull() ?: continue
            assertEquals(viaEnum, app.activationPolicyRaw(), "policy mismatch for ${app.bundleIdentifier}")
            compared++
        }
        assertTrue(compared > 0, "no running app yielded a readable activation policy")
    }
}
