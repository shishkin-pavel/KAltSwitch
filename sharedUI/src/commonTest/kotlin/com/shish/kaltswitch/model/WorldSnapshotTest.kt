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
        val log = ActivationLog().apply {
            record(ActivationEvent(1, finder.pid, finderWin.id))
            record(ActivationEvent(2, ide.pid, ideMisc.id))
            record(ActivationEvent(3, safari.pid, safariA.id))
            record(ActivationEvent(4, ide.pid, ideMain.id))     // ide bumps ahead of safari
        }
        val snap = world(log).snapshot()
        assertEquals(listOf("IntelliJ IDEA", "Safari", "Finder"), snap.withWindows.map { it.app.name })
        assertEquals(listOf("Launchpad"), snap.windowless.map { it.app.name })
    }

    @Test
    fun snapshot_ordersWindowsWithinApp_byRecencyThenInputOrder() {
        val log = ActivationLog().apply {
            record(ActivationEvent(1, safari.pid, safariB.id))   // B first
            record(ActivationEvent(2, safari.pid, safariA.id))   // A bumps ahead
        }
        val snap = world(log).snapshot()
        val safariEntry = snap.withWindows.first { it.app.name == "Safari" }
        assertEquals(listOf(safariA, safariB), safariEntry.windows)
    }

    @Test
    fun snapshot_appendsNeverActivatedWindowsAtEnd() {
        val log = ActivationLog().apply {
            record(ActivationEvent(1, safari.pid, safariA.id))   // only A activated, B never
        }
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
    fun snapshot_dropsAppsThatDiedSinceTheirLastActivation() {
        val log = ActivationLog().apply {
            record(ActivationEvent(1, pid = 999, windowId = 9999))  // unknown pid
            record(ActivationEvent(2, safari.pid, safariA.id))
        }
        val snap = world(log).snapshot()
        val pids = snap.all.map { it.app.pid }.toSet()
        assertTrue(999 !in pids)
        assertTrue(safari.pid in pids)
    }
}
