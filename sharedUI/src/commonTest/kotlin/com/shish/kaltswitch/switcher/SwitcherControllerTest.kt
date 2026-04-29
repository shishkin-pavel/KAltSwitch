package com.shish.kaltswitch.switcher

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
            .record(ActivationEvent(1, pid = 2, windowId = 22))
            .record(ActivationEvent(2, pid = 2, windowId = 21))
            .record(ActivationEvent(3, pid = 1, windowId = 12))
            .record(ActivationEvent(4, pid = 1, windowId = 11))  // Safari/11 most recent
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
        val ctl = SwitcherController(store, scope = backgroundScope, showDelayMs = 20, previewDelayMs = 250)

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
        val ctl = SwitcherController(store, scope = backgroundScope, showDelayMs = 20)
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
        val ctl = SwitcherController(store, scope = backgroundScope, showDelayMs = 20)
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
        val ctl = SwitcherController(store, scope = backgroundScope, showDelayMs = 20)
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
        val raises = mutableListOf<Pair<Int, Long>>()
        val ctl = SwitcherController(
            store, scope = backgroundScope,
            showDelayMs = 20, previewDelayMs = 100,
        ).also { it.onRaiseWindow = { pid, wid -> raises += pid to wid } }

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
        val raises = mutableListOf<Pair<Int, Long>>()
        val ctl = SwitcherController(
            store, scope = backgroundScope,
            showDelayMs = 20, previewDelayMs = 100,
        ).also { it.onRaiseWindow = { pid, wid -> raises += pid to wid } }

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
        val ctl = SwitcherController(store, scope = backgroundScope, showDelayMs = 20)

        ctl.onShortcut(SwitcherEntry.App)
        advanceTimeBy(20)
        // Default cursor: app=1.
        assertEquals(1, ctl.ui.value?.state?.cursor?.appIndex)

        ctl.onShortcut(SwitcherEntry.App)  // re-press: NextApp → wraps to 0
        assertEquals(0, ctl.ui.value?.state?.cursor?.appIndex)
    }

    @Test
    fun reverseShortcut_fromClosed_opensAndStepsBackOnce() = runTest {
        val store = seededStore()
        val ctl = SwitcherController(store, scope = backgroundScope, showDelayMs = 20)

        // Default cursor for App is index 1 (IDE). Reverse shortcut should step
        // back once → wraps to 0 (Safari).
        ctl.onShortcut(SwitcherEntry.App, reverse = true)
        assertEquals(0, ctl.ui.value?.state?.cursor?.appIndex)
    }

    @Test
    fun reverseShortcut_whileOpen_navigatesPrev() = runTest {
        val store = seededStore()
        val ctl = SwitcherController(store, scope = backgroundScope, showDelayMs = 20)

        ctl.onShortcut(SwitcherEntry.App)  // cursor app=1
        ctl.onShortcut(SwitcherEntry.App, reverse = true)  // PrevApp → 0
        assertEquals(0, ctl.ui.value?.state?.cursor?.appIndex)
    }

    @Test
    fun switcherActive_isTrueDuringSession_andClearsAfterCommit() = runTest {
        val store = seededStore()
        val ctl = SwitcherController(
            store, scope = backgroundScope,
            showDelayMs = 20, activeFlagDebounceMs = 50,
        )
        ctl.onShortcut(SwitcherEntry.App)
        assertEquals(true, store.switcherActive.value)
        advanceTimeBy(30)
        ctl.onModifierReleased()
        // Flag stays true through the debounce so AX echo from the commit is dropped.
        assertEquals(true, store.switcherActive.value)
        advanceTimeBy(60)
        assertEquals(false, store.switcherActive.value)
    }

    @Test
    fun store_recordEvent_isDroppedWhileSwitcherActive() = runTest {
        val store = seededStore()
        val ctl = SwitcherController(store, scope = backgroundScope, showDelayMs = 20)
        ctl.onShortcut(SwitcherEntry.App)

        val before = store.state.value.log.events.size
        store.recordEvent(ActivationEvent(timestampMs = 999, pid = 1, windowId = 11))
        assertEquals(before, store.state.value.log.events.size)
    }
}
