package com.shish.kaltswitch.model

/**
 * Bubble-up result for one (sub)window. [displayBadge] is what the
 * switcher renders on this window's row — `null` either because no rule
 * matched OR because every sibling had the same badge and it bubbled to
 * the parent (which now displays it instead).
 *
 * [children] mirrors [Window.children] post-bubble: a child whose badge
 * was promoted to its parent shows up here with [displayBadge] = null.
 */
data class WindowBadge(
    val windowId: WindowId,
    val displayBadge: ResolvedBadge?,
    val children: List<WindowBadge>,
)

/**
 * Bubble-up result for one app + the windows the switcher will render.
 * [displayBadge] is what to paint on the app icon (top-right pill); when
 * it's non-null every entry in [windows] has its own [WindowBadge.displayBadge]
 * cleared, since the spec is "if the app shows a badge no window repeats it".
 */
data class AppBadgeTree(
    val displayBadge: ResolvedBadge?,
    val windows: List<WindowBadge>,
)

/**
 * Compute per-app and per-window badges by:
 *   1. Recursively evaluating each (sub)window against [rules].
 *   2. If every visible sibling under a node has the same non-null
 *      badge, that badge bubbles up to the parent and the siblings clear.
 *      The same check then runs at the parent's own level — so a chain
 *      can collapse multiple steps in one pass.
 *   3. The same merge runs at the App level over [visibleWindows] (which
 *      is `entry.windows` — the windows that survived the user's
 *      filter rules; the spec is "только по видимым окнам").
 *
 * Per-window evaluation uses the (app, window.title) pair. A windowless
 * leaf (no children, title doesn't match any rule) gets `null`. App-only
 * rules — i.e. rules with no title predicate, narrowing only by appName /
 * bundleId — match every window of the app to the same badge, so the
 * bubble-up trivially elevates them to the app cell.
 */
fun computeBadgeTree(
    rules: BadgeRules,
    app: App,
    visibleWindows: List<Window>,
): AppBadgeTree {
    val windowBadges = visibleWindows.map { buildWindowBadge(rules, app, it) }
    val bubbled = bubble(windowBadges.map { it.displayBadge })
    return if (bubbled != null) {
        AppBadgeTree(
            displayBadge = bubbled,
            windows = windowBadges.map { it.copy(displayBadge = null) },
        )
    } else {
        AppBadgeTree(displayBadge = null, windows = windowBadges)
    }
}

private fun buildWindowBadge(rules: BadgeRules, app: App, window: Window): WindowBadge {
    val children = window.children.map { buildWindowBadge(rules, app, it) }
    val ownBadge = rules.evaluate(app, window.title)
    val bubbledFromChildren = if (children.isEmpty()) null else bubble(children.map { it.displayBadge })
    return if (bubbledFromChildren != null) {
        WindowBadge(
            windowId = window.id,
            displayBadge = bubbledFromChildren,
            children = children.map { it.copy(displayBadge = null) },
        )
    } else {
        WindowBadge(windowId = window.id, displayBadge = ownBadge, children = children)
    }
}

/**
 * Returns the shared badge if every entry is the same non-null
 * [ResolvedBadge], else `null`. An empty list — i.e. a leaf with no
 * children — never bubbles, since "all equal" is vacuously true but
 * there's nothing to elevate. A single non-null entry does bubble (one
 * window's badge naturally promotes to its parent's level).
 */
private fun bubble(badges: List<ResolvedBadge?>): ResolvedBadge? {
    if (badges.isEmpty()) return null
    val first = badges[0] ?: return null
    if (badges.any { it != first }) return null
    return first
}
