package com.shish.kaltswitch.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PinningRuleTest {

    private val app = App(pid = 10, bundleId = "org.mozilla.firefox", name = "Firefox")

    @Test
    fun emptyRuleListMatchesNothing() {
        assertFalse(PinningRules().matches(app, Window(id = 1, pid = 10, title = "anything")))
    }

    @Test
    fun ruleWithNoEnabledPredicatesIsInert() {
        // Empty predicate list — rule should be inert even when enabled.
        val rules = PinningRules(rules = listOf(PinningRule(id = "p-1")))
        assertFalse(rules.matches(app, Window(id = 1, pid = 10, title = "X")))
    }

    @Test
    fun disabledRuleSkipped() {
        val rules = PinningRules(
            rules = listOf(
                PinningRule(
                    id = "p-1",
                    enabled = false,
                    predicates = listOf(TitlePredicate(op = StringOp.Eq, value = "X")),
                ),
            ),
        )
        assertFalse(rules.matches(app, Window(id = 1, pid = 10, title = "X")))
    }

    @Test
    fun predicatesAreANDed() {
        val rules = PinningRules(
            rules = listOf(
                PinningRule(
                    id = "p-1",
                    predicates = listOf(
                        AppNamePredicate(value = "Firefox"),
                        TitlePredicate(op = StringOp.Contains, value = "Picture-in-Picture"),
                    ),
                ),
            ),
        )
        assertTrue(rules.matches(app, Window(id = 1, pid = 10, title = "Twitch — Picture-in-Picture")))
        // App name matches but title doesn't.
        assertFalse(rules.matches(app, Window(id = 2, pid = 10, title = "Hacker News")))
        // Title matches but app name doesn't.
        val safari = app.copy(name = "Safari", bundleId = "com.apple.safari")
        assertFalse(rules.matches(safari, Window(id = 3, pid = 10, title = "Twitch — Picture-in-Picture")))
    }

    @Test
    fun anyMatchingRuleWins() {
        // Two rules, only the second matches — should still be a pin.
        val rules = PinningRules(
            rules = listOf(
                PinningRule(
                    id = "p-1",
                    predicates = listOf(TitlePredicate(op = StringOp.Eq, value = "Never")),
                ),
                PinningRule(
                    id = "p-2",
                    predicates = listOf(TitlePredicate(op = StringOp.Eq, value = "Hit")),
                ),
            ),
        )
        assertTrue(rules.matches(app, Window(id = 1, pid = 10, title = "Hit")))
    }

    @Test
    fun invertedPredicateNegates() {
        // "title is not empty" — should match a titled window.
        val rules = PinningRules(
            rules = listOf(
                PinningRule(
                    id = "p-1",
                    predicates = listOf(TitlePredicate(op = StringOp.IsEmpty, inverted = true)),
                ),
            ),
        )
        assertTrue(rules.matches(app, Window(id = 1, pid = 10, title = "Hello")))
        assertFalse(rules.matches(app, Window(id = 2, pid = 10, title = "")))
    }

    @Test
    fun roleAndSubrolePredicates_workForSheetLikeWindows() {
        val rules = PinningRules(
            rules = listOf(
                PinningRule(
                    id = "p-1",
                    predicates = listOf(RolePredicate(op = StringOp.Eq, value = "AXSheet")),
                ),
            ),
        )
        val sheet = Window(id = 1, pid = 10, title = "", role = "AXSheet")
        val regular = Window(id = 2, pid = 10, title = "Main", role = "AXWindow")
        assertTrue(rules.matches(app, sheet))
        assertFalse(rules.matches(app, regular))
    }

    @Test
    fun pinningRuleSerialisedRoundTrip() {
        // Smoke test for kotlinx-serialization wiring — the Pinning tab
        // depends on this round-tripping through ~/Library/.../config.json.
        val original = PinningRules(
            rules = listOf(
                PinningRule(
                    id = "p-1",
                    name = "FF PiP",
                    predicates = listOf(
                        AppNamePredicate(value = "Firefox"),
                        TitlePredicate(op = StringOp.Contains, value = "Picture-in-Picture"),
                    ),
                ),
            ),
        )
        val json = com.shish.kaltswitch.config.configJson
        val text = json.encodeToString(PinningRules.serializer(), original)
        val parsed = json.decodeFromString(PinningRules.serializer(), text)
        assertEquals(original, parsed)
    }
}
