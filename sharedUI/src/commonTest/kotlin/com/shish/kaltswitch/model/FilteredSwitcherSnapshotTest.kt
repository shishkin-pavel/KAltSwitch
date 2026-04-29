package com.shish.kaltswitch.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests of the unified rule pipeline (`FilteringRules`). The classifier:
 * - walks rules per real window (first-match-wins, default Show);
 * - then derives the app's section from those modes — any Show → Show,
 *   any Demote → Demote;
 * - otherwise synthesises a phantom window and walks the rules again with
 *   `isPhantom = true` (so the `NoVisibleWindows` predicate becomes
 *   meaningful), defaulting to Hide if no rule matches.
 *
 * There are no separate fallback toggles; everything goes through rules.
 */
class FilteredSwitcherSnapshotTest {

    private val regularA = App(pid = 10, bundleId = "a", name = "Regular A")
    private val regularB = App(pid = 20, bundleId = "b", name = "Regular B")
    private val windowless = App(pid = 30, bundleId = "wless", name = "Windowless")
    private val accessory = App(
        pid = 40, bundleId = "acc", name = "Accessory",
        activationPolicy = AppActivationPolicy.Accessory,
    )
    private val winA = Window(id = 100, pid = 10, title = "A1")
    private val winB = Window(id = 200, pid = 20, title = "B1")
    private val winAcc = Window(id = 400, pid = 40, title = "Acc1")

    private fun world(): World {
        val log = ActivationLog()
            .record(ActivationEvent(accessory.pid, winAcc.id))
            .record(ActivationEvent(regularB.pid, winB.id))
            .record(ActivationEvent(regularA.pid, winA.id))
        return World(
            log = log,
            runningApps = listOf(regularA, regularB, windowless, accessory).associateBy { it.pid },
            windowsByPid = mapOf(
                regularA.pid to listOf(winA),
                regularB.pid to listOf(winB),
                windowless.pid to emptyList(),
                accessory.pid to listOf(winAcc),
            ),
        )
    }

    @Test
    fun emptyRules_everythingShows_includingWindowlessApps() {
        // No rules → real windows default Show, phantom default Show too.
        // Nothing gets demoted or hidden without explicit user rules.
        val snap = world().filteredSwitcherSnapshot(FilteringRules(rules = emptyList()))
        val primary = snap.withWindows.map { it.app.name }
        val secondary = snap.windowless.map { it.app.name }
        // All four apps in primary; windowless one is alphabetically last
        // because raw snapshot lists windowed apps by recency, then puts
        // known-windowless apps at the end.
        assertEquals(setOf("Regular A", "Regular B", "Accessory", "Windowless"), primary.toSet())
        assertTrue(secondary.isEmpty())
    }

    @Test
    fun noVisibleWindowsRule_demotesWindowlessApps() {
        // Replicates the old `windowlessApps = Demote` fallback as a rule.
        val rules = FilteringRules(
            rules = listOf(
                Rule(
                    id = "windowless",
                    predicates = listOf(NoVisibleWindowsPredicate()),
                    outcome = TriFilter.Demote,
                ),
            ),
        )
        val snap = world().filteredSwitcherSnapshot(rules)
        // Regular A, Regular B, Accessory keep windows; only Windowless demotes.
        assertEquals(listOf("Regular A", "Regular B", "Accessory"), snap.withWindows.map { it.app.name })
        assertEquals(listOf("Windowless"), snap.windowless.map { it.app.name })
    }

    @Test
    fun accessoryHideRule_appliesToBothRealWindowsAndPhantom() {
        // "accessory → Hide" hides both an accessory app's real window
        // (sending the app to phantom evaluation) AND the phantom (matching
        // again), so the app lands in Hide.
        val rules = FilteringRules(
            rules = listOf(
                Rule(
                    id = "no-accessory",
                    predicates = listOf(ActivationPolicyPredicate(value = PolicyValue.Accessory)),
                    outcome = TriFilter.Hide,
                ),
            ),
        )
        val snap = world().filteredSwitcherSnapshot(rules)
        val all = snap.all.map { it.app.name }
        assertFalse("Accessory" in all, "Accessory app must be hidden")
    }

    @Test
    fun explicitShowBeatsLaterAccessoryHide() {
        // The user's classic example: a more specific rule earlier wins
        // over a broader catch-all later, even if both target the same app.
        val rules = FilteringRules(
            rules = listOf(
                Rule(
                    id = "specific-show",
                    predicates = listOf(
                        ActivationPolicyPredicate(value = PolicyValue.Accessory),
                        RolePredicate(op = StringOp.IsEmpty, inverted = true),
                    ),
                    outcome = TriFilter.Show,
                ),
                Rule(
                    id = "broad-hide",
                    predicates = listOf(ActivationPolicyPredicate(value = PolicyValue.Accessory)),
                    outcome = TriFilter.Hide,
                ),
            ),
        )
        // Give the accessory window a real role so the inverted-IsEmpty
        // predicate matches.
        val world = world()
        val withRole = world.copy(
            windowsByPid = world.windowsByPid + (accessory.pid to listOf(winAcc.copy(role = "AXWindow"))),
        )
        val snap = withRole.filteredSwitcherSnapshot(rules)
        assertTrue(
            "Accessory" in snap.withWindows.map { it.app.name },
            "Specific Show rule must win over later broad Hide rule",
        )
    }

    @Test
    fun perWindowRule_hidesUntitledWindowsViaIsEmptyTitle() {
        val rules = FilteringRules(
            rules = listOf(
                Rule(
                    id = "untitled",
                    predicates = listOf(TitlePredicate(op = StringOp.IsEmpty)),
                    outcome = TriFilter.Hide,
                ),
            ),
        )
        val world = world()
        val withUntitled = world.copy(
            windowsByPid = world.windowsByPid + (regularA.pid to listOf(winA, Window(id = 999, pid = 10, title = ""))),
        )
        val snap = withUntitled.filteredSwitcherSnapshot(rules)
        val a = snap.withWindows.first { it.app.pid == regularA.pid }
        assertEquals(listOf(100L), a.windows.map { it.id })
    }

    @Test
    fun firstMatchWins_secondRuleDoesNotOverride() {
        val rules = FilteringRules(
            rules = listOf(
                Rule(
                    id = "demote-a1",
                    predicates = listOf(TitlePredicate(op = StringOp.Eq, value = "A1")),
                    outcome = TriFilter.Demote,
                ),
                Rule(
                    id = "hide-a1",
                    predicates = listOf(TitlePredicate(op = StringOp.Eq, value = "A1")),
                    outcome = TriFilter.Hide,
                ),
            ),
        )
        val snap = world().filteredSnapshot(rules)
        val a = snap.demote.first { it.app.pid == regularA.pid }
        val winView = a.windows.first { it.window.id == 100L }
        assertEquals(TriFilter.Demote, winView.mode)
    }

    @Test
    fun rulePredicatesAreANDed_bothMustMatch() {
        // bundleId == "a" AND title == "A1" → Hide. Window B1 (different
        // bundleId) must not be hidden. App A's only real window is hidden,
        // but the phantom doesn't match the rule (phantom title is "" not
        // "A1") → phantom default Show → app A still in Show, just with
        // no visible windows.
        val rules = FilteringRules(
            rules = listOf(
                Rule(
                    id = "rule",
                    predicates = listOf(
                        BundleIdPredicate(value = "a"),
                        TitlePredicate(value = "A1"),
                    ),
                    outcome = TriFilter.Hide,
                ),
            ),
        )
        val snap = world().filteredSnapshot(rules)
        val a = snap.show.firstOrNull { it.app.pid == regularA.pid }
        val b = snap.show.firstOrNull { it.app.pid == regularB.pid }
        assertTrue(a != null, "App A's window was hidden but phantom default Show keeps the app visible")
        assertTrue(b != null, "App B doesn't match; should still be in Show")
    }

    @Test
    fun nullStringFieldsAreNormalisedToEmpty() {
        // bundleId == "" should match an app whose bundleId is null.
        val noBundle = App(pid = 50, bundleId = null, name = "NoBundle")
        val noBundleWin = Window(id = 500, pid = 50, title = "X")
        val rules = FilteringRules(
            rules = listOf(
                Rule(
                    id = "null-bundle",
                    predicates = listOf(BundleIdPredicate(op = StringOp.IsEmpty)),
                    outcome = TriFilter.Hide,
                ),
            ),
        )
        val log = ActivationLog().record(ActivationEvent(noBundle.pid, noBundleWin.id))
        val world = World(
            log = log,
            runningApps = mapOf(noBundle.pid to noBundle),
            windowsByPid = mapOf(noBundle.pid to listOf(noBundleWin)),
        )
        val snap = world.filteredSnapshot(rules)
        // Window classified Hide → phantom evaluated → bundleId IsEmpty
        // also matches phantom → phantom Hide → app Hide.
        assertEquals(0, snap.show.size)
        assertEquals(1, snap.hide.size)
        assertEquals("NoBundle", snap.hide.single().app.name)
    }

    @Test
    fun invertedPredicate_negatesResult() {
        // "title is non-empty → Hide" should hide titled windows but keep
        // untitled ones.
        val rules = FilteringRules(
            rules = listOf(
                Rule(
                    id = "hide-titled",
                    predicates = listOf(TitlePredicate(op = StringOp.IsEmpty, inverted = true)),
                    outcome = TriFilter.Hide,
                ),
            ),
        )
        val world = world()
        val untitled = Window(id = 999, pid = 10, title = "")
        val withUntitled = world.copy(
            windowsByPid = world.windowsByPid + (regularA.pid to listOf(winA, untitled)),
        )
        val snap = withUntitled.filteredSnapshot(rules)
        val a = snap.show.first { it.app.pid == regularA.pid }
        assertEquals(listOf(999L), a.windows.filter { it.mode != TriFilter.Hide }.map { it.window.id })
    }

    @Test
    fun disabledRule_isSkipped() {
        val rules = FilteringRules(
            rules = listOf(
                Rule(
                    id = "off",
                    enabled = false,
                    predicates = listOf(NoVisibleWindowsPredicate()),
                    outcome = TriFilter.Hide,
                ),
            ),
        )
        val snap = world().filteredSnapshot(rules)
        // Disabled rule doesn't fire → phantom default Show. Everything in Show.
        assertEquals(4, snap.show.size)
        assertTrue(snap.demote.isEmpty())
        assertTrue(snap.hide.isEmpty())
    }

    @Test
    fun emptyRule_isInert() {
        // A rule with no enabled predicates must not match anything.
        val rules = FilteringRules(
            rules = listOf(
                Rule(id = "empty", predicates = emptyList(), outcome = TriFilter.Hide),
            ),
        )
        val snap = world().filteredSnapshot(rules)
        // Inert rule → phantom default Show kicks in. Everything visible.
        assertEquals(4, snap.show.size)
        assertTrue(snap.demote.isEmpty())
        assertTrue(snap.hide.isEmpty())
    }

    @Test
    fun phantomDoesNotMatchWindowSpecificPredicates() {
        // Rule "title == 'A1' → Hide" matches winA but the phantom's title
        // is "" so the rule doesn't match it → phantom default Show.
        val rules = FilteringRules(
            rules = listOf(
                Rule(
                    id = "title-hide",
                    predicates = listOf(TitlePredicate(value = "A1")),
                    outcome = TriFilter.Hide,
                ),
            ),
        )
        val snap = world().filteredSnapshot(rules)
        // Windowless app's phantom doesn't match the title rule → default Show.
        assertTrue(snap.show.any { it.app.pid == windowless.pid })
    }

    @Test
    fun seedRules_areAppliedWhenNoExplicitRulesGiven() {
        // Default constructor seeds [SeedRules]. Smoke-test that the seed
        // makes it through to the classifier — an FF window with
        // "Picture-in-Picture" in its title should be Demoted.
        val ff = App(pid = 60, bundleId = "org.mozilla.firefox", name = "Firefox")
        val pipWin = Window(id = 600, pid = 60, title = "YouTube — Picture-in-Picture")
        val log = ActivationLog().record(ActivationEvent(ff.pid, pipWin.id))
        val world = World(
            log = log,
            runningApps = mapOf(ff.pid to ff),
            windowsByPid = mapOf(ff.pid to listOf(pipWin)),
        )
        val snap = world.filteredSnapshot(FilteringRules())
        assertEquals(1, snap.demote.size)
        assertEquals("Firefox", snap.demote.single().app.name)
    }

    @Test
    fun currentSpaceOnly_hidesWindowsNotOnVisibleSpace() {
        // Two-window app on different spaces; only window on space 100 is
        // currently visible. The other should be masked to Hide.
        val onCurrent = winA.copy(spaceIds = listOf(100L))
        val offCurrent = Window(id = 101, pid = 10, title = "A2", spaceIds = listOf(200L))
        val world = world().copy(
            windowsByPid = world().windowsByPid + (regularA.pid to listOf(onCurrent, offCurrent)),
        )
        val snap = world.filteredSnapshot(
            filters = FilteringRules(rules = emptyList()),
            currentSpaceOnly = true,
            visibleSpaceIds = listOf(100L),
        )
        val a = snap.show.first { it.app.pid == regularA.pid }
        val byMode = a.windows.associate { it.window.id to it.mode }
        assertEquals(TriFilter.Show, byMode[100L])
        assertEquals(TriFilter.Hide, byMode[101L])
    }

    @Test
    fun currentSpaceOnly_offIsNoOp() {
        // With the toggle off, off-space windows stay visible regardless of
        // visibleSpaceIds.
        val offCurrent = winA.copy(spaceIds = listOf(200L))
        val world = world().copy(
            windowsByPid = world().windowsByPid + (regularA.pid to listOf(offCurrent)),
        )
        val snap = world.filteredSnapshot(
            filters = FilteringRules(rules = emptyList()),
            currentSpaceOnly = false,
            visibleSpaceIds = listOf(100L),
        )
        assertTrue(snap.show.any { it.app.pid == regularA.pid })
    }

    @Test
    fun currentSpaceOnly_emptyVisibleSetSkipsTheFilter() {
        // The Swift side hasn't seeded visibleSpaceIds yet (e.g. the private
        // CGS API failed). Treat that as "feature unavailable" so the
        // switcher doesn't silently lose every window.
        val offCurrent = winA.copy(spaceIds = listOf(200L))
        val world = world().copy(
            windowsByPid = world().windowsByPid + (regularA.pid to listOf(offCurrent)),
        )
        val snap = world.filteredSnapshot(
            filters = FilteringRules(rules = emptyList()),
            currentSpaceOnly = true,
            visibleSpaceIds = emptyList(),
        )
        assertTrue(snap.show.any { it.app.pid == regularA.pid })
    }

    @Test
    fun currentSpaceOnly_windowWithoutSpaceDataIsKept() {
        // Window's own spaceIds are empty (AX/CGS conversion failed for
        // this one). We can't know whether it's on the current space, so
        // we keep it rather than silently dropping.
        val noSpaceData = winA.copy(spaceIds = emptyList())
        val world = world().copy(
            windowsByPid = world().windowsByPid + (regularA.pid to listOf(noSpaceData)),
        )
        val snap = world.filteredSnapshot(
            filters = FilteringRules(rules = emptyList()),
            currentSpaceOnly = true,
            visibleSpaceIds = listOf(100L),
        )
        assertTrue(snap.show.any { it.app.pid == regularA.pid })
    }

    @Test
    fun currentSpaceOnly_appWithAllOffSpaceWindowsFallsToPhantom() {
        // All windows masked to Hide → app falls through to phantom
        // evaluation. With empty rules → phantom default Show, so the app
        // is still visible (just with no windows the user can navigate to).
        val off = winA.copy(spaceIds = listOf(200L))
        val world = world().copy(
            windowsByPid = world().windowsByPid + (regularA.pid to listOf(off)),
        )
        val snap = world.filteredSnapshot(
            filters = FilteringRules(rules = emptyList()),
            currentSpaceOnly = true,
            visibleSpaceIds = listOf(100L),
        )
        assertTrue(snap.show.any { it.app.pid == regularA.pid })
    }

    @Test
    fun noVisibleWindows_canShowWindowlessAppExplicitly() {
        // Opt-in to showing windowless apps via the new predicate.
        val rules = FilteringRules(
            rules = listOf(
                Rule(
                    id = "show-windowless",
                    predicates = listOf(NoVisibleWindowsPredicate()),
                    outcome = TriFilter.Show,
                ),
            ),
        )
        val snap = world().filteredSnapshot(rules)
        assertTrue(snap.show.any { it.app.pid == windowless.pid })
    }
}
