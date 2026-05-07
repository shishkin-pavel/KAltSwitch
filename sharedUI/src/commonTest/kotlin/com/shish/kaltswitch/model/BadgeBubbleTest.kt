package com.shish.kaltswitch.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BadgeBubbleTest {
    private val firefoxApp = App(pid = 1, bundleId = "org.mozilla.firefox", name = "Firefox")

    private fun win(
        id: WindowId,
        title: String = "",
        children: List<Window> = emptyList(),
    ): Window = Window(id = id, pid = firefoxApp.pid, title = title, isMain = true, children = children)

    private fun profileRule(): BadgeRules = BadgeRules(
        rules = listOf(
            BadgeRule(
                id = "b-profile",
                titleOp = StringOp.Regex,
                titleValue = ".* — (.+)$",
                text = "$1",
                colorRgb = 0x4444FFL,
            ),
        ),
    )

    private val personal = ResolvedBadge("Personal", 0x4444FFL)
    private val work = ResolvedBadge("Work", 0x4444FFL)

    @Test
    fun singleWindowBubblesToApp() {
        val tree = computeBadgeTree(
            profileRule(),
            firefoxApp,
            listOf(win(1, "Hacker News — Personal")),
        )
        assertEquals(personal, tree.displayBadge)
        // Window's own badge is cleared because it bubbled.
        assertNull(tree.windows[0].displayBadge)
    }

    @Test
    fun differentBadgesAcrossWindowsStayPerWindow() {
        val tree = computeBadgeTree(
            profileRule(),
            firefoxApp,
            listOf(
                win(1, "Foo — Personal"),
                win(2, "Bar — Work"),
            ),
        )
        // App has nothing because windows disagree.
        assertNull(tree.displayBadge)
        assertEquals(personal, tree.windows[0].displayBadge)
        assertEquals(work, tree.windows[1].displayBadge)
    }

    @Test
    fun mixedBadgeAndNullDoesNotBubble() {
        // One window matches "Personal", the other has no profile suffix
        // → no shared badge → no bubble. Matched window keeps its own.
        val tree = computeBadgeTree(
            profileRule(),
            firefoxApp,
            listOf(
                win(1, "Page — Personal"),
                win(2, "Plain Page"),
            ),
        )
        assertNull(tree.displayBadge)
        assertEquals(personal, tree.windows[0].displayBadge)
        assertNull(tree.windows[1].displayBadge)
    }

    @Test
    fun allEqualMultipleWindowsBubbleToApp() {
        val tree = computeBadgeTree(
            profileRule(),
            firefoxApp,
            listOf(
                win(1, "Page A — Personal"),
                win(2, "Page B — Personal"),
                win(3, "Page C — Personal"),
            ),
        )
        assertEquals(personal, tree.displayBadge)
        tree.windows.forEach { assertNull(it.displayBadge) }
    }

    @Test
    fun appNameOnlyRuleBubblesNaturally() {
        // Pure-app rule: every window of Firefox gets the same badge,
        // so the bubble-up promotes it to the app cell.
        val rules = BadgeRules(
            rules = listOf(
                BadgeRule(
                    id = "b-firefox",
                    appNameOp = StringOp.Eq,
                    appNameValue = "Firefox",
                    text = "FF",
                    colorRgb = 0xFF6600L,
                ),
            ),
        )
        val tree = computeBadgeTree(
            rules,
            firefoxApp,
            listOf(win(1, "Anything"), win(2, "Else")),
        )
        assertEquals(ResolvedBadge("FF", 0xFF6600L), tree.displayBadge)
        tree.windows.forEach { assertNull(it.displayBadge) }
    }

    @Test
    fun childrenBubbleUpToParentThenToApp() {
        // Window with two child sheets, both matching the same rule.
        // First the children's badge bubbles up to the parent; then the
        // parent (now holding "Personal") is the only window of the app
        // and bubbles up further. Result: app shows the badge, neither
        // the parent window nor its children show theirs.
        val tree = computeBadgeTree(
            profileRule(),
            firefoxApp,
            listOf(
                win(
                    id = 1,
                    title = "Plain Title",          // own match: null
                    children = listOf(
                        Window(id = 10, pid = 1, title = "Sheet — Personal"),
                        Window(id = 11, pid = 1, title = "Sheet 2 — Personal"),
                    ),
                ),
            ),
        )
        assertEquals(personal, tree.displayBadge)
        val w = tree.windows.single()
        assertNull(w.displayBadge)
        w.children.forEach { assertNull(it.displayBadge) }
    }

    @Test
    fun childrenWithMixedBadgesKeepTheirOwn() {
        // Two children with different badges — parent doesn't get a
        // bubbled badge. The parent's own title doesn't match either,
        // so the parent's row has no badge; each child shows its own.
        val tree = computeBadgeTree(
            profileRule(),
            firefoxApp,
            listOf(
                win(
                    id = 1,
                    title = "Plain Title",
                    children = listOf(
                        Window(id = 10, pid = 1, title = "Sheet — Personal"),
                        Window(id = 11, pid = 1, title = "Sheet — Work"),
                    ),
                ),
            ),
        )
        assertNull(tree.displayBadge)
        val w = tree.windows.single()
        assertNull(w.displayBadge)
        assertEquals(personal, w.children[0].displayBadge)
        assertEquals(work, w.children[1].displayBadge)
    }

    @Test
    fun emptyWindowListGivesEmptyTree() {
        val tree = computeBadgeTree(profileRule(), firefoxApp, emptyList())
        assertNull(tree.displayBadge)
        assertEquals(emptyList(), tree.windows)
    }

    @Test
    fun parentOwnTitleIgnoredWhenChildrenBubble() {
        // Parent matches "Personal" by its own title, but children both
        // match "Work". Children bubble up wins — parent gets "Work",
        // its own title-derived "Personal" is shadowed.
        val tree = computeBadgeTree(
            profileRule(),
            firefoxApp,
            listOf(
                win(
                    id = 1,
                    title = "Page — Personal",
                    children = listOf(
                        Window(id = 10, pid = 1, title = "Sheet — Work"),
                        Window(id = 11, pid = 1, title = "Sheet 2 — Work"),
                    ),
                ),
            ),
        )
        // Parent has "Work" (bubbled from children). It's the only
        // top-level window so it bubbles further → app shows "Work".
        assertEquals(work, tree.displayBadge)
    }
}
