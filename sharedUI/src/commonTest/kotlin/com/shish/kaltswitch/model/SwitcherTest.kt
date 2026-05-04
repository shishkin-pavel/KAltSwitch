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

    // ---- refreshedWith — live snapshot updates -------------------------------

    @Test
    fun refreshedWith_keepsCursorIdentity_whenNothingChanges() {
        val snap = snapshotOf("Safari" to 3, "IDE" to 2)
        val state = openSwitcher(snap, SwitcherEntry.App)  // identity = (pid=2, wid=200)

        val refreshed = state.refreshedWith(snap)
        // No-op fast path: returns the same instance reference (data-class
        // equal isn't enough to dedupe a UI emission, but referential equality
        // is — and a no-op should be cheap).
        assertEquals(state, refreshed)
        assertEquals(2, refreshed.selectedAppPid)
        assertEquals(200L, refreshed.selectedWindowId)
    }

    @Test
    fun refreshedWith_keepsCursor_whenNewWindowAppearsForSelectedApp() {
        // Selected: IDE/200. A new window is added to IDE. Cursor must stay on
        // IDE/200 even though the index of 200 might have changed.
        val before = snapshotOf("Safari" to 3, "IDE" to 2)
        val state = openSwitcher(before, SwitcherEntry.App)  // app=1 (IDE), wid=200
        assertEquals(2, state.selectedAppPid)
        assertEquals(200L, state.selectedWindowId)

        // Add a new window to IDE at the front (newest-first ordering).
        val ide = before.all[1].app
        val newIdeWindows = listOf(
            Window(id = 999, pid = 2, title = "IDE#new"),
            Window(id = 200, pid = 2, title = "IDE#0"),
            Window(id = 201, pid = 2, title = "IDE#1"),
        )
        val after = SwitcherSnapshot(
            withWindows = listOf(
                before.all[0],
                AppEntry(ide, newIdeWindows),
            ),
            windowless = emptyList(),
        )

        val refreshed = state.refreshedWith(after)
        assertEquals(2, refreshed.selectedAppPid)
        assertEquals(200L, refreshed.selectedWindowId)
        // Index reflects the new position: window 200 is now at windowIndex=1.
        assertEquals(1, refreshed.cursor.appIndex)
        assertEquals(1, refreshed.cursor.windowIndex)
    }

    @Test
    fun refreshedWith_movesCursorToRightNeighbour_whenSelectedWindowDisappears() {
        // Selected: IDE/200. IDE has 3 windows. The 200 closes; cursor should
        // move to its right-neighbour in the OLD order, which was IDE/201.
        val ide = App(pid = 2, bundleId = "ide", name = "IDE")
        val before = SwitcherSnapshot(
            withWindows = listOf(
                AppEntry(ide, listOf(
                    Window(id = 200, pid = 2, title = "w200"),
                    Window(id = 201, pid = 2, title = "w201"),
                    Window(id = 202, pid = 2, title = "w202"),
                )),
            ),
            windowless = emptyList(),
        )
        val state = openSwitcher(before, SwitcherEntry.Window).withCursor(SwitcherCursor(0, 0))
        assertEquals(200L, state.selectedWindowId)

        val after = SwitcherSnapshot(
            withWindows = listOf(
                AppEntry(ide, listOf(
                    Window(id = 201, pid = 2, title = "w201"),
                    Window(id = 202, pid = 2, title = "w202"),
                )),
            ),
            windowless = emptyList(),
        )
        val refreshed = state.refreshedWith(after)
        assertEquals(2, refreshed.selectedAppPid)
        assertEquals(201L, refreshed.selectedWindowId)
    }

    @Test
    fun refreshedWith_fallsBackLeft_whenAllRightNeighboursGone() {
        val ide = App(pid = 2, bundleId = "ide", name = "IDE")
        val before = SwitcherSnapshot(
            withWindows = listOf(
                AppEntry(ide, listOf(
                    Window(id = 200, pid = 2, title = "w200"),
                    Window(id = 201, pid = 2, title = "w201"),
                    Window(id = 202, pid = 2, title = "w202"),
                )),
            ),
            windowless = emptyList(),
        )
        // Cursor sits on the rightmost window 202.
        val state = openSwitcher(before, SwitcherEntry.Window).withCursor(SwitcherCursor(0, 2))
        assertEquals(202L, state.selectedWindowId)

        // 202 closes; only 200 survives.
        val after = SwitcherSnapshot(
            withWindows = listOf(
                AppEntry(ide, listOf(Window(id = 200, pid = 2, title = "w200"))),
            ),
            windowless = emptyList(),
        )
        val refreshed = state.refreshedWith(after)
        // Right-neighbour of 202 in old order was nothing → fall back left to 201
        // → not alive → fall back further to 200.
        assertEquals(200L, refreshed.selectedWindowId)
    }

    @Test
    fun refreshedWith_movesCursorToNextApp_whenSelectedAppDisappears() {
        val safari = App(pid = 1, bundleId = "safari", name = "Safari")
        val ide = App(pid = 2, bundleId = "ide", name = "IDE")
        val finder = App(pid = 3, bundleId = "finder", name = "Finder")
        val before = SwitcherSnapshot(
            withWindows = listOf(
                AppEntry(safari, listOf(Window(id = 100, pid = 1, title = "s"))),
                AppEntry(ide,    listOf(Window(id = 200, pid = 2, title = "i"))),
                AppEntry(finder, listOf(Window(id = 300, pid = 3, title = "f"))),
            ),
            windowless = emptyList(),
        )
        // Cursor on IDE.
        val state = openSwitcher(before, SwitcherEntry.App)  // app=1 (IDE)
        assertEquals(2, state.selectedAppPid)

        // IDE quits.
        val after = SwitcherSnapshot(
            withWindows = listOf(
                AppEntry(safari, listOf(Window(id = 100, pid = 1, title = "s"))),
                AppEntry(finder, listOf(Window(id = 300, pid = 3, title = "f"))),
            ),
            windowless = emptyList(),
        )
        val refreshed = state.refreshedWith(after)
        // Right-neighbour of IDE in old snapshot was Finder → cursor jumps there.
        assertEquals(3, refreshed.selectedAppPid)
        assertEquals(300L, refreshed.selectedWindowId)
    }

    @Test
    fun refreshedWith_droppedAppLastInOrder_fallsBackLeft() {
        val safari = App(pid = 1, bundleId = "safari", name = "Safari")
        val ide = App(pid = 2, bundleId = "ide", name = "IDE")
        val before = SwitcherSnapshot(
            withWindows = listOf(
                AppEntry(safari, listOf(Window(id = 100, pid = 1, title = "s"))),
                AppEntry(ide,    listOf(Window(id = 200, pid = 2, title = "i"))),
            ),
            windowless = emptyList(),
        )
        // Cursor on the rightmost (IDE).
        val state = openSwitcher(before, SwitcherEntry.App).withCursor(SwitcherCursor(1, 0))
        assertEquals(2, state.selectedAppPid)

        // IDE quits.
        val after = SwitcherSnapshot(
            withWindows = listOf(AppEntry(safari, listOf(Window(id = 100, pid = 1, title = "s")))),
            windowless = emptyList(),
        )
        val refreshed = state.refreshedWith(after)
        // No right-neighbour → fall back left to Safari.
        assertEquals(1, refreshed.selectedAppPid)
    }

    @Test
    fun refreshedWith_emptyNewSnapshot_clearsSelection() {
        val snap = snapshotOf("Safari" to 1)
        val state = openSwitcher(snap, SwitcherEntry.App)
        assertEquals(1, state.selectedAppPid)

        val refreshed = state.refreshedWith(SwitcherSnapshot.Empty)
        assertEquals(null, refreshed.selectedAppPid)
        assertEquals(null, refreshed.selectedWindowId)
    }

    @Test
    fun refreshedWith_appBecomesWindowless_keepsCursorOnSameApp() {
        val safari = App(pid = 1, bundleId = "safari", name = "Safari")
        val before = SwitcherSnapshot(
            withWindows = listOf(AppEntry(safari, listOf(Window(id = 100, pid = 1, title = "s")))),
            windowless = emptyList(),
        )
        val state = openSwitcher(before, SwitcherEntry.App)
        assertEquals(1, state.selectedAppPid)
        assertEquals(100L, state.selectedWindowId)

        // Safari closes its only window → demoted into windowless bucket.
        val after = SwitcherSnapshot(
            withWindows = emptyList(),
            windowless = listOf(AppEntry(safari, emptyList())),
        )
        val refreshed = state.refreshedWith(after)
        // App stays selected; window pointer drops to null because the window
        // disappeared and the app is now windowless.
        assertEquals(1, refreshed.selectedAppPid)
        assertEquals(null, refreshed.selectedWindowId)
    }
}
