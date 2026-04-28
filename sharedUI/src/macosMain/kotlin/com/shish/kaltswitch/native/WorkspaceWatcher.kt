@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.shish.kaltswitch.native

import com.shish.kaltswitch.model.ActivationEvent
import com.shish.kaltswitch.model.App
import com.shish.kaltswitch.store.WorldStore
import platform.AppKit.NSApplicationActivationPolicy.NSApplicationActivationPolicyRegular
import platform.AppKit.NSRunningApplication
import platform.AppKit.NSWorkspace
import platform.AppKit.NSWorkspaceApplicationKey
import platform.AppKit.NSWorkspaceDidActivateApplicationNotification
import platform.AppKit.NSWorkspaceDidLaunchApplicationNotification
import platform.AppKit.NSWorkspaceDidTerminateApplicationNotification
import platform.AppKit.runningApplications
import platform.Foundation.NSDate
import platform.Foundation.NSNotification
import platform.Foundation.NSOperationQueue
import platform.Foundation.timeIntervalSince1970
import platform.darwin.NSObjectProtocol

/**
 * Subscribes to [NSWorkspace] notifications and feeds app-level events into [WorldStore].
 * Window-level events come from the AX observer (see AxWatcher, to follow).
 */
class WorkspaceWatcher(private val store: WorldStore) {
    private val workspace = NSWorkspace.sharedWorkspace
    private val center = workspace.notificationCenter
    private val observers = mutableListOf<NSObjectProtocol>()

    fun start() {
        seedRunningApps()
        observe(NSWorkspaceDidLaunchApplicationNotification) { onLaunched(it) }
        observe(NSWorkspaceDidTerminateApplicationNotification) { onTerminated(it) }
        observe(NSWorkspaceDidActivateApplicationNotification) { onActivated(it) }
    }

    fun stop() {
        observers.forEach { center.removeObserver(it) }
        observers.clear()
    }

    private fun observe(name: String?, block: (NSNotification) -> Unit) {
        val obs = center.addObserverForName(
            name = name,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { notification: NSNotification? ->
            notification?.let(block)
        }
        observers.add(obs)
    }

    @Suppress("UNCHECKED_CAST")
    private fun seedRunningApps() {
        val list = (workspace.runningApplications as List<NSRunningApplication>)
            .filter { it.activationPolicy == NSApplicationActivationPolicyRegular }
        store.setRunningApps(list.map { it.toApp() }.associateBy { it.pid })
        for (nsApp in list) refreshWindows(nsApp.processIdentifier)
    }

    private fun onLaunched(n: NSNotification) {
        val nsApp = n.runningApp() ?: return
        if (nsApp.activationPolicy != NSApplicationActivationPolicyRegular) return
        store.addRunningApp(nsApp.toApp())
        refreshWindows(nsApp.processIdentifier)
    }

    private fun onTerminated(n: NSNotification) {
        val nsApp = n.runningApp() ?: return
        store.removeRunningApp(nsApp.processIdentifier)
    }

    private fun onActivated(n: NSNotification) {
        val nsApp = n.runningApp() ?: return
        if (nsApp.activationPolicy != NSApplicationActivationPolicyRegular) return
        val pid = nsApp.processIdentifier
        store.recordEvent(
            ActivationEvent(
                timestampMs = nowMillis(),
                pid = pid,
                windowId = null,
            )
        )
        refreshWindows(pid)
    }

    private fun refreshWindows(pid: Int) {
        store.setWindows(pid, queryWindows(pid))
    }
}

private fun NSNotification.runningApp(): NSRunningApplication? =
    userInfo?.get(NSWorkspaceApplicationKey) as? NSRunningApplication

private fun NSRunningApplication.toApp(): App = App(
    pid = processIdentifier,
    bundleId = bundleIdentifier,
    name = localizedName ?: bundleIdentifier ?: "Unknown",
)

private fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()
