@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.shish.kaltswitch.native

import com.shish.kaltswitch.model.ActivationEvent
import com.shish.kaltswitch.model.App
import com.shish.kaltswitch.model.WindowId
import com.shish.kaltswitch.store.WorldStore
import platform.AppKit.NSApplicationActivationPolicy.NSApplicationActivationPolicyProhibited
import platform.AppKit.NSRunningApplication
import platform.AppKit.NSWorkspace
import platform.AppKit.NSWorkspaceApplicationKey
import platform.AppKit.NSWorkspaceDidActivateApplicationNotification
import platform.AppKit.NSWorkspaceDidLaunchApplicationNotification
import platform.AppKit.NSWorkspaceDidTerminateApplicationNotification
import platform.AppKit.runningApplications
import platform.Foundation.NSDate
import platform.Foundation.NSLog
import platform.Foundation.NSNotification
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSTimer
import platform.Foundation.timeIntervalSince1970
import platform.darwin.NSObjectProtocol

/**
 * Subscribes to [NSWorkspace] notifications and feeds app/window state into [WorldStore].
 *
 * Three input streams:
 *  - NSWorkspace launch/terminate/activate notifications drive [App] membership and app-level
 *    activation events.
 *  - On each app activation we re-query the activated app's windows via AX.
 *  - A periodic timer polls the *currently frontmost* app's windows + focused window. This
 *    catches "user opened a new window in already-active app" and "user switched between
 *    windows of the same app via cmd+`" without an `AXObserver`. Latency = poll interval.
 *
 * Replace this with a real `AXObserver` per app for instant updates — see follow-up.
 */
class WorkspaceWatcher(private val store: WorldStore) {
    private val workspace = NSWorkspace.sharedWorkspace
    private val center = workspace.notificationCenter
    private val observers = mutableListOf<NSObjectProtocol>()
    private var pollTimer: NSTimer? = null
    private val lastFocused = mutableMapOf<Int, WindowId>()

    fun start() {
        val trusted = isAxTrusted()
        store.setAxTrusted(trusted)
        NSLog("KAltSwitch: AX trusted = $trusted")
        seedRunningApps(querySome = trusted)
        observe(NSWorkspaceDidLaunchApplicationNotification) { onLaunched(it) }
        observe(NSWorkspaceDidTerminateApplicationNotification) { onTerminated(it) }
        observe(NSWorkspaceDidActivateApplicationNotification) { onActivated(it) }
        pollTimer = NSTimer.scheduledTimerWithTimeInterval(
            interval = POLL_INTERVAL_SECONDS,
            repeats = true,
        ) { _ -> tick() }
    }

    fun stop() {
        observers.forEach { center.removeObserver(it) }
        observers.clear()
        pollTimer?.invalidate()
        pollTimer = null
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
    private fun seedRunningApps(querySome: Boolean) {
        val list = (workspace.runningApplications as List<NSRunningApplication>)
            .filter { it.activationPolicy != NSApplicationActivationPolicyProhibited }
        store.setRunningApps(list.map { it.toApp() }.associateBy { it.pid })
        if (!querySome) return
        // Query AX windows for every visible app. With permission granted, AX reports
        // accurate windows — even for accessory (menu-bar-only) apps like Bitwarden,
        // Tailscale that don't show up in cmd+tab natively.
        for (nsApp in list) refreshWindows(nsApp.processIdentifier)
        workspace.frontmostApplication?.let {
            store.setActive(pid = it.processIdentifier, windowId = queryFocusedWindow(it.processIdentifier))
        }
    }

    private fun onLaunched(n: NSNotification) {
        val nsApp = n.runningApp() ?: return
        if (nsApp.activationPolicy == NSApplicationActivationPolicyProhibited) return
        store.addRunningApp(nsApp.toApp())
        refreshWindows(nsApp.processIdentifier)
    }

    private fun onTerminated(n: NSNotification) {
        val nsApp = n.runningApp() ?: return
        store.removeRunningApp(nsApp.processIdentifier)
        lastFocused.remove(nsApp.processIdentifier)
    }

    private fun onActivated(n: NSNotification) {
        val nsApp = n.runningApp() ?: return
        if (nsApp.activationPolicy == NSApplicationActivationPolicyProhibited) return
        val pid = nsApp.processIdentifier
        store.recordEvent(ActivationEvent(nowMillis(), pid, windowId = null))
        refreshWindows(pid)
        store.setActive(pid = pid, windowId = queryFocusedWindow(pid))
    }

    /**
     * Periodic poll of the frontmost app: re-query its windows + focused window.
     * Catches new/closed windows and same-app focus changes that NSWorkspace doesn't
     * surface on its own.
     */
    private fun tick() {
        val frontmost = workspace.frontmostApplication ?: return
        if (frontmost.activationPolicy == NSApplicationActivationPolicyProhibited) return
        val pid = frontmost.processIdentifier
        refreshWindows(pid)
        val focused = queryFocusedWindow(pid)
        store.setActive(pid = pid, windowId = focused)
        if (focused != null && lastFocused[pid] != focused) {
            lastFocused[pid] = focused
            store.recordEvent(ActivationEvent(nowMillis(), pid, windowId = focused))
        }
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

private const val POLL_INTERVAL_SECONDS: Double = 0.5
