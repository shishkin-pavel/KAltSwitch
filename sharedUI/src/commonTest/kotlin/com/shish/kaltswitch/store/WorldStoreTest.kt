package com.shish.kaltswitch.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for the activation-gating contract that the AX/Workspace observers
 * (Swift) and the SwitcherController commit path (Kotlin) both rely on.
 *
 * The contract is:
 *  - `recordActivation` is the **only** path that mutates the activation
 *    log; it also updates `activeAppPid` / `activeWindowId` *atomically*
 *    in the same call. UI invariants depend on the inspector's row order
 *    (driven by the log) never disagreeing with the active-row highlight
 *    (driven by the pointers).
 *  - While `switcherActive == true`, `recordActivation` drops events
 *    silently — they describe our own preview-raise / commit AX echo,
 *    not user intent.
 *  - `clearActive` zeros the pointers but leaves the log untouched.
 *
 * Each test is small and one-shot; together they pin the contract so
 * subsequent refactors can't silently weaken it.
 */
class WorldStoreTest {

    @Test
    fun recordActivation_updatesLogAndActivePointers_atomically() {
        val store = WorldStore()
        store.recordActivation(pid = 10, windowId = 100)
        assertEquals(listOf(10), store.state.value.log.appOrder())
        assertEquals(10, store.activeAppPid.value)
        assertEquals(100L, store.activeWindowId.value)
    }

    @Test
    fun recordActivation_appLevelEvent_clearsWindowPointer() {
        val store = WorldStore()
        store.recordActivation(pid = 10, windowId = 100)
        store.recordActivation(pid = 10, windowId = null)  // app-level
        assertEquals(10, store.activeAppPid.value)
        assertNull(store.activeWindowId.value)
        // The window-level event 100 is still in the log; the app-level event
        // sits on top but doesn't shadow per-window history.
        assertEquals(listOf(100L), store.state.value.log.windowOrder(pid = 10))
    }

    @Test
    fun recordActivation_isDroppedWhileSwitcherActive() {
        val store = WorldStore()
        store.recordActivation(pid = 10, windowId = 100)

        store.setSwitcherActive(true)
        store.recordActivation(pid = 20, windowId = 200)  // dropped
        store.recordActivation(pid = 30, windowId = null) // dropped
        assertEquals(listOf(10), store.state.value.log.appOrder())
        assertEquals(10, store.activeAppPid.value)
        assertEquals(100L, store.activeWindowId.value)

        // Once the gate clears, events flow again.
        store.setSwitcherActive(false)
        store.recordActivation(pid = 20, windowId = 200)
        assertEquals(listOf(20, 10), store.state.value.log.appOrder())
        assertEquals(20, store.activeAppPid.value)
        assertEquals(200L, store.activeWindowId.value)
    }

    @Test
    fun clearActive_zeroesPointers_withoutTouchingLog() {
        val store = WorldStore()
        store.recordActivation(pid = 10, windowId = 100)
        store.recordActivation(pid = 20, windowId = 200)

        store.clearActive()
        assertNull(store.activeAppPid.value)
        assertNull(store.activeWindowId.value)
        // Log untouched.
        assertEquals(listOf(20, 10), store.state.value.log.appOrder())
    }

    @Test
    fun removeApp_dropsWindows_andIcon() {
        val store = WorldStore()
        store.upsertAppFields(
            pid = 10,
            bundleId = "com.example",
            name = "Example",
            activationPolicyRaw = 0,
            isHidden = false,
            isFinishedLaunching = true,
            executablePath = null,
            launchDateMillis = 0,
        )
        store.setWindows(pid = 10, windows = emptyList())
        store.setAppIconPng(pid = 10, png = byteArrayOf(1, 2, 3))

        store.removeApp(pid = 10)
        assertNull(store.state.value.runningApps[10])
        assertNull(store.state.value.windowsByPid[10])
        assertNull(store.iconsByPid.value[10])
    }
}
