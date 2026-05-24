package com.shish.kaltswitch.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests that the pinning pass — applied inside `World.snapshot()` — re-parents
 * matching top-level windows under the same app's most recently activated
 * non-pinned root, using the activation log's per-pid window order.
 */
class PinningSnapshotTest {

    private val ff = App(pid = 10, bundleId = "org.mozilla.firefox", name = "Firefox")

    private val main = Window(id = 100, pid = 10, title = "Main")
    private val second = Window(id = 200, pid = 10, title = "Secondary")
    private val pip = Window(id = 300, pid = 10, title = "Twitch — Picture-in-Picture")

    private val pipRule = PinningRules(
        rules = listOf(
            PinningRule(
                id = "p-1",
                predicates = listOf(TitlePredicate(op = StringOp.Contains, value = "Picture-in-Picture")),
            ),
        ),
    )

    private fun worldWith(vararg windows: Window, order: List<Long>): World {
        var log = ActivationLog()
        // Apply oldest-first so the newest event lands at the front of the list.
        for (wid in order.reversed()) log = log.record(ActivationEvent(ff.pid, wid))
        return World(
            log = log,
            runningApps = mapOf(ff.pid to ff),
            windowsByPid = mapOf(ff.pid to windows.toList()),
        )
    }

    @Test
    fun matchingWindowReparentedUnderLastActiveRoot() {
        // Most recent: main, second, pip. Pip matches → re-parent under
        // the newest non-pip root, which is `main`.
        val world = worldWith(main, second, pip, order = listOf(main.id, second.id, pip.id))
        val snap = world.snapshot(pipRule)
        val ffEntry = snap.withWindows.single { it.app.pid == ff.pid }
        // Roots: main + second (pip dropped from the top level).
        assertEquals(listOf(main.id, second.id), ffEntry.windows.map { it.id })
        // `main` has gained `pip` as a child.
        val mainView = ffEntry.windows.single { it.id == main.id }
        assertEquals(listOf(pip.id), mainView.children.map { it.id })
        // `second` is unchanged.
        val secondView = ffEntry.windows.single { it.id == second.id }
        assertTrue(secondView.children.isEmpty())
    }

    @Test
    fun pinAnchorIsMostRecentNonMatchingRoot_notTheVeryRecent() {
        // Activation order: pip (newest), main, second. The pip itself is
        // newest in the log but it's matching, so the anchor must skip it
        // and pick `main`.
        val world = worldWith(main, second, pip, order = listOf(pip.id, main.id, second.id))
        val snap = world.snapshot(pipRule)
        val ffEntry = snap.withWindows.single { it.app.pid == ff.pid }
        val mainView = ffEntry.windows.single { it.id == main.id }
        assertEquals(listOf(pip.id), mainView.children.map { it.id })
    }

    @Test
    fun multipleMatchingSiblings_allUnderSameAnchor() {
        val pip2 = pip.copy(id = 400, title = "YouTube — Picture-in-Picture")
        val world = worldWith(main, pip, pip2, order = listOf(main.id, pip.id, pip2.id))
        val snap = world.snapshot(pipRule)
        val ffEntry = snap.withWindows.single { it.app.pid == ff.pid }
        // Roots reduce to just `main`.
        assertEquals(listOf(main.id), ffEntry.windows.map { it.id })
        // Both pips ended up under `main`.
        val mainView = ffEntry.windows.single { it.id == main.id }
        assertEquals(listOf(pip.id, pip2.id), mainView.children.map { it.id })
    }

    @Test
    fun allRootsMatch_butNoActivationLog_keepsAsRoots() {
        // Edge: every root matches and there's no activation history. We'd
        // hide everything under nothing, so the pass leaves the roots as-is.
        val world = World(
            log = ActivationLog(),
            runningApps = mapOf(ff.pid to ff),
            windowsByPid = mapOf(ff.pid to listOf(pip)),
        )
        val snap = world.snapshot(pipRule)
        val ffEntry = snap.withWindows.single { it.app.pid == ff.pid }
        assertEquals(listOf(pip.id), ffEntry.windows.map { it.id })
        assertTrue(ffEntry.windows.single().children.isEmpty())
    }

    @Test
    fun emptyRulesList_isNoOp() {
        val world = worldWith(main, pip, order = listOf(main.id, pip.id))
        val snap = world.snapshot(PinningRules())
        val ffEntry = snap.withWindows.single { it.app.pid == ff.pid }
        assertEquals(listOf(main.id, pip.id), ffEntry.windows.map { it.id })
    }

    @Test
    fun preservesExistingChildren_ofAnchor() {
        val sheet = Window(id = 500, pid = 10, title = "Find")
        val mainWithSheet = main.copy(children = listOf(sheet))
        val world = worldWith(mainWithSheet, pip, order = listOf(mainWithSheet.id, pip.id))
        val snap = world.snapshot(pipRule)
        val ffEntry = snap.withWindows.single { it.app.pid == ff.pid }
        val mainView = ffEntry.windows.single { it.id == main.id }
        // Existing sheet first, then the pinned pip appended.
        assertEquals(listOf(sheet.id, pip.id), mainView.children.map { it.id })
    }

    @Test
    fun demotedPinnedChild_isOutOfShownScope_butReachableViaArrows() {
        // The actual case from the user report: demote rule for PiP + pin
        // rule for PiP under main. cmd+` (Shown scope) must NOT land on
        // the demoted child; arrows (All scope) must reach it.
        val world = worldWith(main, pip, order = listOf(main.id, pip.id))
        val filters = FilteringRules(
            rules = listOf(
                Rule(
                    id = "demote-pip",
                    predicates = listOf(TitlePredicate(op = StringOp.Contains, value = "Picture-in-Picture")),
                    outcome = TriFilter.Demote,
                ),
            ),
        )
        val snap = world.filteredSwitcherSnapshot(filters = filters, pinning = pipRule)
        val ffEntry = snap.all.single { it.app.pid == ff.pid }
        // Tree DFS: main first, then its pinned PiP child.
        assertEquals(listOf(main.id, pip.id), ffEntry.navigableWindows.map { it.id })
        // Shown scope skips the Demote subtree entirely.
        assertEquals(listOf(main.id), ffEntry.shownNavigableWindows.map { it.id })
        assertEquals(1, ffEntry.shownWindowCount)

        // Cursor on FF, main. Shown-scope NextWindow stays on main.
        val state = openSwitcher(snap, SwitcherEntry.App)
            .copy(selectedAppPid = ff.pid, selectedWindowId = main.id)
        val shown = state.apply(SwitcherEvent.NextWindow, NavScope.Shown)
        assertEquals(main.id, shown.selectedWindowId)
        // All-scope NextWindow steps onto the demoted PiP.
        val all = state.apply(SwitcherEvent.NextWindow, NavScope.All)
        assertEquals(pip.id, all.selectedWindowId)
    }

    @Test
    fun demotedPinnedChild_marksItsIdInDemotedSet() {
        // Pinning re-parents the PiP window under main; the filter rule
        // separately demotes it. The switcher snapshot must surface that
        // demote classification per-window so the overlay can tint the
        // child row — not infer demote from the parent's bucket.
        val world = worldWith(main, pip, order = listOf(main.id, pip.id))
        val filters = FilteringRules(
            rules = listOf(
                Rule(
                    id = "demote-pip",
                    predicates = listOf(TitlePredicate(op = StringOp.Contains, value = "Picture-in-Picture")),
                    outcome = TriFilter.Demote,
                ),
            ),
        )
        val snap = world.filteredSwitcherSnapshot(filters = filters, pinning = pipRule)
        val ffEntry = snap.all.single { it.app.pid == ff.pid }
        assertTrue(pip.id in ffEntry.demotedWindowIds, "pinned PiP child must be in demotedWindowIds")
        assertTrue(main.id !in ffEntry.demotedWindowIds, "main is Show; must not be in demotedWindowIds")
        // Top-level partition still puts main in the Show bucket.
        assertEquals(1, ffEntry.shownTopWindowCount)
    }

    @Test
    fun pinnedChildClassifiedAndKeptUnderParent_inSwitcherSnapshot() {
        // The user requirement: a pinned (now-child) window that the filter
        // rules demote must still be rendered under its parent, just at the
        // end of the children list (after non-demoted children).
        val sibling = Window(id = 600, pid = 10, title = "Inbox", role = "AXWindow")
        val mainWithSibling = main.copy(children = listOf(sibling))
        val world = worldWith(mainWithSibling, pip, order = listOf(mainWithSibling.id, pip.id))
        val filters = FilteringRules(
            rules = listOf(
                Rule(
                    id = "demote-pip",
                    predicates = listOf(TitlePredicate(op = StringOp.Contains, value = "Picture-in-Picture")),
                    outcome = TriFilter.Demote,
                ),
            ),
        )
        val snap = world.filteredSwitcherSnapshot(filters = filters, pinning = pipRule)
        val ffEntry = snap.all.single { it.app.pid == ff.pid }
        val mainOut = ffEntry.windows.single { it.id == main.id }
        // Show child first, demoted pinned child last.
        assertEquals(listOf(sibling.id, pip.id), mainOut.children.map { it.id })
    }

    @Test
    fun navigation_walksIntoPinnedChildren() {
        // FF has main + secondary as Show roots, with `pip` pinned under
        // `main`. cmd+`/arrows from main should visit pip before secondary.
        // Explicit empty rules — the default `SeedRules` would otherwise
        // demote Firefox PiP via `default-demote-ff-pip`, which we test
        // separately in `demotedPinnedChild_isOutOfShownScope_*`.
        val world = worldWith(main, second, pip, order = listOf(main.id, second.id, pip.id))
        val noRules = FilteringRules(rules = emptyList())
        val snap = world.filteredSwitcherSnapshot(filters = noRules, pinning = pipRule)
        val state = openSwitcher(snap, SwitcherEntry.App)
        val ffEntry = snap.all.single { it.app.pid == ff.pid }
        // navigableWindows = main, pip (child of main), second.
        assertEquals(listOf(main.id, pip.id, second.id), ffEntry.navigableWindows.map { it.id })

        // Place the cursor on FF, main window. Then walk forward.
        val onMain = state.copy(selectedAppPid = ff.pid, selectedWindowId = main.id)
        val s1 = onMain.apply(SwitcherEvent.NextWindow, NavScope.All)
        assertEquals(pip.id, s1.selectedWindowId)
        val s2 = s1.apply(SwitcherEvent.NextWindow, NavScope.All)
        assertEquals(second.id, s2.selectedWindowId)
        // Wrap to main.
        val s3 = s2.apply(SwitcherEvent.NextWindow, NavScope.All)
        assertEquals(main.id, s3.selectedWindowId)
    }

    @Test
    fun shownScope_includesPinnedChildrenOfShowRoots() {
        // With an empty rule set (override the default SeedRules so the
        // PiP isn't demoted), all roots are Show. shownWindowCount must
        // count the pinned child too, so cmd+` reaches it.
        val world = worldWith(main, pip, order = listOf(main.id, pip.id))
        val noRules = FilteringRules(rules = emptyList())
        val snap = world.filteredSwitcherSnapshot(filters = noRules, pinning = pipRule)
        val ffEntry = snap.all.single { it.app.pid == ff.pid }
        // 1 Show root (main) with 1 kept child (pip) → 2 navigable Shown entries.
        assertEquals(2, ffEntry.shownWindowCount)
        // But the visual top-level partition still sees just one Show root.
        assertEquals(1, ffEntry.shownTopWindowCount)
    }

    @Test
    fun storedAnchorWins_overCurrentRecency() {
        // The bug scenario: PiP was pinned under `main` when it opened.
        // Later the user activates `second`, which would normally make
        // `second` the most recent non-matching root. The stored anchor
        // must override that — PiP stays under `main`.
        val world = World(
            log = ActivationLog()
                .record(ActivationEvent(ff.pid, main.id))
                .record(ActivationEvent(ff.pid, pip.id))
                .record(ActivationEvent(ff.pid, second.id)),   // newest
            runningApps = mapOf(ff.pid to ff),
            windowsByPid = mapOf(ff.pid to listOf(main, second, pip)),
            pinAnchorByWindow = mapOf(ff.pid to mapOf(pip.id to main.id)),
        )
        val snap = world.snapshot(pipRule)
        val ffEntry = snap.withWindows.single { it.app.pid == ff.pid }
        // Recency would put `second` first (it's the most recent non-pip),
        // but PiP must stay under `main` because the stored anchor says so.
        val mainView = ffEntry.windows.single { it.id == main.id }
        assertEquals(listOf(pip.id), mainView.children.map { it.id })
        val secondView = ffEntry.windows.single { it.id == second.id }
        assertTrue(secondView.children.isEmpty())
    }

    @Test
    fun staleStoredAnchor_fallsBackToRecency() {
        // Stored anchor points at a window that no longer exists. Fallback
        // kicks in (most recent non-matching root by current recency).
        val world = World(
            log = ActivationLog()
                .record(ActivationEvent(ff.pid, second.id))
                .record(ActivationEvent(ff.pid, pip.id)),
            runningApps = mapOf(ff.pid to ff),
            // `main` is no longer a root.
            windowsByPid = mapOf(ff.pid to listOf(second, pip)),
            pinAnchorByWindow = mapOf(ff.pid to mapOf(pip.id to main.id)),
        )
        val snap = world.snapshot(pipRule)
        val ffEntry = snap.withWindows.single { it.app.pid == ff.pid }
        val secondView = ffEntry.windows.single { it.id == second.id }
        // Without a valid stored anchor, pip falls back under `second`.
        assertEquals(listOf(pip.id), secondView.children.map { it.id })
    }

    @Test
    fun differentSiblings_canHaveDifferentStoredAnchors() {
        // Two PiPs, each opened from a different FF window. The map
        // remembers per-window anchors and applyPinning splits them.
        val pip2 = pip.copy(id = 400, title = "YouTube — Picture-in-Picture")
        val world = World(
            log = ActivationLog()
                .record(ActivationEvent(ff.pid, second.id))
                .record(ActivationEvent(ff.pid, main.id)),
            runningApps = mapOf(ff.pid to ff),
            windowsByPid = mapOf(ff.pid to listOf(main, second, pip, pip2)),
            pinAnchorByWindow = mapOf(ff.pid to mapOf(pip.id to main.id, pip2.id to second.id)),
        )
        val snap = world.snapshot(pipRule)
        val ffEntry = snap.withWindows.single { it.app.pid == ff.pid }
        val mainView = ffEntry.windows.single { it.id == main.id }
        val secondView = ffEntry.windows.single { it.id == second.id }
        assertEquals(listOf(pip.id), mainView.children.map { it.id })
        assertEquals(listOf(pip2.id), secondView.children.map { it.id })
    }

    @Test
    fun rootOrder_followsFreshestPinnedDescendant() {
        // Two FF windows, each with its own pinned PiP child. Only the
        // children ever get activated. The parent whose child is most
        // recent must appear first in the top-level order even though the
        // parents themselves never reach `windowOrder`.
        val pip2 = pip.copy(id = 400, title = "YouTube — Picture-in-Picture")
        val world = World(
            log = ActivationLog()
                // Newest activation = pip2 (child of `second`).
                .record(ActivationEvent(ff.pid, pip.id))
                .record(ActivationEvent(ff.pid, pip2.id)),
            runningApps = mapOf(ff.pid to ff),
            windowsByPid = mapOf(ff.pid to listOf(main, second, pip, pip2)),
            pinAnchorByWindow = mapOf(ff.pid to mapOf(pip.id to main.id, pip2.id to second.id)),
        )
        val snap = world.snapshot(pipRule)
        val ffEntry = snap.withWindows.single { it.app.pid == ff.pid }
        // `second` ranks above `main` because pip2 (its child) is the
        // most recently activated window in the subtree.
        assertEquals(listOf(second.id, main.id), ffEntry.windows.map { it.id })

        // Swap which child is newest → top-level order flips accordingly.
        val world2 = world.copy(
            log = ActivationLog()
                .record(ActivationEvent(ff.pid, pip2.id))
                .record(ActivationEvent(ff.pid, pip.id)),
        )
        val snap2 = world2.snapshot(pipRule)
        val ffEntry2 = snap2.withWindows.single { it.app.pid == ff.pid }
        assertEquals(listOf(main.id, second.id), ffEntry2.windows.map { it.id })
    }

    @Test
    fun rootOrder_unchangedWhenNoSubtreeRecency() {
        // Two roots with pinned children but no per-window activations at
        // all. The sort key ties (all Int.MAX_VALUE), so the stable sort
        // preserves the input order coming out of `orderedWindows` — which
        // is AX-enumeration order: main, then second.
        val pip2 = pip.copy(id = 400, title = "YouTube — Picture-in-Picture")
        val world = World(
            log = ActivationLog(),  // no recency at all
            runningApps = mapOf(ff.pid to ff),
            windowsByPid = mapOf(ff.pid to listOf(main, second, pip, pip2)),
            pinAnchorByWindow = mapOf(ff.pid to mapOf(pip.id to main.id, pip2.id to second.id)),
        )
        val snap = world.snapshot(pipRule)
        val ffEntry = snap.withWindows.single { it.app.pid == ff.pid }
        assertEquals(listOf(main.id, second.id), ffEntry.windows.map { it.id })
    }

    @Test
    fun hideChildrenDropped_inSwitcherSnapshot() {
        // Filter rule sets a child to Hide → must be dropped from the switcher.
        val secretSheet = Window(id = 700, pid = 10, title = "Secret", role = "AXSheet")
        val visibleSheet = Window(id = 800, pid = 10, title = "Settings", role = "AXSheet")
        val mainWithSheets = main.copy(children = listOf(secretSheet, visibleSheet))
        val world = World(
            log = ActivationLog().record(ActivationEvent(ff.pid, mainWithSheets.id)),
            runningApps = mapOf(ff.pid to ff),
            windowsByPid = mapOf(ff.pid to listOf(mainWithSheets)),
        )
        val filters = FilteringRules(
            rules = listOf(
                Rule(
                    id = "hide-secret",
                    predicates = listOf(TitlePredicate(op = StringOp.Eq, value = "Secret")),
                    outcome = TriFilter.Hide,
                ),
            ),
        )
        val snap = world.filteredSwitcherSnapshot(filters = filters)
        val ffEntry = snap.all.single { it.app.pid == ff.pid }
        val mainOut = ffEntry.windows.single { it.id == main.id }
        // secretSheet dropped, visibleSheet kept.
        assertEquals(listOf(visibleSheet.id), mainOut.children.map { it.id })
    }
}
