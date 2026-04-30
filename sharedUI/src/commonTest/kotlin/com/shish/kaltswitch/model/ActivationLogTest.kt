package com.shish.kaltswitch.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ActivationLogTest {

    @Test
    fun appOrder_isNewestFirst_dedupedByPid() {
        val log = ActivationLog()
            .record(ActivationEvent(pid = 10, windowId = 100))
            .record(ActivationEvent(pid = 20, windowId = 200))
            .record(ActivationEvent(pid = 10, windowId = 101))   // 10 bumps to front
        assertEquals(listOf(10, 20), log.appOrder())
    }

    @Test
    fun windowOrder_isNewestFirst_perPid_skipsAppLevelEvents() {
        val log = ActivationLog()
            .record(ActivationEvent(pid = 10, windowId = 100))
            .record(ActivationEvent(pid = 10, windowId = 101))
            .record(ActivationEvent(pid = 20, windowId = 200))
            .record(ActivationEvent(pid = 10, windowId = null))  // app-level, ignored for windows
            .record(ActivationEvent(pid = 10, windowId = 100))   // 100 bumps to front
        assertEquals(listOf(100L, 101L), log.windowOrder(pid = 10))
        assertEquals(listOf(200L), log.windowOrder(pid = 20))
        assertEquals(emptyList(), log.windowOrder(pid = 30))
    }

    @Test
    fun record_collapsesAdjacentDuplicateEchoes() {
        // AX/Workspace can echo the same activation we just recorded
        // synchronously in commit(). Adjacent duplicates would otherwise
        // double the head and waste a slot in the bounded log.
        val event = ActivationEvent(pid = 10, windowId = 100)
        val log = ActivationLog()
            .record(event)
            .record(event)
        assertEquals(listOf(event), log.events)
    }

    @Test
    fun record_isBoundedByMaxEvents() {
        val log = ActivationLog()
            .record(ActivationEvent(pid = 1, windowId = null), maxEvents = 3)
            .record(ActivationEvent(pid = 2, windowId = null), maxEvents = 3)
            .record(ActivationEvent(pid = 3, windowId = null), maxEvents = 3)
            .record(ActivationEvent(pid = 4, windowId = null), maxEvents = 3)
        assertEquals(listOf(4, 3, 2), log.events.map { it.pid })
    }

    @Test
    fun withoutPid_removesAllEventsForThatProcess() {
        val log = ActivationLog()
            .record(ActivationEvent(pid = 1, windowId = 10))
            .record(ActivationEvent(pid = 2, windowId = 20))
            .record(ActivationEvent(pid = 1, windowId = 11))

        assertEquals(
            listOf(ActivationEvent(pid = 2, windowId = 20)),
            log.withoutPid(1).events,
        )
    }

    @Test
    fun withoutWindow_removesOnlyMatchingWindowEvents() {
        // App-level events for the same pid are kept (windowId == null).
        val log = ActivationLog()
            .record(ActivationEvent(pid = 1, windowId = 10))
            .record(ActivationEvent(pid = 1, windowId = null))
            .record(ActivationEvent(pid = 2, windowId = 10))
            .record(ActivationEvent(pid = 1, windowId = 11))

        assertEquals(
            listOf(
                ActivationEvent(pid = 1, windowId = 11),
                ActivationEvent(pid = 2, windowId = 10),
                ActivationEvent(pid = 1, windowId = null),
            ),
            log.withoutWindow(pid = 1, windowId = 10).events,
        )
    }

    @Test
    fun withoutMissingWindows_keepsAppLevelAndLiveWindowEvents() {
        val log = ActivationLog()
            .record(ActivationEvent(pid = 1, windowId = 10))
            .record(ActivationEvent(pid = 1, windowId = null))
            .record(ActivationEvent(pid = 1, windowId = 11))
            .record(ActivationEvent(pid = 2, windowId = 10))

        assertEquals(
            listOf(
                ActivationEvent(pid = 2, windowId = 10),
                ActivationEvent(pid = 1, windowId = 11),
                ActivationEvent(pid = 1, windowId = null),
            ),
            log.withoutMissingWindows(pid = 1, liveWindowIds = setOf(11)).events,
        )
    }
}
