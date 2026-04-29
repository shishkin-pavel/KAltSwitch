package com.shish.kaltswitch.switcher

import com.shish.kaltswitch.config.SwitcherSettings
import com.shish.kaltswitch.model.ActivationEvent
import com.shish.kaltswitch.model.ActivationLog
import com.shish.kaltswitch.model.App
import com.shish.kaltswitch.model.SwitcherEntry
import com.shish.kaltswitch.model.SwitcherEvent
import com.shish.kaltswitch.model.Window
import com.shish.kaltswitch.model.World
import com.shish.kaltswitch.store.WorldStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
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
    fun reverseShortcut_fromClosed_opensAndStepsBackOnce() = runTest {
        val store = seededStore()
        val ctl = SwitcherController(store, scope = backgroundScope)

        // Default cursor for App is index 1 (IDE). Reverse shortcut should step
        // back once → wraps to 0 (Safari).
        ctl.onShortcut(SwitcherEntry.App, reverse = true)
        assertEquals(0, ctl.ui.value?.state?.cursor?.appIndex)
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
}
