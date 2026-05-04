package com.shish.kaltswitch.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorldSnapshotTest {

    private val safari = App(pid = 10, bundleId = "com.apple.Safari", name = "Safari")
    private val ide = App(pid = 20, bundleId = "com.jetbrains.intellij", name = "IntelliJ IDEA")
    private val finder = App(pid = 30, bundleId = "com.apple.finder", name = "Finder")
    private val launchpad = App(pid = 40, bundleId = "com.apple.launchpad", name = "Launchpad")  // no windows

    private val safariA = Window(id = 100, pid = 10, title = "GitHub")
    private val safariB = Window(id = 101, pid = 10, title = "Hacker News")
    private val ideMain = Window(id = 200, pid = 20, title = "KAltSwitch")
    private val ideMisc = Window(id = 201, pid = 20, title = "Another project")
    private val finderWin = Window(id = 300, pid = 30, title = "Downloads")

    private fun world(log: ActivationLog) = World(
        log = log,
        runningApps = listOf(safari, ide, finder, launchpad).associateBy { it.pid },
        windowsByPid = mapOf(
            safari.pid to listOf(safariA, safariB),
            ide.pid to listOf(ideMain, ideMisc),
            finder.pid to listOf(finderWin),
            launchpad.pid to emptyList(),
        ),
    )

    @Test
    fun snapshot_ordersAppsByActivationRecency_windowlessAtEnd() {
        val log = ActivationLog()
            .record(ActivationEvent(finder.pid, finderWin.id))
            .record(ActivationEvent(ide.pid, ideMisc.id))
            .record(ActivationEvent(safari.pid, safariA.id))
            .record(ActivationEvent(ide.pid, ideMain.id))     // ide bumps ahead of safari
        val snap = world(log).snapshot()
        assertEquals(listOf("IntelliJ IDEA", "Safari", "Finder"), snap.withWindows.map { it.app.name })
        assertEquals(listOf("Launchpad"), snap.windowless.map { it.app.name })
    }

    @Test
    fun snapshot_ordersWindowsWithinApp_byRecencyThenInputOrder() {
        val log = ActivationLog()
            .record(ActivationEvent(safari.pid, safariB.id))   // B first
            .record(ActivationEvent(safari.pid, safariA.id))   // A bumps ahead
        val snap = world(log).snapshot()
        val safariEntry = snap.withWindows.first { it.app.name == "Safari" }
        assertEquals(listOf(safariA, safariB), safariEntry.windows)
    }

    @Test
    fun snapshot_appendsNeverActivatedWindowsAtEnd() {
        val log = ActivationLog()
            .record(ActivationEvent(safari.pid, safariA.id))   // only A activated, B never
        val safariEntry = world(log).snapshot().withWindows.first { it.app.name == "Safari" }
        assertEquals(listOf(safariA, safariB), safariEntry.windows)
    }

    @Test
    fun snapshot_includesRunningAppsWithNoActivationHistory() {
        val log = ActivationLog()  // empty
        val snap = world(log).snapshot()
        // All non-windowless apps should appear; ordering between them is implementation-defined,
        // but each should be present.
        val withWindowsNames = snap.withWindows.map { it.app.name }.toSet()
        assertTrue("Safari" in withWindowsNames)
        assertTrue("IntelliJ IDEA" in withWindowsNames)
        assertTrue("Finder" in withWindowsNames)
        assertEquals(listOf("Launchpad"), snap.windowless.map { it.app.name })
    }

    @Test
    fun snapshot_keepsRecentlyActivatedWindowlessAppInRecencyOrder() {
        // Regression: an app that was just focused but has no live windows
        // (Finder after closing every window, Bitwarden flipping to
        // accessory, etc.) used to be routed to step 3 — alphabetical bucket
        // — even though its pid sat at the head of appOrder. The user saw a
        // just-used app fall to the tail of the switcher.
        val log = ActivationLog()
            .record(ActivationEvent(safari.pid, safariA.id))
            .record(ActivationEvent(launchpad.pid, null))   // app-level activation, windowless
        val world = World(
            log = log,
            runningApps = mapOf(safari.pid to safari, launchpad.pid to launchpad),
            windowsByPid = mapOf(safari.pid to listOf(safariA), launchpad.pid to emptyList()),
        )
        val snap = world.snapshot()
        // Launchpad just got an app-level activation → it belongs at the
        // head of withWindows (recency), even though it has no windows.
        // Safari sits behind it because Launchpad is more recent.
        assertEquals(listOf("Launchpad", "Safari"), snap.withWindows.map { it.app.name })
        assertEquals(emptyList(), snap.windowless.map { it.app.name })
    }

    @Test
    fun snapshot_neverActivatedWindowlessAppGoesAlphabetically() {
        // Counterpart of the above: an app that has never been focused and
        // has no windows has no recency to anchor on, so it lands in step 3
        // (alphabetical bucket).
        val log = ActivationLog().record(ActivationEvent(safari.pid, safariA.id))
        val world = World(
            log = log,
            runningApps = mapOf(safari.pid to safari, launchpad.pid to launchpad),
            windowsByPid = mapOf(safari.pid to listOf(safariA), launchpad.pid to emptyList()),
        )
        val snap = world.snapshot()
        assertEquals(listOf("Safari"), snap.withWindows.map { it.app.name })
        assertEquals(listOf("Launchpad"), snap.windowless.map { it.app.name })
    }

    @Test
    fun snapshot_dropsAppsThatDiedSinceTheirLastActivation() {
        val log = ActivationLog()
            .record(ActivationEvent(pid = 999, windowId = 9999))  // unknown pid
            .record(ActivationEvent(safari.pid, safariA.id))
        val snap = world(log).snapshot()
        val pids = snap.all.map { it.app.pid }.toSet()
        assertTrue(999 !in pids)
        assertTrue(safari.pid in pids)
    }
}
