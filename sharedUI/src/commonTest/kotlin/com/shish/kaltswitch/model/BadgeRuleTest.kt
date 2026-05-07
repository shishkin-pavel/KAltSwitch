package com.shish.kaltswitch.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BadgeRuleTest {
    private fun app(
        name: String = "Firefox",
        bundleId: String? = "org.mozilla.firefox",
        pid: Int = 100,
    ): App = App(pid = pid, bundleId = bundleId, name = name)

    @Test
    fun emptyRules_returnNull() {
        assertNull(BadgeRules().evaluate(app(), "anything"))
    }

    @Test
    fun titleRegexExpandsCaptureGroup() {
        val rules = BadgeRules(
            rules = listOf(
                BadgeRule(
                    id = "b-1",
                    titleOp = StringOp.Regex,
                    titleValue = " — (.+)$",
                    text = "$1",
                    colorRgb = 0xFF0000L,
                ),
            ),
        )
        val resolved = rules.evaluate(app(), "Hacker News — Personal")
        assertEquals(ResolvedBadge(text = "Personal", colorRgb = 0xFF0000L), resolved)
    }

    @Test
    fun appNamePredicateNarrowsRule() {
        val rule = BadgeRule(
            id = "b-1",
            appNameOp = StringOp.Eq,
            appNameValue = "Firefox",
            text = "FF",
        )
        val rules = BadgeRules(rules = listOf(rule))
        // Matches when name is "Firefox".
        assertEquals(ResolvedBadge("FF", DefaultBadgeColorRgb), rules.evaluate(app(name = "Firefox"), ""))
        // Skips when name is something else.
        assertNull(rules.evaluate(app(name = "Safari"), ""))
    }

    @Test
    fun bundleIdPredicateNarrowsRule() {
        val rule = BadgeRule(
            id = "b-1",
            bundleIdOp = StringOp.Contains,
            bundleIdValue = "mozilla",
            text = "FF",
        )
        val rules = BadgeRules(rules = listOf(rule))
        assertEquals(
            ResolvedBadge("FF", DefaultBadgeColorRgb),
            rules.evaluate(app(bundleId = "org.mozilla.firefox"), ""),
        )
        assertNull(rules.evaluate(app(bundleId = "com.apple.Safari"), ""))
        // Null bundleId is treated as the empty string — Contains "mozilla" fails.
        assertNull(rules.evaluate(app(bundleId = null), ""))
    }

    @Test
    fun emptyValuePredicateSkipsItself() {
        // Only appName is filled in; bundleId & title rows have empty
        // values and non-IsEmpty ops, so they shouldn't sink the rule.
        val rule = BadgeRule(
            id = "b-1",
            appNameOp = StringOp.Eq,
            appNameValue = "Firefox",
            bundleIdOp = StringOp.Eq,           // empty value → skip
            bundleIdValue = "",
            titleOp = StringOp.Regex,           // empty value → skip
            titleValue = "",
            text = "FF",
        )
        val rules = BadgeRules(rules = listOf(rule))
        assertEquals(
            ResolvedBadge("FF", DefaultBadgeColorRgb),
            rules.evaluate(app(name = "Firefox", bundleId = null), "anything"),
        )
    }

    @Test
    fun isEmptyOpStillFiresWhenValueBlank() {
        // bundleId IsEmpty (with blank value) is *not* skipped — it
        // genuinely matches an app whose bundle id is null/empty.
        val rule = BadgeRule(
            id = "b-1",
            bundleIdOp = StringOp.IsEmpty,
            bundleIdValue = "",
            text = "x",
        )
        val rules = BadgeRules(rules = listOf(rule))
        assertEquals(ResolvedBadge("x", DefaultBadgeColorRgb), rules.evaluate(app(bundleId = null), ""))
        assertNull(rules.evaluate(app(bundleId = "org.example.app"), ""))
    }

    @Test
    fun firstMatchingRuleWins() {
        val rules = BadgeRules(
            rules = listOf(
                BadgeRule(id = "a", appNameOp = StringOp.Eq, appNameValue = "Safari", text = "S", colorRgb = 0x111111L),
                BadgeRule(id = "b", appNameOp = StringOp.Eq, appNameValue = "Firefox", text = "F", colorRgb = 0x222222L),
                BadgeRule(id = "c", appNameOp = StringOp.Contains, appNameValue = "fox", text = "X", colorRgb = 0x333333L),
            ),
        )
        // Both b and c match Firefox; the earlier rule wins.
        assertEquals(ResolvedBadge("F", 0x222222L), rules.evaluate(app(name = "Firefox"), ""))
    }

    @Test
    fun disabledRuleIsSkipped() {
        val rules = BadgeRules(
            rules = listOf(
                BadgeRule(id = "a", enabled = false, appNameValue = "Firefox", text = "first"),
                BadgeRule(id = "b", appNameValue = "Firefox", text = "second"),
            ),
        )
        val resolved = rules.evaluate(app(name = "Firefox"), "")
        assertEquals("second", resolved?.text)
    }

    @Test
    fun outOfRangeCaptureLeavesLiteral() {
        // Pattern "Personal" has no capture groups, so $1 has nothing to
        // substitute. We deliberately leave the literal "$1" visible
        // rather than silently emit empty text — user sees the badge is
        // misconfigured instead of the rule disappearing.
        val rules = BadgeRules(
            rules = listOf(
                BadgeRule(
                    id = "a",
                    titleOp = StringOp.Regex,
                    titleValue = "Personal",
                    text = "$1",
                    colorRgb = 0xAAAAAAL,
                ),
            ),
        )
        assertEquals(ResolvedBadge("\$1", 0xAAAAAAL), rules.evaluate(app(), "Hacker News — Personal"))
    }

    @Test
    fun emptyResolvedTextFallsThrough() {
        // First rule's $1 expansion gives an empty string (group 1 captured
        // nothing). We treat empty-after-expansion as "rule didn't really
        // produce a badge" and try the next rule.
        val rules = BadgeRules(
            rules = listOf(
                BadgeRule(
                    id = "a",
                    titleOp = StringOp.Regex,
                    titleValue = "Personal( ?)",
                    text = "$1",                    // → "" (empty optional group)
                    colorRgb = 0xAAAAAAL,
                ),
                BadgeRule(
                    id = "b",
                    titleOp = StringOp.Contains,
                    titleValue = "Personal",
                    text = "P",
                    colorRgb = 0xBBBBBBL,
                ),
            ),
        )
        assertEquals(ResolvedBadge("P", 0xBBBBBBL), rules.evaluate(app(), "Hacker News — Personal"))
    }

    @Test
    fun invalidRegexMatchesNothing() {
        val rules = BadgeRules(
            rules = listOf(
                BadgeRule(
                    id = "a",
                    titleOp = StringOp.Regex,
                    titleValue = "[unclosed",
                    text = "x",
                ),
            ),
        )
        assertNull(rules.evaluate(app(), "any title"))
    }

    @Test
    fun captureGroupEscapeProducesLiteralDollar() {
        val rules = BadgeRules(
            rules = listOf(
                BadgeRule(
                    id = "a",
                    titleOp = StringOp.Regex,
                    titleValue = "(.+)",
                    text = "$$$1",
                ),
            ),
        )
        // $$ → literal $, $1 → first capture group.
        assertEquals("\$Personal", rules.evaluate(app(), "Personal")?.text)
    }
}
