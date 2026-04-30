package com.shish.kaltswitch.switcher

import com.shish.kaltswitch.config.SwitcherSettings
import com.shish.kaltswitch.model.ActivationEvent
import com.shish.kaltswitch.model.ActivationLog
import com.shish.kaltswitch.model.App
import com.shish.kaltswitch.model.AppEntry
import com.shish.kaltswitch.model.FilteringRules
import com.shish.kaltswitch.model.NavScope
import com.shish.kaltswitch.model.NoVisibleWindowsPredicate
import com.shish.kaltswitch.model.Rule
import com.shish.kaltswitch.model.SwitcherEntry
import com.shish.kaltswitch.model.SwitcherEvent
import com.shish.kaltswitch.model.SwitcherCursor
import com.shish.kaltswitch.model.SwitcherSnapshot
import com.shish.kaltswitch.model.SwitcherState
import com.shish.kaltswitch.model.TriFilter
import com.shish.kaltswitch.model.Window
import com.shish.kaltswitch.model.World
import com.shish.kaltswitch.model.apply
import com.shish.kaltswitch.model.openSwitcher
import com.shish.kaltswitch.model.withCursor
import com.shish.kaltswitch.store.WorldStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class SwitcherControllerTest {

    private fun seededStore(): WorldStore {
        // Two apps, two windows each. Activation log makes Safari most-recent.
        val safari = App(pid = 1, bundleId = "safari", name = "Safari")
        val ide = App(pid = 2, bundleId = "ide", name = "IDE")
        val w1a = Window(id = 11, pid = 1, title = "Safari A")
        val w1b = Window(id = 12, pid = 1, title = "Safari B")
        val w2a = Window(id = 21, pid = 2, title = "IDE A")
        val w2b = Window(id = 22, pid = 2, title = "IDE B")
        val log = ActivationLog()
            .record(ActivationEvent(pid = 2, windowId = 22))
            .record(ActivationEvent(pid = 2, windowId = 21))
            .record(ActivationEvent(pid = 1, windowId = 12))
            .record(ActivationEvent(pid = 1, windowId = 11))  // Safari/11 most recent
        return WorldStore(
            World(
                log = log,
                runningApps = mapOf(1 to safari, 2 to ide),
                windowsByPid = mapOf(1 to listOf(w1a, w1b), 2 to listOf(w2a, w2b)),
            )
        )
    }

    @Test
    fun shortcut_pendingState_isInvisibleUntilShowDelay() = runTest {
        val store = seededStore()
        val ctl = SwitcherController(store, scope = backgroundScope)

        ctl.onShortcut(SwitcherEntry.App)
        // Pending: state exists but visible == false.
        val pending = ctl.ui.value
        assertNotNull(pending)
        assertEquals(false, pending.visible)
        assertEquals(true, store.switcherActive.value)

        advanceTimeBy(15)
        assertEquals(false, ctl.ui.value?.visible)

        advanceTimeBy(10)
        assertEquals(true, ctl.ui.value?.visible)
    }

    @Test
    fun earlyRelease_commitsCursor_withoutEverShowingUi() = runTest {
        val store = seededStore()
        val commits = mutableListOf<Pair<Int, Long?>>()
        val ctl = SwitcherController(store, scope = backgroundScope)
            .also { it.onCommitActivation = { pid, wid -> commits += pid to wid } }

        ctl.onShortcut(SwitcherEntry.App)
        advanceTimeBy(10)  // < showDelay
        ctl.onModifierReleased()
        advanceUntilIdle()

        assertNull(ctl.ui.value)
        // Default cursor for App entry is app[1] (second-most-recent). With Safari current,
        // app[1] is IDE. window[0] of IDE in newest-first order is IDE A (id 21).
        assertEquals(listOf<Pair<Int, Long?>>(2 to 21L), commits)
    }

    @Test
    fun lateRelease_aftersUiVisible_commitsSelection() = runTest {
        val store = seededStore()
        val commits = mutableListOf<Pair<Int, Long?>>()
        val ctl = SwitcherController(store, scope = backgroundScope)
            .also { it.onCommitActivation = { pid, wid -> commits += pid to wid } }

        ctl.onShortcut(SwitcherEntry.App)
        advanceTimeBy(50)  // > showDelay
        ctl.onModifierReleased()
        advanceUntilIdle()

        assertNull(ctl.ui.value)
        assertEquals(listOf<Pair<Int, Long?>>(2 to 21L), commits)
    }

    @Test
    fun esc_cancels_withoutCommit() = runTest {
        val store = seededStore()
        val commits = mutableListOf<Pair<Int, Long?>>()
        val ctl = SwitcherController(store, scope = backgroundScope)
            .also { it.onCommitActivation = { pid, wid -> commits += pid to wid } }

        ctl.onShortcut(SwitcherEntry.App)
        advanceTimeBy(50)
        ctl.onEsc()
        advanceUntilIdle()

        assertNull(ctl.ui.value)
        assertEquals(0, commits.size, "Esc must not activate anything")
    }

    @Test
    fun previewRaise_firesAfterPreviewDelay_onlyWhenVisible() = runTest {
        val store = seededStore()
        store.setSwitcherSettings(SwitcherSettings(showDelayMs = 20, previewDelayMs = 100, previewEnabled = true))
        val raises = mutableListOf<Pair<Int, Long>>()
        val ctl = SwitcherController(store, scope = backgroundScope)
            .also { it.onRaiseWindow = { pid, wid -> raises += pid to wid } }

        ctl.onShortcut(SwitcherEntry.App)
        advanceTimeBy(50)  // UI visible at t=20; preview will fire 100ms later
        assertEquals(0, raises.size)
        advanceTimeBy(80)
        // Default cursor: app=1 (IDE), window=0 (IDE A, id 21).
        assertEquals(listOf(2 to 21L), raises)
    }

    @Test
    fun navigation_resetsPreviewTimer() = runTest {
        val store = seededStore()
        store.setSwitcherSettings(SwitcherSettings(showDelayMs = 20, previewDelayMs = 100, previewEnabled = true))
        val raises = mutableListOf<Pair<Int, Long>>()
        val ctl = SwitcherController(store, scope = backgroundScope)
            .also { it.onRaiseWindow = { pid, wid -> raises += pid to wid } }

        ctl.onShortcut(SwitcherEntry.App)
        advanceTimeBy(20)  // UI now visible
        advanceTimeBy(50)  // 50ms into the 100ms preview window
        ctl.onNavigate(SwitcherEvent.NextApp)  // resets timer; cursor moves to app=0 (Safari)
        advanceTimeBy(50)  // 50ms into the new preview window — still no raise
        assertEquals(0, raises.size)
        advanceTimeBy(60)  // total 60ms after nav — fires
        // Safari's window[0] in recency order: id 11.
        assertEquals(listOf(1 to 11L), raises)
    }

    @Test
    fun rePress_advancesApp() = runTest {
        val store = seededStore()
        val ctl = SwitcherController(store, scope = backgroundScope)

        ctl.onShortcut(SwitcherEntry.App)
        advanceTimeBy(20)
        // Default cursor: app=1.
        assertEquals(1, ctl.ui.value?.state?.cursor?.appIndex)

        // Real re-press: user releases tab fully, then presses again. Without
        // the keyUp signal between, the second onShortcut would be treated
        // as OS auto-repeat of the held key and ignored.
        ctl.onShortcutKeyReleased()
        ctl.onShortcut(SwitcherEntry.App)  // NextApp → wraps to 0
        assertEquals(0, ctl.ui.value?.state?.cursor?.appIndex)
    }

    @Test
    fun reverseShortcut_fromClosed_landsOnLastInRecency() = runTest {
        val store = seededStore()
        val ctl = SwitcherController(store, scope = backgroundScope)

        // 2 apps. cmd+shift+tab from closed lands on the last in recency
        // (= app[size-1] = IDE). With only two apps that coincides with
        // cmd+tab's "next-most-recent" target — the 3-app test below
        // discriminates the two semantics.
        ctl.onShortcut(SwitcherEntry.App, reverse = true)
        assertEquals(1, ctl.ui.value?.state?.cursor?.appIndex)
    }

    @Test
    fun reverseShortcut_threeApps_landsOnLastNotPenultimate() = runTest {
        // Custom 3-app world to verify "land on last in recency" instead of
        // the old "step back from default cursor" (which would put cursor on
        // app[0] = current app, the bug this test guards against).
        val a1 = App(pid = 1, bundleId = "a1", name = "A1")
        val a2 = App(pid = 2, bundleId = "a2", name = "A2")
        val a3 = App(pid = 3, bundleId = "a3", name = "A3")
        val w1 = Window(id = 11, pid = 1, title = "A1 win")
        val w2 = Window(id = 21, pid = 2, title = "A2 win")
        val w3 = Window(id = 31, pid = 3, title = "A3 win")
        val log = ActivationLog()
            .record(ActivationEvent(pid = 3, windowId = 31))
            .record(ActivationEvent(pid = 2, windowId = 21))
            .record(ActivationEvent(pid = 1, windowId = 11))  // A1 most recent
        val store = WorldStore(World(
            log = log,
            runningApps = mapOf(1 to a1, 2 to a2, 3 to a3),
            windowsByPid = mapOf(1 to listOf(w1), 2 to listOf(w2), 3 to listOf(w3)),
        ))
        val ctl = SwitcherController(store, scope = backgroundScope)

        // Recency: A1 (current), A2, A3. cmd+shift+tab → A3 = app[2].
        ctl.onShortcut(SwitcherEntry.App, reverse = true)
        assertEquals(2, ctl.ui.value?.state?.cursor?.appIndex)
    }

    @Test
    fun reverseWindowShortcut_fromClosed_landsOnLastWindowOfCurrent() = runTest {
        // 3 windows in the current app to discriminate "default cursor's
        // window[1]" from "window[size-1]".
        val a1 = App(pid = 1, bundleId = "a1", name = "A1")
        val w1 = Window(id = 11, pid = 1, title = "win 1")
        val w2 = Window(id = 12, pid = 1, title = "win 2")
        val w3 = Window(id = 13, pid = 1, title = "win 3")
        val log = ActivationLog()
            .record(ActivationEvent(pid = 1, windowId = 13))
            .record(ActivationEvent(pid = 1, windowId = 12))
            .record(ActivationEvent(pid = 1, windowId = 11))  // win 11 most recent
        val store = WorldStore(World(
            log = log,
            runningApps = mapOf(1 to a1),
            windowsByPid = mapOf(1 to listOf(w1, w2, w3)),
        ))
        val ctl = SwitcherController(store, scope = backgroundScope)

        // cmd+shift+` from closed → app[0].window[size-1] = window[2] (= "win 3").
        ctl.onShortcut(SwitcherEntry.Window, reverse = true)
        assertEquals(0, ctl.ui.value?.state?.cursor?.appIndex)
        assertEquals(2, ctl.ui.value?.state?.cursor?.windowIndex)
    }

    @Test
    fun reverseShortcut_whileOpen_navigatesPrev() = runTest {
        val store = seededStore()
        val ctl = SwitcherController(store, scope = backgroundScope)

        ctl.onShortcut(SwitcherEntry.App)  // cursor app=1
        // Adding shift mid-hold counts as a different combo, so the second
        // onShortcut is a fresh press, not auto-repeat.
        ctl.onShortcut(SwitcherEntry.App, reverse = true)  // PrevApp → 0
        assertEquals(0, ctl.ui.value?.state?.cursor?.appIndex)
    }

    @Test
    fun switcherActive_isTrueDuringSession_clearsImmediatelyOnCommit() = runTest {
        val store = seededStore()
        val ctl = SwitcherController(store, scope = backgroundScope)
        ctl.onShortcut(SwitcherEntry.App)
        assertEquals(true, store.switcherActive.value)
        advanceTimeBy(30)
        ctl.onModifierReleased()
        // No debounce — flag clears synchronously so the commit's AX echo can
        // land in the log without being dropped.
        assertEquals(false, store.switcherActive.value)
    }

    @Test
    fun switcherActive_clearsImmediatelyOnEsc() = runTest {
        val store = seededStore()
        val ctl = SwitcherController(store, scope = backgroundScope)
        ctl.onShortcut(SwitcherEntry.App)
        advanceTimeBy(30)
        ctl.onEsc()
        assertEquals(false, store.switcherActive.value)
    }

    @Test
    fun pointAt_movesCursor_andCommitsOnClick() = runTest {
        val store = seededStore()
        val commits = mutableListOf<Pair<Int, Long?>>()
        val ctl = SwitcherController(store, scope = backgroundScope)
            .also { it.onCommitActivation = { pid, wid -> commits += pid to wid } }

        ctl.onShortcut(SwitcherEntry.App)  // default cursor: app=1 (IDE), win=0 (IDE A id 21)
        advanceTimeBy(30)
        // Simulate the user moving the mouse so the stationary-mouse gate
        // (see SwitcherController.mouseInteracted) doesn't swallow the
        // following hover events.
        ctl.onPointerMoved()

        // Hover over Safari (appIndex=0). No windowIndex hint → resets to 0
        // (most-recent Safari window = id 11).
        ctl.onPointAt(appIndex = 0)
        assertEquals(0, ctl.ui.value?.state?.cursor?.appIndex)
        assertEquals(0, ctl.ui.value?.state?.cursor?.windowIndex)

        // Hover over Safari's second window (Safari B id 12).
        ctl.onPointAt(appIndex = 0, windowIndex = 1)
        assertEquals(1, ctl.ui.value?.state?.cursor?.windowIndex)

        // Click commits without waiting for cmd-release.
        ctl.onCommit()
        advanceUntilIdle()
        assertNull(ctl.ui.value)
        assertEquals(listOf<Pair<Int, Long?>>(1 to 12L), commits)
    }

    @Test
    fun pointAt_sameApp_preservesWindowIndexWhenWindowIndexNull() = runTest {
        val store = seededStore()
        val ctl = SwitcherController(store, scope = backgroundScope)

        ctl.onShortcut(SwitcherEntry.App)  // app=1 (IDE), win=0
        advanceTimeBy(30)
        ctl.onPointerMoved()
        ctl.onPointAt(appIndex = 1, windowIndex = 1)  // IDE B id 22
        assertEquals(1, ctl.ui.value?.state?.cursor?.windowIndex)

        // Re-hover the same app cell as a whole — must NOT clobber win=1 back to 0.
        ctl.onPointAt(appIndex = 1, windowIndex = null)
        assertEquals(1, ctl.ui.value?.state?.cursor?.windowIndex)
    }

    @Test
    fun pointAt_outOfRange_isIgnored() = runTest {
        val store = seededStore()
        val ctl = SwitcherController(store, scope = backgroundScope)

        ctl.onShortcut(SwitcherEntry.App)
        ctl.onPointerMoved()
        val before = ctl.ui.value?.state?.cursor
        ctl.onPointAt(appIndex = 99)
        assertEquals(before, ctl.ui.value?.state?.cursor)
        ctl.onPointAt(appIndex = 0, windowIndex = 99)
        // App moves but windowIndex clamps to last available.
        assertEquals(0, ctl.ui.value?.state?.cursor?.appIndex)
        assertEquals(1, ctl.ui.value?.state?.cursor?.windowIndex)
    }

    @Test
    fun pointAt_beforeOpen_isIgnored() = runTest {
        val store = seededStore()
        val ctl = SwitcherController(store, scope = backgroundScope)
        ctl.onPointAt(appIndex = 0)
        assertNull(ctl.ui.value)
    }

    @Test
    fun pointAt_beforeFirstMouseMove_isIgnored() = runTest {
        // Regression: hover events that fire purely because the panel just
        // appeared under a stationary mouse must not move the cursor away
        // from the keyboard-selected default. Only honour onPointAt after
        // the platform layer reports a real pointer-Move event.
        val store = seededStore()
        val ctl = SwitcherController(store, scope = backgroundScope)
        ctl.onShortcut(SwitcherEntry.App)  // default cursor: app=1
        advanceTimeBy(30)

        ctl.onPointAt(appIndex = 0)  // hover-Enter from stationary mouse
        assertEquals(1, ctl.ui.value?.state?.cursor?.appIndex)

        ctl.onPointerMoved()
        ctl.onPointAt(appIndex = 0)
        assertEquals(0, ctl.ui.value?.state?.cursor?.appIndex)
    }

    @Test
    fun pointAt_gateResetsForEachSession() = runTest {
        val store = seededStore()
        val ctl = SwitcherController(store, scope = backgroundScope)

        // Session 1: arm and use the gate.
        ctl.onShortcut(SwitcherEntry.App)
        advanceTimeBy(30)
        ctl.onPointerMoved()
        ctl.onModifierReleased()  // commit → closeSession
        advanceUntilIdle()
        assertNull(ctl.ui.value)

        // Session 2 should re-arm the gate even though the previous one
        // ended in mouseInteracted=true.
        ctl.onShortcut(SwitcherEntry.App)
        advanceTimeBy(30)
        ctl.onPointAt(appIndex = 0)  // stationary-mouse hover, ignored
        assertEquals(1, ctl.ui.value?.state?.cursor?.appIndex)
    }

    @Test
    fun heldShortcut_doesNotAdvanceInsideInitialDelay() = runTest {
        val store = seededStore()
        store.setSwitcherSettings(SwitcherSettings(repeatInitialDelayMs = 400, repeatIntervalMs = 100))
        val ctl = SwitcherController(store, scope = backgroundScope)

        ctl.onShortcut(SwitcherEntry.App)  // advances once → cursor=1
        advanceTimeBy(20)
        assertEquals(1, ctl.ui.value?.state?.cursor?.appIndex)

        // Inside the initial delay window — no auto-advance yet.
        advanceTimeBy(350)
        assertEquals(1, ctl.ui.value?.state?.cursor?.appIndex)
    }

    @Test
    fun heldShortcut_autoAdvancesPastInitialDelay() = runTest {
        val store = seededStore()
        store.setSwitcherSettings(SwitcherSettings(repeatInitialDelayMs = 400, repeatIntervalMs = 100))
        val ctl = SwitcherController(store, scope = backgroundScope)

        ctl.onShortcut(SwitcherEntry.App)  // cursor=1 (IDE)
        advanceTimeBy(420)  // first auto-tick fires at t=400
        assertEquals(0, ctl.ui.value?.state?.cursor?.appIndex)  // wrapped Safari

        advanceTimeBy(100)  // next tick at t=500
        assertEquals(1, ctl.ui.value?.state?.cursor?.appIndex)  // back to IDE

        advanceTimeBy(100)  // tick at t=600
        assertEquals(0, ctl.ui.value?.state?.cursor?.appIndex)
    }

    @Test
    fun shortcutKeyReleased_stopsAutoAdvance() = runTest {
        val store = seededStore()
        store.setSwitcherSettings(SwitcherSettings(repeatInitialDelayMs = 200, repeatIntervalMs = 100))
        val ctl = SwitcherController(store, scope = backgroundScope)

        ctl.onShortcut(SwitcherEntry.App)
        advanceTimeBy(220)  // auto-advance fires once → cursor=0
        assertEquals(0, ctl.ui.value?.state?.cursor?.appIndex)

        ctl.onShortcutKeyReleased()
        advanceTimeBy(500)  // would have fired ~5 more times if still running
        // Cursor stayed put after release.
        assertEquals(0, ctl.ui.value?.state?.cursor?.appIndex)
    }

    @Test
    fun osAutoRepeat_isIgnoredWhileSameComboHeld() = runTest {
        val store = seededStore()
        store.setSwitcherSettings(SwitcherSettings(repeatInitialDelayMs = 1_000, repeatIntervalMs = 1_000))
        val ctl = SwitcherController(store, scope = backgroundScope)

        ctl.onShortcut(SwitcherEntry.App)  // cursor=1
        // OS keyboard auto-repeat fires the same hotkey at e.g. 30ms intervals.
        // Without keyUp between, those must NOT advance — initial delay is
        // far longer than the simulated repeat burst.
        repeat(10) { ctl.onShortcut(SwitcherEntry.App) }
        advanceTimeBy(50)
        assertEquals(1, ctl.ui.value?.state?.cursor?.appIndex)
    }

    @Test
    fun shiftAddedMidHold_isFreshPress() = runTest {
        val store = seededStore()
        val ctl = SwitcherController(store, scope = backgroundScope)

        ctl.onShortcut(SwitcherEntry.App)  // cursor=1
        // User adds shift while still holding tab → different combo, treat
        // as fresh press → step backwards.
        ctl.onShortcut(SwitcherEntry.App, reverse = true)
        assertEquals(0, ctl.ui.value?.state?.cursor?.appIndex)
    }

    @Test
    fun esc_clearsHeldShortcut_soNextPressAdvances() = runTest {
        val store = seededStore()
        val ctl = SwitcherController(store, scope = backgroundScope)

        ctl.onShortcut(SwitcherEntry.App)  // cursor=1
        advanceTimeBy(30)
        ctl.onEsc()  // closes session
        advanceUntilIdle()

        // Without resetting heldShortcut on close, the next press would be
        // mistaken for auto-repeat of the same combo and ignored.
        ctl.onShortcut(SwitcherEntry.App)
        assertEquals(1, ctl.ui.value?.state?.cursor?.appIndex)
    }

    @Test
    fun store_recordActivation_isDroppedWhileSwitcherActive() = runTest {
        val store = seededStore()
        val ctl = SwitcherController(store, scope = backgroundScope)
        ctl.onShortcut(SwitcherEntry.App)

        val before = store.state.value.log.events.size
        store.recordActivation(pid = 1, windowId = 11)
        assertEquals(before, store.state.value.log.events.size)
    }

    @Test
    fun store_recordActivation_updatesBothLogAndActivePointer() = runTest {
        val store = seededStore()
        // No active session — call is allowed.
        store.recordActivation(pid = 2, windowId = 22)
        assertEquals(2, store.activeAppPid.value)
        assertEquals(22L, store.activeWindowId.value)
        // App with pid=2 should be at the head of the order now.
        assertEquals(2, store.state.value.log.appOrder().first())
    }

    // ---- Live snapshot: structural changes during a session --------------

    @Test
    fun liveSnapshot_newWindowAppearsForOtherApp_doesNotMoveCursor() = runTest {
        val store = seededStore()
        val ctl = SwitcherController(store, scope = backgroundScope)

        ctl.onShortcut(SwitcherEntry.App)
        advanceTimeBy(50)
        // Default cursor: app=1 (IDE), window=0 (IDE A, id 21).
        val before = ctl.ui.value?.state
        assertEquals(2, before?.selectedAppPid)
        assertEquals(21L, before?.selectedWindowId)

        // Add a new window to Safari (NOT the selected app).
        store.upsertWindow(Window(id = 13, pid = 1, title = "Safari C"))
        runCurrent()

        val after = ctl.ui.value?.state
        // Cursor stays on IDE/21 by identity.
        assertEquals(2, after?.selectedAppPid)
        assertEquals(21L, after?.selectedWindowId)
    }

    @Test
    fun liveSnapshot_newWindowOnSelectedApp_doesNotMoveCursor() = runTest {
        val store = seededStore()
        val ctl = SwitcherController(store, scope = backgroundScope)

        ctl.onShortcut(SwitcherEntry.App)  // selects IDE/21
        advanceTimeBy(50)

        // IDE opens a new window. setWindows replaces the full list — keep
        // the existing windows + the new one at the head.
        store.setWindows(
            pid = 2,
            windows = listOf(
                Window(id = 23, pid = 2, title = "IDE C"),  // brand new
                Window(id = 21, pid = 2, title = "IDE A"),
                Window(id = 22, pid = 2, title = "IDE B"),
            ),
        )
        runCurrent()

        val after = ctl.ui.value?.state
        assertEquals(2, after?.selectedAppPid)
        assertEquals(21L, after?.selectedWindowId)
    }

    @Test
    fun liveSnapshot_selectedWindowCloses_cursorMovesToRightNeighbour() = runTest {
        val store = seededStore()
        val ctl = SwitcherController(store, scope = backgroundScope)

        ctl.onShortcut(SwitcherEntry.App)  // selects IDE/21
        advanceTimeBy(50)
        assertEquals(21L, ctl.ui.value?.state?.selectedWindowId)

        // IDE/21 disappears. Right-neighbour in the old window order was
        // IDE/22, which is still alive → cursor moves there.
        store.setWindows(
            pid = 2,
            windows = listOf(Window(id = 22, pid = 2, title = "IDE B")),
        )
        // `runCurrent()` runs everything scheduled at the current virtual time
        // — that's what flushes the collector's pending resume after the
        // MutableStateFlow update. `advanceUntilIdle()` alone isn't enough
        // because no delay() is pending; the StateFlow emission posts as an
        // immediate continuation that needs the current tick to drain.
        runCurrent()

        val after = ctl.ui.value?.state
        assertEquals(2, after?.selectedAppPid)
        assertEquals(22L, after?.selectedWindowId)
    }

    @Test
    fun liveSnapshot_selectedAppTerminates_cursorJumpsToOldRightNeighbour() = runTest {
        // Three apps so we can test "right neighbour" rather than "first".
        val safari = App(pid = 1, bundleId = "safari", name = "Safari")
        val ide = App(pid = 2, bundleId = "ide", name = "IDE")
        val finder = App(pid = 3, bundleId = "finder", name = "Finder")
        val w1 = Window(id = 11, pid = 1, title = "Safari A")
        val w2 = Window(id = 21, pid = 2, title = "IDE A")
        val w3 = Window(id = 31, pid = 3, title = "Finder A")
        val log = ActivationLog()
            .record(ActivationEvent(pid = 3, windowId = 31))
            .record(ActivationEvent(pid = 2, windowId = 21))
            .record(ActivationEvent(pid = 1, windowId = 11))  // Safari most-recent
        val store = WorldStore(World(
            log = log,
            runningApps = mapOf(1 to safari, 2 to ide, 3 to finder),
            windowsByPid = mapOf(1 to listOf(w1), 2 to listOf(w2), 3 to listOf(w3)),
        ))
        val ctl = SwitcherController(store, scope = backgroundScope)

        ctl.onShortcut(SwitcherEntry.App)  // default cursor app=1 (IDE)
        advanceTimeBy(50)
        assertEquals(2, ctl.ui.value?.state?.selectedAppPid)

        // IDE quits. Old order was [Safari, IDE, Finder]; right-neighbour of
        // IDE in old order was Finder (pid 3) → cursor should land there.
        store.removeApp(pid = 2)
        runCurrent()

        val after = ctl.ui.value?.state
        assertEquals(3, after?.selectedAppPid)
        assertEquals(31L, after?.selectedWindowId)
    }

    @Test
    fun liveSnapshot_nonSelectedWindowCloses_cursorUnaffected() = runTest {
        val store = seededStore()
        val ctl = SwitcherController(store, scope = backgroundScope)

        ctl.onShortcut(SwitcherEntry.App)  // selects IDE/21
        advanceTimeBy(50)

        // Close a Safari window (different app, different window).
        store.setWindows(pid = 1, windows = listOf(Window(id = 11, pid = 1, title = "Safari A")))
        runCurrent()

        val after = ctl.ui.value?.state
        assertEquals(2, after?.selectedAppPid)
        assertEquals(21L, after?.selectedWindowId)
    }

    // ---- NavScope: Shown vs All ------------------------------------------

    /** Snapshot with two Show apps and one Demote app, single window each. */
    private fun snapshot2Show1Demote(): SwitcherSnapshot {
        val a = App(pid = 1, bundleId = "a", name = "A")
        val b = App(pid = 2, bundleId = "b", name = "B")
        val c = App(pid = 3, bundleId = "c", name = "C-demoted")
        val wa = Window(id = 11, pid = 1, title = "wa")
        val wb = Window(id = 22, pid = 2, title = "wb")
        val wc = Window(id = 33, pid = 3, title = "wc")
        return SwitcherSnapshot(
            withWindows = listOf(AppEntry(a, listOf(wa)), AppEntry(b, listOf(wb))),
            windowless = listOf(AppEntry(c, listOf(wc))),
        )
    }

    @Test
    fun apply_nextApp_shownScope_skipsDemote() {
        val s = snapshot2Show1Demote()  // Show: [A, B], Demote: [C]; size=3, shownAppCount=2
        var state = openSwitcher(s, SwitcherEntry.App).withCursor(SwitcherCursor(0, 0))
        // NextApp Shown from index 0 → 1 (still in Show).
        state = state.apply(SwitcherEvent.NextApp, NavScope.Shown)
        assertEquals(1, state.cursor.appIndex)
        // From the last Show (1), Shown wraps back to 0 — does NOT step into C (index 2).
        state = state.apply(SwitcherEvent.NextApp, NavScope.Shown)
        assertEquals(0, state.cursor.appIndex)
    }

    @Test
    fun apply_nextApp_allScope_traversesDemote() {
        val s = snapshot2Show1Demote()
        var state = openSwitcher(s, SwitcherEntry.App).withCursor(SwitcherCursor(0, 0))
        // All scope wraps over [0, 2].
        state = state.apply(SwitcherEvent.NextApp, NavScope.All)
        assertEquals(1, state.cursor.appIndex)
        state = state.apply(SwitcherEvent.NextApp, NavScope.All)
        assertEquals(2, state.cursor.appIndex)  // demoted C
        state = state.apply(SwitcherEvent.NextApp, NavScope.All)
        assertEquals(0, state.cursor.appIndex)
    }

    @Test
    fun apply_shownScope_fromOutsideRange_snapsBack() {
        val s = snapshot2Show1Demote()
        // Cursor is parked on the demoted app (index 2). User just came in via
        // arrow keys; now they hit cmd+tab → Shown scope.
        var state = openSwitcher(s, SwitcherEntry.App).withCursor(SwitcherCursor(2, 0))
        state = state.apply(SwitcherEvent.NextApp, NavScope.Shown)
        // Snap to the start of the Show range.
        assertEquals(0, state.cursor.appIndex)
    }

    @Test
    fun apply_window_shownScope_skipsDemoteWindows() {
        // Single app with 2 Show windows + 1 Demote window in a fixed order.
        val app = App(pid = 1, bundleId = "a", name = "A")
        val w1 = Window(id = 1, pid = 1, title = "show 1")
        val w2 = Window(id = 2, pid = 1, title = "show 2")
        val w3 = Window(id = 3, pid = 1, title = "demote 3")
        val entry = AppEntry(app, windows = listOf(w1, w2, w3), shownWindowCount = 2)
        val snapshot = SwitcherSnapshot(withWindows = listOf(entry), windowless = emptyList())
        var state = openSwitcher(snapshot, SwitcherEntry.Window).withCursor(SwitcherCursor(0, 0))
        // NextWindow Shown wraps within [0, 1].
        state = state.apply(SwitcherEvent.NextWindow, NavScope.Shown)
        assertEquals(1, state.cursor.windowIndex)
        state = state.apply(SwitcherEvent.NextWindow, NavScope.Shown)
        assertEquals(0, state.cursor.windowIndex)
        // NextWindow All reaches index 2.
        state = state.apply(SwitcherEvent.NextWindow, NavScope.All)
        state = state.apply(SwitcherEvent.NextWindow, NavScope.All)
        assertEquals(2, state.cursor.windowIndex)
    }

    @Test
    fun reverseShortcut_fromClosed_landsOnLastShownNotDemoted() = runTest {
        // 3 apps; an explicit rule demotes a3's phantom (it's windowless).
        // cmd+shift+tab from closed must land on app[1] (last Show), not
        // app[2] (Demote).
        val a1 = App(pid = 1, bundleId = "a1", name = "A1")
        val a2 = App(pid = 2, bundleId = "a2", name = "A2")
        val a3 = App(pid = 3, bundleId = "a3", name = "A3")
        val w1 = Window(id = 11, pid = 1, title = "A1 win")
        val w2 = Window(id = 21, pid = 2, title = "A2 win")
        val log = ActivationLog()
            .record(ActivationEvent(pid = 3, windowId = null))
            .record(ActivationEvent(pid = 2, windowId = 21))
            .record(ActivationEvent(pid = 1, windowId = 11))
        val store = WorldStore(World(
            log = log,
            runningApps = mapOf(1 to a1, 2 to a2, 3 to a3),
            windowsByPid = mapOf(1 to listOf(w1), 2 to listOf(w2), 3 to emptyList()),
        ))
        // Explicit rule recreates the old "windowless apps go to Demote"
        // fallback so the test scenario stays meaningful.
        store.setFilters(FilteringRules(rules = listOf(
            Rule(
                id = "windowless-demote",
                predicates = listOf(NoVisibleWindowsPredicate()),
                outcome = TriFilter.Demote,
            ),
        )))
        val ctl = SwitcherController(store, scope = backgroundScope)

        ctl.onShortcut(SwitcherEntry.App, reverse = true)
        // Should be 1 (last Show), not 2 (the demoted A3).
        assertEquals(1, ctl.ui.value?.state?.cursor?.appIndex)
    }
}
