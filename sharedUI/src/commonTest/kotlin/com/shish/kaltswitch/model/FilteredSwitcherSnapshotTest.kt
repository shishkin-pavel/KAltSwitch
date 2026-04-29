package com.shish.kaltswitch.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilteredSwitcherSnapshotTest {

    private val regularA = App(pid = 10, bundleId = "a", name = "Regular A")
    private val regularB = App(pid = 20, bundleId = "b", name = "Regular B")
    private val windowless = App(pid = 30, bundleId = "wless", name = "Windowless")
    private val accessory = App(
        pid = 40, bundleId = "acc", name = "Accessory",
        activationPolicy = AppActivationPolicy.Accessory,
    )
    private val winA = Window(id = 100, pid = 10, title = "A1")
    private val winB = Window(id = 200, pid = 20, title = "B1")
    private val winAcc = Window(id = 400, pid = 40, title = "Acc1")

    private fun world(): World {
        val log = ActivationLog()
            .record(ActivationEvent(accessory.pid, winAcc.id))
            .record(ActivationEvent(regularB.pid, winB.id))
            .record(ActivationEvent(regularA.pid, winA.id))   // most recent
        return World(
            log = log,
            runningApps = listOf(regularA, regularB, windowless, accessory).associateBy { it.pid },
            windowsByPid = mapOf(
                regularA.pid to listOf(winA),
                regularB.pid to listOf(winB),
                windowless.pid to emptyList(),
                accessory.pid to listOf(winAcc),
            ),
        )
    }

    @Test
    fun defaultFilters_windowlessAppsGoBehindSeparator() {
        val snap = world().filteredSwitcherSnapshot(Filters())
        // Defaults: windowlessApps = Demote → secondary; everything else = Show.
        val primary = snap.withWindows.map { it.app.name }
        val secondary = snap.windowless.map { it.app.name }
        assertEquals(listOf("Regular A", "Regular B", "Accessory"), primary)
        assertEquals(listOf("Windowless"), secondary)
    }

    @Test
    fun hiddenApps_areDroppedFromSwitcher() {
        val filters = Filters(accessoryApps = TriFilter.Hide)
        val snap = world().filteredSwitcherSnapshot(filters)
        val all = snap.all.map { it.app.name }
        assertFalse("Accessory" in all, "Hide-filtered apps must not appear")
    }

    @Test
    fun demotedApps_landBehindSeparator() {
        val filters = Filters(accessoryApps = TriFilter.Demote)
        val snap = world().filteredSwitcherSnapshot(filters)
        // Accessory now joins Windowless in the secondary group.
        assertEquals(listOf("Regular A", "Regular B"), snap.withWindows.map { it.app.name })
        assertTrue("Accessory" in snap.windowless.map { it.app.name })
        assertTrue("Windowless" in snap.windowless.map { it.app.name })
    }

    @Test
    fun appOrder_matchesInspectorOrderFromTheSameFilters() {
        val filters = Filters()
        val inspector = world().filteredSnapshot(filters)
        val switcher = world().filteredSwitcherSnapshot(filters)
        assertEquals(
            inspector.show.map { it.app.pid } + inspector.demote.map { it.app.pid },
            switcher.all.map { it.app.pid }
        )
    }

    @Test
    fun hiddenWindows_areDroppedFromAppCell() {
        val world = world()
        val filters = Filters(untitledWindows = TriFilter.Hide)
        val withUntitled = world.copy(
            windowsByPid = world.windowsByPid + (regularA.pid to listOf(winA, Window(id = 999, pid = 10, title = ""))),
        )
        val snap = withUntitled.filteredSwitcherSnapshot(filters)
        val a = snap.withWindows.first { it.app.pid == regularA.pid }
        assertEquals(listOf(100L), a.windows.map { it.id })
    }
}
