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
}
