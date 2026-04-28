package com.shish.kaltswitch.model

import kotlin.test.Test
import kotlin.test.assertEquals

class SwitcherTest {

    private fun snapshotOf(vararg appsAndWindowCounts: Pair<String, Int>): SwitcherSnapshot {
        val entries = appsAndWindowCounts.mapIndexed { i, (name, n) ->
            val app = App(pid = i + 1, bundleId = null, name = name)
            val windows = (0 until n).map { Window(id = (i + 1) * 100L + it, pid = app.pid, title = "$name#$it") }
            AppEntry(app, windows)
        }
        val (with, without) = entries.partition { it.hasWindows }
        return SwitcherSnapshot(with, without)
    }

    @Test
    fun appEntry_defaultCursor_pointsAtSecondMostRecentApp_firstWindow() {
        val snap = snapshotOf("Safari" to 3, "IDE" to 2, "Finder" to 1)
        val cursor = snap.defaultCursor(SwitcherEntry.App)
        assertEquals(SwitcherCursor(appIndex = 1, windowIndex = 0), cursor)
    }

    @Test
    fun appEntry_defaultCursor_clampsWhenOnlyOneApp() {
        val snap = snapshotOf("Solo" to 2)
        val cursor = snap.defaultCursor(SwitcherEntry.App)
        assertEquals(SwitcherCursor(appIndex = 0, windowIndex = 0), cursor)
    }

    @Test
    fun windowEntry_defaultCursor_pointsAtCurrentApp_secondWindow() {
        val snap = snapshotOf("Safari" to 3, "IDE" to 2)
        val cursor = snap.defaultCursor(SwitcherEntry.Window)
        assertEquals(SwitcherCursor(appIndex = 0, windowIndex = 1), cursor)
    }

    @Test
    fun windowEntry_defaultCursor_clampsWhenOnlyOneWindow() {
        val snap = snapshotOf("Safari" to 1, "IDE" to 2)
        val cursor = snap.defaultCursor(SwitcherEntry.Window)
        assertEquals(SwitcherCursor(appIndex = 0, windowIndex = 0), cursor)
    }

    @Test
    fun navigation_nextApp_resetsWindowIndex() {
        val snap = snapshotOf("Safari" to 2, "IDE" to 3)
        val state = openSwitcher(snap, SwitcherEntry.Window)  // app=0, window=1
        val next = state.apply(SwitcherEvent.NextApp)
        assertEquals(SwitcherCursor(appIndex = 1, windowIndex = 0), next.cursor)
    }

    @Test
    fun navigation_wrapsAroundEdges() {
        val snap = snapshotOf("Safari" to 1, "IDE" to 1)
        val state = openSwitcher(snap, SwitcherEntry.App)  // app=1
        val wrapped = state.apply(SwitcherEvent.NextApp).apply(SwitcherEvent.NextApp)  // 1 -> 0 -> 1
        assertEquals(1, wrapped.cursor.appIndex)
        val back = state.apply(SwitcherEvent.PrevApp).apply(SwitcherEvent.PrevApp)
        assertEquals(1, back.cursor.appIndex)
    }

    @Test
    fun navigation_nextWindow_wrapsWithinApp() {
        val snap = snapshotOf("Safari" to 3)
        val state = openSwitcher(snap, SwitcherEntry.App)  // window=0
        val s2 = state.apply(SwitcherEvent.NextWindow)
        val s3 = s2.apply(SwitcherEvent.NextWindow)
        val s4 = s3.apply(SwitcherEvent.NextWindow)  // wraps
        assertEquals(1, s2.cursor.windowIndex)
        assertEquals(2, s3.cursor.windowIndex)
        assertEquals(0, s4.cursor.windowIndex)
    }

    @Test
    fun selectedWindow_returnsCorrectWindow() {
        val snap = snapshotOf("Safari" to 3, "IDE" to 2)
        val state = openSwitcher(snap, SwitcherEntry.App)  // app=1, window=0
        val selectedTitle = state.selectedWindow?.title
        assertEquals("IDE#0", selectedTitle)
    }
}
