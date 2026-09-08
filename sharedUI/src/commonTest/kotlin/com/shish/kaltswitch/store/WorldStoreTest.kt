package com.shish.kaltswitch.store

import com.shish.kaltswitch.config.AccentColorChoice
import com.shish.kaltswitch.config.AppConfig
import com.shish.kaltswitch.config.SwitcherSettings
import com.shish.kaltswitch.config.WindowFrame
import com.shish.kaltswitch.model.App
import com.shish.kaltswitch.model.AppActivationPolicy
import com.shish.kaltswitch.model.FilteringRules
import com.shish.kaltswitch.model.PinningRule
import com.shish.kaltswitch.model.PinningRules
import com.shish.kaltswitch.model.StringOp
import com.shish.kaltswitch.model.TitlePredicate
import com.shish.kaltswitch.model.Window
import com.shish.kaltswitch.model.WindowSource
import com.shish.kaltswitch.model.snapshot
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
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
        assertEquals(listOf(10), store.state.value.log.appOrder)
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
    fun clearActive_zeroesPointers_withoutTouchingLog() {
        val store = WorldStore()
        store.recordActivation(pid = 10, windowId = 100)
        store.recordActivation(pid = 20, windowId = 200)

        store.clearActive()
        assertNull(store.activeAppPid.value)
        assertNull(store.activeWindowId.value)
        // Log untouched.
        assertEquals(listOf(20, 10), store.state.value.log.appOrder)
    }

    @Test
    fun applyConfig_seedsEveryPersistedField() = runTest {
        val store = WorldStore()
        val cfg = AppConfig(
            schemaVersion = 5,
            filters = FilteringRules(),
            settingsWindowFrame = WindowFrame(x = 100.0, y = 200.0, width = 640.0, height = 540.0),
            inspectorWindowFrame = WindowFrame(x = 800.0, y = 100.0, width = 720.0, height = 600.0),
            switcher = SwitcherSettings(showDelayMs = 50L, previewEnabled = true),
            showMenubarIcon = false,
            launchAtLogin = true,
            currentSpaceOnly = true,
            accentColor = AccentColorChoice.UseSystem,
        )

        store.applyConfig(cfg)

        // Round-trip via configFlow: first emission is the current snapshot.
        val snapshot = store.configFlow().first()
        // schemaVersion isn't part of the round-trip (configFlow uses default).
        assertEquals(cfg.copy(schemaVersion = AppConfig().schemaVersion), snapshot)
    }

    @Test
    fun configFlow_reEmitsOnSinglePersistedFieldChange() = runTest {
        val store = WorldStore()
        val initial = store.configFlow().first()

        store.setLaunchAtLogin(true)
        val afterLaunchAtLogin = store.configFlow().first()
        assertEquals(initial.copy(launchAtLogin = true), afterLaunchAtLogin)

        store.saveSettingsWindowFrame(x = 10.0, y = 20.0, width = 640.0, height = 540.0)
        val afterFrame = store.configFlow().first()
        assertEquals(
            initial.copy(
                launchAtLogin = true,
                settingsWindowFrame = WindowFrame(10.0, 20.0, 640.0, 540.0),
            ),
            afterFrame,
        )
    }

    @Test
    fun removeApp_prunesActivationHistory_andClearsActivePointersIfThatPid() {
        val store = WorldStore()
        store.recordActivation(pid = 10, windowId = 100)
        store.recordActivation(pid = 20, windowId = 200)
        store.recordActivation(pid = 10, windowId = 101)

        store.removeApp(pid = 10)

        // History no longer mentions pid 10 — a future reused pid 10 starts fresh.
        assertEquals(listOf(20), store.state.value.log.appOrder)
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
        store.applyAxSnapshot(pid = 10, axWindows =listOf(window(id = 101, pid = 10)))

        // Window 100 is gone from history, 101 + the app-level event remain.
        assertEquals(listOf(101L), store.state.value.log.windowOrder(pid = 10))
        // App-level event preserved (windowId == null is not a window id).
        assertEquals(listOf(10), store.state.value.log.appOrder)
    }

    @Test
    fun setWindows_clearsActiveWindowIdIfThatWindowDisappeared() {
        val store = WorldStore()
        store.recordActivation(pid = 10, windowId = 100)
        store.applyAxSnapshot(pid = 10, axWindows =listOf(window(id = 100, pid = 10)))
        // Sanity: still pointing at 100.
        assertEquals(100L, store.activeWindowId.value)

        store.applyAxSnapshot(pid = 10, axWindows =listOf(window(id = 101, pid = 10)))

        assertEquals(10, store.activeAppPid.value)        // app pointer kept
        assertNull(store.activeWindowId.value)            // window pointer cleared
    }

    @Test
    fun setWindows_keepsActivationEventsForChildWindowIds() {
        val store = WorldStore()
        store.recordActivation(pid = 10, windowId = 200)  // a child / sheet
        store.recordActivation(pid = 10, windowId = 100)

        // Snapshot: window 100 has child id 200 attached.
        store.applyAxSnapshot(
            pid = 10,
            axWindows = listOf(
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
        store.applyAxSnapshot(pid = 10, axWindows =emptyList())
        store.setAppIconPng(pid = 10, png = byteArrayOf(1, 2, 3))

        store.removeApp(pid = 10)
        assertNull(store.state.value.runningApps[10])
        assertNull(store.state.value.windowsByPid[10])
        assertNull(store.iconsByPid.value[10])
    }

    /**
     * `upsertAppFields` takes the activation policy as a raw `NSInteger`
     * precisely so the macOS side never has to build an
     * `NSApplicationActivationPolicy` — the cinterop enum lookup throws on any
     * value outside {0, 1, 2}, and `NSRunningApplication` hands back an
     * out-of-range integer once its process has exited. Anything unrecognised
     * must therefore land on `Prohibited`, not blow up.
     */
    @Test
    fun upsertAppFields_mapsActivationPolicyRaw_andFoldsUnknownToProhibited() {
        val store = WorldStore()
        val seen = mutableMapOf<Long, AppActivationPolicy>()
        for (raw in listOf(0L, 1L, 2L, -1L, Long.MAX_VALUE)) {
            store.upsertAppFields(
                pid = 10,
                bundleId = "com.example",
                name = "Example",
                activationPolicyRaw = raw,
                isHidden = false,
                isFinishedLaunching = true,
                executablePath = null,
                launchDateMillis = 0,
            )
            seen[raw] = assertNotNull(store.state.value.runningApps[10]).activationPolicy
        }

        assertEquals(AppActivationPolicy.Regular, seen[0L])
        assertEquals(AppActivationPolicy.Accessory, seen[1L])
        assertEquals(AppActivationPolicy.Prohibited, seen[2L])
        assertEquals(AppActivationPolicy.Prohibited, seen[-1L])
        assertEquals(AppActivationPolicy.Prohibited, seen[Long.MAX_VALUE])
    }

    private fun window(
        id: Long,
        pid: Int,
        children: List<Window> = emptyList(),
    ): Window = Window(id = id, pid = pid, title = "", children = children)

    // ─────────────────────── Unified window storage ───────────────────────
    //
    // These tests pin the per-source membership rules from
    // `applyAxSnapshot` / `applyCgSnapshot` / `upsertAxWindow`. The
    // critical invariants:
    //  * A window is dropped iff its `sources` becomes empty.
    //  * Retracting one source preserves the other source's last-known
    //    fields on the window.
    //  * `applyAxSnapshot` ignores windows AX never claimed (CG-only
    //    cross-space rows aren't affected by AX losing visibility).

    @Test
    fun applyAxSnapshot_addsNewWindow_withAxInSources() {
        val store = WorldStore()
        store.applyAxSnapshot(
            pid = 1,
            axWindows = listOf(window(id = 11, pid = 1).copy(title = "Hi", cgWindowId = 101)),
        )
        val w = store.state.value.windowsByPid[1]?.single()
        assertNotNull(w)
        assertEquals("Hi", w.title)
        assertEquals(setOf(WindowSource.AX), w.sources)
    }

    @Test
    fun applyAxSnapshot_retract_dropsAxOnlyWindow() {
        // Window present with sources = {AX}, then AX-snapshot doesn't
        // mention it → window is removed entirely.
        val store = WorldStore()
        store.applyAxSnapshot(
            pid = 1,
            axWindows = listOf(window(id = 11, pid = 1).copy(cgWindowId = 101)),
        )
        store.applyAxSnapshot(pid = 1, axWindows = emptyList())
        assertNull(store.state.value.windowsByPid[1])
    }

    @Test
    fun applyAxSnapshot_retract_keepsWindowWhenCgStillHasIt_preservesAxFields() {
        // Seed AX + CG both knowing the same window (matched by cgWindowId).
        val store = WorldStore()
        store.applyAxSnapshot(
            pid = 1,
            axWindows = listOf(window(id = 11, pid = 1).copy(title = "Hello", cgWindowId = 101)),
        )
        store.applyCgSnapshot(
            allCgWindows = listOf(window(id = 0, pid = 1).copy(cgWindowId = 101, ownerName = "Mail")),
        )
        // Now AX retracts.
        store.applyAxSnapshot(pid = 1, axWindows = emptyList())
        val w = store.state.value.windowsByPid[1]?.single()
        assertNotNull(w)
        assertEquals(setOf(WindowSource.CG), w.sources)
        // AX-side fields are preserved on the survivor — title doesn't
        // collapse to "" just because AX lost visibility.
        assertEquals("Hello", w.title)
        // CG fields stay populated too.
        assertEquals("Mail", w.ownerName)
    }

    @Test
    fun applyCgSnapshot_retract_dropsCgOnlyWindow() {
        // CG-only (cross-space) window, then CG no longer sees it → drop.
        val store = WorldStore()
        store.applyCgSnapshot(
            allCgWindows = listOf(window(id = 0, pid = 1).copy(cgWindowId = 101)),
        )
        assertEquals(1, store.state.value.windowsByPid[1]?.size)
        store.applyCgSnapshot(allCgWindows = emptyList())
        assertNull(store.state.value.windowsByPid[1])
    }

    @Test
    fun applyCgSnapshot_retract_keepsWindowWhenAxStillHasIt_preservesCgFields() {
        // Both AX + CG saw the window; CG retracts.
        val store = WorldStore()
        store.applyAxSnapshot(
            pid = 1,
            axWindows = listOf(window(id = 11, pid = 1).copy(title = "Hi", cgWindowId = 101)),
        )
        store.applyCgSnapshot(
            allCgWindows = listOf(
                window(id = 0, pid = 1).copy(cgWindowId = 101, ownerName = "Mail", cgLayer = 0),
            ),
        )
        store.applyCgSnapshot(allCgWindows = emptyList())
        val w = store.state.value.windowsByPid[1]?.single()
        assertNotNull(w)
        assertEquals(setOf(WindowSource.AX), w.sources)
        assertEquals("Hi", w.title)
        // CG fields are preserved (last-known) — UI / rules can still
        // reason about the off-CG state even though CG itself doesn't
        // currently see the window.
        assertEquals("Mail", w.ownerName)
    }

    @Test
    fun applyAxSnapshot_doesNotDropCgOnlyWindow() {
        // CG-only cross-space window. AX-snapshot for this pid arrives
        // without it (AX is space-filtered, doesn't see it). Window
        // should NOT be dropped — AX has no claim over it.
        val store = WorldStore()
        store.applyCgSnapshot(
            allCgWindows = listOf(window(id = 0, pid = 1).copy(cgWindowId = 101)),
        )
        store.applyAxSnapshot(
            pid = 1,
            axWindows = listOf(window(id = 12, pid = 1).copy(cgWindowId = 102)),
        )
        val ws = store.state.value.windowsByPid[1].orEmpty()
        // Two windows: the CG-only 101 and the new AX 102.
        assertEquals(2, ws.size)
        val cgOnly = ws.single { it.cgWindowId == 101L }
        assertEquals(setOf(WindowSource.CG), cgOnly.sources)
    }

    @Test
    fun applyCgSnapshot_upgradesCgOnlyToBothSources() {
        // CG-only window first, then AX sees it (user switched to its
        // space) → sources becomes {AX, CG}.
        val store = WorldStore()
        store.applyCgSnapshot(
            allCgWindows = listOf(window(id = 0, pid = 1).copy(cgWindowId = 101, ownerName = "Mail")),
        )
        store.applyAxSnapshot(
            pid = 1,
            axWindows = listOf(window(id = 11, pid = 1).copy(title = "Inbox", cgWindowId = 101)),
        )
        val w = store.state.value.windowsByPid[1]?.single()
        assertNotNull(w)
        assertEquals(setOf(WindowSource.AX, WindowSource.CG), w.sources)
        assertEquals("Inbox", w.title)
        assertEquals("Mail", w.ownerName)
    }

    @Test
    fun upsertAxWindow_addsThenPatches_neverDrops() {
        val store = WorldStore()
        store.upsertAxWindow(window = window(id = 11, pid = 1).copy(title = "A", cgWindowId = 101))
        assertEquals(1, store.state.value.windowsByPid[1]?.size)

        // Same cgWindowId, new title → patch.
        store.upsertAxWindow(window = window(id = 11, pid = 1).copy(title = "A2", cgWindowId = 101))
        val w = store.state.value.windowsByPid[1]?.single()
        assertEquals("A2", w?.title)
        assertTrue(WindowSource.AX in (w?.sources ?: emptySet()))

        // Different cgWindowId → upsert adds, doesn't drop the existing.
        store.upsertAxWindow(window = window(id = 12, pid = 1).copy(title = "B", cgWindowId = 102))
        assertEquals(2, store.state.value.windowsByPid[1]?.size)
    }

    // ─────────────── Optimistic minimize / unminimize ───────────────

    @Test
    fun setWindowMinimizedOptimistic_axCgWindow_flipsBothFields() {
        // Window seen by both AX and CG (typical case for a visible
        // app's window). Optimistic minimize must flip isMinimized AND
        // anticipate the CG isOnscreen flip — otherwise the
        // `default-hide-cg-hidden-helper` rule's race window opens.
        val store = WorldStore()
        store.applyAxSnapshot(
            pid = 1,
            axWindows = listOf(window(id = 11, pid = 1).copy(cgWindowId = 101)),
        )
        store.applyCgSnapshot(
            allCgWindows = listOf(
                window(id = 0, pid = 1).copy(cgWindowId = 101, isOnscreen = true, ownerName = "App"),
            ),
        )
        store.setWindowMinimizedOptimistic(pid = 1, windowId = 11, minimized = true)
        val w = store.state.value.windowsByPid[1]?.single()
        assertNotNull(w)
        assertEquals(true, w.isMinimized)
        assertEquals(false, w.isOnscreen)

        // Un-minimize flips both back.
        store.setWindowMinimizedOptimistic(pid = 1, windowId = 11, minimized = false)
        val w2 = store.state.value.windowsByPid[1]?.single()
        assertEquals(false, w2?.isMinimized)
        assertEquals(true, w2?.isOnscreen)
    }

    @Test
    fun setWindowMinimizedOptimistic_axOnlyWindow_leavesOnscreenAlone() {
        // No CG twin → leave isOnscreen alone (it's null and the next
        // CG refresh would have nothing to compare against).
        val store = WorldStore()
        store.applyAxSnapshot(
            pid = 1,
            axWindows = listOf(window(id = 11, pid = 1).copy(cgWindowId = 101)),
        )
        store.setWindowMinimizedOptimistic(pid = 1, windowId = 11, minimized = true)
        val w = store.state.value.windowsByPid[1]?.single()
        assertEquals(true, w?.isMinimized)
        assertNull(w?.isOnscreen)
    }

    // ─────────────────────── Pinning anchor stickiness ───────────────────────
    //
    // Reproduces the user-reported bug where a freshly opened pinned window
    // (e.g. Firefox PiP opened from window A) re-pins to whichever sibling
    // happens to be most recently active. The fix stashes the anchor choice
    // in `World.pinAnchorByWindow` at window-creation time so subsequent
    // activations don't shuffle it.

    @Test
    fun pinningAnchor_isStickyAcrossActivationShuffle() {
        val store = WorldStore()
        store.upsertApp(
            App(
                pid = 10,
                bundleId = "org.mozilla.firefox",
                name = "Firefox",
            ),
        )
        store.setPinning(
            PinningRules(
                rules = listOf(
                    PinningRule(
                        id = "p-pip",
                        predicates = listOf(
                            TitlePredicate(
                                op = StringOp.Contains,
                                value = "Picture-in-Picture",
                            ),
                        ),
                    ),
                ),
            ),
        )
        val winA = Window(id = 100, pid = 10, title = "FF A", cgWindowId = 1000)
        val winB = Window(id = 101, pid = 10, title = "FF B", cgWindowId = 1001)
        val pip = Window(id = 102, pid = 10, title = "Twitch — Picture-in-Picture", cgWindowId = 1002)

        // 1. User has FF windows A and B; A is most recently active.
        store.applyAxSnapshot(pid = 10, axWindows = listOf(winA, winB))
        store.recordActivation(pid = 10, windowId = winA.id)

        // 2. PiP opens from A — AX delivers the new window list.
        store.applyAxSnapshot(pid = 10, axWindows = listOf(winA, winB, pip))

        // Anchor should be A (active just before pip arrived).
        val anchorAfterOpen = store.state.value.pinAnchorByWindow[10]?.get(pip.id)
        assertEquals(winA.id, anchorAfterOpen)

        // 3. User switches to B — A is no longer most recent. The bug
        //    used to make PiP "follow" the active window here.
        store.recordActivation(pid = 10, windowId = winB.id)

        // Sticky anchor must still be A, regardless of which root is newest.
        val anchorAfterShuffle = store.state.value.pinAnchorByWindow[10]?.get(pip.id)
        assertEquals(winA.id, anchorAfterShuffle)

        // And the snapshot honours the sticky choice — PiP under A, not B.
        val snap = store.state.value.snapshot(store.pinning.value)
        val ffEntry = snap.withWindows.single { it.app.pid == 10 }
        val aView = ffEntry.windows.single { it.id == winA.id }
        val bView = ffEntry.windows.single { it.id == winB.id }
        assertEquals(listOf(pip.id), aView.children.map { it.id })
        assertTrue(bView.children.isEmpty())
    }

    @Test
    fun pinningAnchor_droppedWhenWindowDies() {
        val store = WorldStore()
        store.upsertApp(
            App(pid = 10, bundleId = "org.mozilla.firefox", name = "Firefox"),
        )
        store.setPinning(
            PinningRules(
                rules = listOf(
                    PinningRule(
                        id = "p-pip",
                        predicates = listOf(
                            TitlePredicate(
                                op = StringOp.Contains,
                                value = "Picture-in-Picture",
                            ),
                        ),
                    ),
                ),
            ),
        )
        val winA = Window(id = 100, pid = 10, title = "FF A", cgWindowId = 1000)
        val pip = Window(id = 102, pid = 10, title = "Twitch — Picture-in-Picture", cgWindowId = 1002)
        store.applyAxSnapshot(pid = 10, axWindows = listOf(winA, pip))
        store.recordActivation(pid = 10, windowId = winA.id)
        assertEquals(winA.id, store.state.value.pinAnchorByWindow[10]?.get(pip.id))

        // PiP closes — its anchor entry must be evicted, so a future PiP
        // with the same id wouldn't accidentally inherit the old anchor.
        store.applyAxSnapshot(pid = 10, axWindows = listOf(winA))
        assertNull(store.state.value.pinAnchorByWindow[10]?.get(pip.id))
    }

    @Test
    fun pinningAnchor_clearedOnAppRemoval() {
        val store = WorldStore()
        store.upsertApp(
            App(pid = 10, bundleId = "org.mozilla.firefox", name = "Firefox"),
        )
        store.setPinning(
            PinningRules(
                rules = listOf(
                    PinningRule(
                        id = "p-pip",
                        predicates = listOf(
                            TitlePredicate(
                                op = StringOp.Contains,
                                value = "Picture-in-Picture",
                            ),
                        ),
                    ),
                ),
            ),
        )
        val winA = Window(id = 100, pid = 10, title = "FF A", cgWindowId = 1000)
        val pip = Window(id = 102, pid = 10, title = "Twitch — Picture-in-Picture", cgWindowId = 1002)
        store.applyAxSnapshot(pid = 10, axWindows = listOf(winA, pip))
        store.recordActivation(pid = 10, windowId = winA.id)
        assertEquals(winA.id, store.state.value.pinAnchorByWindow[10]?.get(pip.id))

        store.removeApp(pid = 10)
        // Pid is fully scrubbed from the anchor map alongside its other state.
        assertNull(store.state.value.pinAnchorByWindow[10])
    }

    // ─────────────── CG-resurrection tombstones ───────────────
    //
    // After a WindowServer-confirmed AX destruction, `CGWindowListCopyWindowInfo`
    // keeps listing the dead window for up to ~2 s. Without a gate the next
    // `applyCgSnapshot` re-adds it (the "add brand-new CG window" branch) and
    // the just-dropped row zombies back — the "Finder reappears then animates
    // away ~3 s after closing its last window" bug. `dropWindowsByCgWindowIds`
    // tombstones the cgwid; `applyCgSnapshot` refuses to re-add a tombstoned
    // id and self-clears the tombstone once CG stops listing it.

    @Test
    fun dropWindowsByCgWindowIds_tombstoneBlocksLaggingCgReAdd() {
        val store = WorldStore()
        // AX + CG both know the window.
        store.applyAxSnapshot(
            pid = 1,
            axWindows = listOf(window(id = 11, pid = 1).copy(cgWindowId = 101)),
        )
        store.applyCgSnapshot(
            allCgWindows = listOf(window(id = 0, pid = 1).copy(cgWindowId = 101)),
        )
        assertEquals(1, store.state.value.windowsByPid[1]?.size)

        // AX-destroy fast path: WindowServer-confirmed drop.
        store.dropWindowsByCgWindowIds(pid = 1, cgWindowIds = listOf(101))
        assertNull(store.state.value.windowsByPid[1])

        // CG poll still lists 101 (its view lags the destruction). Must NOT
        // resurrect the window.
        store.applyCgSnapshot(
            allCgWindows = listOf(window(id = 0, pid = 1).copy(cgWindowId = 101)),
        )
        assertNull(store.state.value.windowsByPid[1])

        // Still lagging a second poll — still suppressed.
        store.applyCgSnapshot(
            allCgWindows = listOf(window(id = 0, pid = 1).copy(cgWindowId = 101)),
        )
        assertNull(store.state.value.windowsByPid[1])
    }

    @Test
    fun cgTombstone_selfClears_onceCgStopsListingTheId() {
        val store = WorldStore()
        store.applyCgSnapshot(
            allCgWindows = listOf(window(id = 0, pid = 1).copy(cgWindowId = 101)),
        )
        store.dropWindowsByCgWindowIds(pid = 1, cgWindowIds = listOf(101))
        assertNull(store.state.value.windowsByPid[1])

        // CG finally drops 101 → tombstone self-clears.
        store.applyCgSnapshot(allCgWindows = emptyList())

        // A later window reusing cgwid 101 (WindowServer only reuses ids
        // slowly) is now allowed through again — the gate isn't permanent.
        store.applyCgSnapshot(
            allCgWindows = listOf(window(id = 0, pid = 1).copy(cgWindowId = 101)),
        )
        assertEquals(1, store.state.value.windowsByPid[1]?.size)
    }

    @Test
    fun setWindowMinimizedOptimistic_unknownId_isNoOp() {
        val store = WorldStore()
        store.applyAxSnapshot(
            pid = 1,
            axWindows = listOf(window(id = 11, pid = 1).copy(cgWindowId = 101)),
        )
        val before = store.state.value
        store.setWindowMinimizedOptimistic(pid = 1, windowId = 9999, minimized = true)
        assertEquals(before, store.state.value)
    }
}
