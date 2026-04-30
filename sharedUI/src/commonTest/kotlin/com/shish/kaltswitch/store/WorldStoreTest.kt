package com.shish.kaltswitch.store

import com.shish.kaltswitch.config.AccentColorChoice
import com.shish.kaltswitch.config.AppConfig
import com.shish.kaltswitch.config.SwitcherSettings
import com.shish.kaltswitch.config.WindowFrame
import com.shish.kaltswitch.model.FilteringRules
import com.shish.kaltswitch.model.Window
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
    fun applyConfig_seedsEveryPersistedField() = runTest {
        val store = WorldStore()
        val cfg = AppConfig(
            schemaVersion = 3,
            filters = FilteringRules(),
            windowFrame = WindowFrame(x = 100.0, y = 200.0, width = 480.0, height = 720.0),
            inspectorWidth = 555.0,
            switcher = SwitcherSettings(showDelayMs = 50L, previewEnabled = true),
            inspectorVisible = false,
            showMenubarIcon = false,
            launchAtLogin = true,
            currentSpaceOnly = true,
            accentColor = AccentColorChoice.UseSystem,
        )

        store.applyConfig(cfg)

        // Round-trip via configFlow: first emission is the current snapshot.
        val snapshot = store.configFlow().first()
        // schemaVersion isn't part of the round-trip (configFlow uses default).
        assertEquals(cfg.copy(schemaVersion = 3), snapshot)
    }

    @Test
    fun configFlow_reEmitsOnSinglePersistedFieldChange() = runTest {
        val store = WorldStore()
        val initial = store.configFlow().first()

        store.setLaunchAtLogin(true)
        val afterLaunchAtLogin = store.configFlow().first()
        assertEquals(initial.copy(launchAtLogin = true), afterLaunchAtLogin)

        store.setInspectorWidth(640.0)
        val afterWidth = store.configFlow().first()
        assertEquals(initial.copy(launchAtLogin = true, inspectorWidth = 640.0), afterWidth)
    }

    @Test
    fun removeApp_prunesActivationHistory_andClearsActivePointersIfThatPid() {
        val store = WorldStore()
        store.recordActivation(pid = 10, windowId = 100)
        store.recordActivation(pid = 20, windowId = 200)
        store.recordActivation(pid = 10, windowId = 101)

        store.removeApp(pid = 10)

        // History no longer mentions pid 10 — a future reused pid 10 starts fresh.
        assertEquals(listOf(20), store.state.value.log.appOrder())
        assertEquals(emptyList(), store.state.value.log.windowOrder(pid = 10))
        // Active pointers were pointing at the removed pid → cleared.
        assertNull(store.activeAppPid.value)
        assertNull(store.activeWindowId.value)
    }

    @Test
    fun setWindows_prunesActivationLog_forDisappearedWindows() {
        val store = WorldStore()
        store.recordActivation(pid = 10, windowId = 100)
        store.recordActivation(pid = 10, windowId = 101)
        store.recordActivation(pid = 10, windowId = null)  // app-level

        // Snapshot reports only window 101 alive.
        store.setWindows(pid = 10, windows = listOf(window(id = 101, pid = 10)))

        // Window 100 is gone from history, 101 + the app-level event remain.
        assertEquals(listOf(101L), store.state.value.log.windowOrder(pid = 10))
        // App-level event preserved (windowId == null is not a window id).
        assertEquals(listOf(10), store.state.value.log.appOrder())
    }

    @Test
    fun setWindows_clearsActiveWindowIdIfThatWindowDisappeared() {
        val store = WorldStore()
        store.recordActivation(pid = 10, windowId = 100)
        store.setWindows(pid = 10, windows = listOf(window(id = 100, pid = 10)))
        // Sanity: still pointing at 100.
        assertEquals(100L, store.activeWindowId.value)

        store.setWindows(pid = 10, windows = listOf(window(id = 101, pid = 10)))

        assertEquals(10, store.activeAppPid.value)        // app pointer kept
        assertNull(store.activeWindowId.value)            // window pointer cleared
    }

    @Test
    fun setWindows_keepsActivationEventsForChildWindowIds() {
        val store = WorldStore()
        store.recordActivation(pid = 10, windowId = 200)  // a child / sheet
        store.recordActivation(pid = 10, windowId = 100)

        // Snapshot: window 100 has child id 200 attached.
        store.setWindows(
            pid = 10,
            windows = listOf(
                window(id = 100, pid = 10, children = listOf(window(id = 200, pid = 10))),
            ),
        )

        assertEquals(listOf(100L, 200L), store.state.value.log.windowOrder(pid = 10))
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

    private fun window(
        id: Long,
        pid: Int,
        children: List<Window> = emptyList(),
    ): Window = Window(id = id, pid = pid, title = "", children = children)
}
