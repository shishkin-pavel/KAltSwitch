package com.shish.kaltswitch.model

import kotlin.test.Test
import kotlin.test.assertEquals

private fun event(pid: Pid, windowId: WindowId?) = ActivationEvent(pid, windowId)

class ActivationLogTest {

    @Test
    fun appOrder_isNewestFirst_dedupedByPid() {
        val log = ActivationLog()
            .record(event(pid = 10, windowId = 100))
            .record(event(pid = 20, windowId = 200))
            .record(event(pid = 10, windowId = 101))   // 10 bumps to front
        assertEquals(listOf(10, 20), log.appOrder)
    }

    @Test
    fun windowOrder_isNewestFirst_perPid_skipsAppLevelEvents() {
        val log = ActivationLog()
            .record(event(pid = 10, windowId = 100))
            .record(event(pid = 10, windowId = 101))
            .record(event(pid = 20, windowId = 200))
            .record(event(pid = 10, windowId = null))  // app-level, no window bump
            .record(event(pid = 10, windowId = 100))   // 100 bumps to front
        assertEquals(listOf(100L, 101L), log.windowOrder(pid = 10))
        assertEquals(listOf(200L), log.windowOrder(pid = 20))
        assertEquals(emptyList(), log.windowOrder(pid = 30))
    }

    @Test
    fun appLevelEvent_bumpsAppOrder_withoutTouchingWindowOrder() {
        // The whole point of separating the two indices: an app-level
        // event keeps the pid newest in app recency even though it adds
        // nothing to the per-window list. A windowless app that just got
        // focus must still sit at the top.
        val log = ActivationLog()
            .record(event(pid = 10, windowId = 100))
            .record(event(pid = 20, windowId = 200))   // 20 most recent
            .record(event(pid = 10, windowId = null))  // 10 bumps back via app-level
        assertEquals(listOf(10, 20), log.appOrder)
        // 10's window order is unchanged: only 100 was ever recorded for it.
        assertEquals(listOf(100L), log.windowOrder(pid = 10))
    }

    @Test
    fun recordWindow_bumpsWindowOrder_withoutTouchingAppOrder() {
        // Window-only counterpart of `record`: the user did something to a
        // window (cmd+M minimize/restore in the switcher) without
        // committing to "use this app". The per-app window order updates;
        // the app's place in appOrder stays put.
        val log = ActivationLog()
            .record(event(pid = 10, windowId = 100))
            .record(event(pid = 20, windowId = 200))   // 20 is most-recent app
            .recordWindow(pid = 10, windowId = 101)    // window-only bump for pid=10

        // 20 stays at head — pid=10 didn't promote even though we touched
        // its window order.
        assertEquals(listOf(20, 10), log.appOrder)
        // pid=10's per-window order: 101 newest, 100 second.
        assertEquals(listOf(101L, 100L), log.windowOrder(pid = 10))
        // pid=20 untouched.
        assertEquals(listOf(200L), log.windowOrder(pid = 20))
    }

    @Test
    fun recordWindow_seedsPerWindowOrder_evenForPidNotInAppOrder() {
        // recordWindow must not assume the pid is already in appOrder. A
        // window-state interaction can fire before the app has had any
        // attention-shift activation (e.g. an early cmd+M before any focus
        // change has been recorded). The per-window history is recorded
        // regardless; appOrder remains empty until a real activation lands.
        val log = ActivationLog().recordWindow(pid = 10, windowId = 100)
        assertEquals(emptyList(), log.appOrder)
        assertEquals(listOf(100L), log.windowOrder(pid = 10))
    }

    @Test
    fun record_idempotentAtHead_returnsStructurallyEqualLog() {
        // Adjacent duplicates (an AX echo of a commit we just recorded
        // synchronously) are no-ops at the head of each index.
        val first = ActivationLog().record(event(pid = 10, windowId = 100))
        val second = first.record(event(pid = 10, windowId = 100))
        assertEquals(first, second)
    }

    @Test
    fun withoutPid_removesPidFromBothIndices() {
        val log = ActivationLog()
            .record(event(pid = 1, windowId = 10))
            .record(event(pid = 2, windowId = 20))
            .record(event(pid = 1, windowId = 11))

        val pruned = log.withoutPid(1)
        assertEquals(listOf(2), pruned.appOrder)
        assertEquals(emptyList(), pruned.windowOrder(pid = 1))
        assertEquals(listOf(20L), pruned.windowOrder(pid = 2))
    }

    @Test
    fun withoutWindow_dropsOnlyTheTargetedWindow_keepsAppRecency() {
        val log = ActivationLog()
            .record(event(pid = 1, windowId = 10))
            .record(event(pid = 1, windowId = 11))   // 11 newest for pid=1
            .record(event(pid = 2, windowId = 20))   // 2 newest overall

        val pruned = log.withoutWindow(pid = 1, windowId = 11)
        // pid=1 still in app recency (closing a window doesn't make the user
        // less recently using the app).
        assertEquals(listOf(2, 1), pruned.appOrder)
        assertEquals(listOf(10L), pruned.windowOrder(pid = 1))
    }

    @Test
    fun withoutMissingWindows_keepsAppRecency_evenAfterAllWindowsGone() {
        // The bug this design fixes: a windowless app that was active a
        // moment ago must keep its place at the top of [appOrder].
        val log = ActivationLog()
            .record(event(pid = 1, windowId = 10))
            .record(event(pid = 1, windowId = 11))
            .record(event(pid = 2, windowId = 20))
            .record(event(pid = 1, windowId = 12))   // pid=1 newest

        // Snapshot says pid=1 has zero live windows (app went windowless).
        val pruned = log.withoutMissingWindows(pid = 1, liveWindowIds = emptySet())

        // pid=1 stays at the head of appOrder despite losing every window.
        assertEquals(listOf(1, 2), pruned.appOrder)
        assertEquals(emptyList(), pruned.windowOrder(pid = 1))
        assertEquals(listOf(20L), pruned.windowOrder(pid = 2))
    }

    @Test
    fun withoutMissingWindows_filtersDeadWindowIds() {
        val log = ActivationLog()
            .record(event(pid = 1, windowId = 10))
            .record(event(pid = 1, windowId = 11))
            .record(event(pid = 1, windowId = 12))

        // Snapshot reports only window 11 alive.
        val pruned = log.withoutMissingWindows(pid = 1, liveWindowIds = setOf(11L))
        assertEquals(listOf(11L), pruned.windowOrder(pid = 1))
        // appOrder untouched.
        assertEquals(listOf(1), pruned.appOrder)
    }
}
