package com.shish.kaltswitch.model

import kotlinx.serialization.Serializable

/** Three-way filter outcome: include normally, push to the demoted bucket, or hide. */
@Serializable
enum class TriFilter { Show, Demote, Hide }

/**
 * One classification rule. AND of [predicates], evaluated per window. The
 * first matching rule (in list order) decides the window's [outcome]; later
 * rules don't get a turn.
 *
 * - [enabled]: rule-level kill switch. A disabled rule is skipped entirely
 *   so the user can A/B-test by toggling instead of deleting.
 * - [name]: human-readable label; the UI falls back to a generated summary
 *   when this is blank.
 * - A rule with no enabled predicates is inert (matches nothing). This is
 *   the default state of a freshly-created rule, so adding one is safe even
 *   before the user wires up its predicates.
 */
@Serializable
data class Rule(
    val id: String,
    val name: String = "",
    val enabled: Boolean = true,
    val predicates: List<Predicate> = emptyList(),
    val outcome: TriFilter = TriFilter.Hide,
)

/** True iff every enabled predicate matches the (app, window, isPhantom) tuple. */
fun Rule.matches(app: App, window: Window, isPhantom: Boolean): Boolean {
    if (!enabled) return false
    val active = predicates.filter { it.enabled }
    if (active.isEmpty()) return false
    return active.all { it.matches(app, window, isPhantom) }
}

/**
 * User's classification configuration. A single ordered list of rules —
 * no separate fallback toggles, since the rule chain plus the
 * `noVisibleWindows` predicate cover everything the old fallbacks did.
 *
 * The default ruleset ([SeedRules]) ships with a handful of common-sense
 * rules tuned against a real macOS workload: hide the switcher's own
 * untitled overlay, hide Finder's untitled menubar window, hide
 * sub-100-px-area sliver windows, and demote Firefox PiP popouts. Two
 * accessory-app templates ship disabled — they're examples the user can
 * enable if those apps start polluting their switcher.
 */
@Serializable
data class FilteringRules(
    val rules: List<Rule> = SeedRules,
)

/**
 * Default ruleset. IDs are stable strings so JSON round-trips don't churn
 * them and so the rules are recognisable in `config.json` if a user pokes
 * around. Tests that want a guaranteed-empty list pass `rules = emptyList()`
 * explicitly.
 */
val SeedRules: List<Rule> = listOf(
    Rule(
        id = "default-hide-kaltswitch-untitled",
        name = "hide KAltSwitch switcher",
        predicates = listOf(
            BundleIdPredicate(value = "com.shish.kaltswitch"),
            TitlePredicate(op = StringOp.Eq, value = ""),
        ),
        outcome = TriFilter.Hide,
    ),
    Rule(
        id = "default-hide-finder-untitled",
        name = "hide MacOs Finder",
        predicates = listOf(
            BundleIdPredicate(value = "com.apple.finder"),
            TitlePredicate(op = StringOp.Eq, value = ""),
        ),
        outcome = TriFilter.Hide,
    ),
    Rule(
        id = "default-show-accessory-windows",
        name = "show Accessory Windows",
        enabled = false,
        predicates = listOf(
            ActivationPolicyPredicate(value = PolicyValue.Accessory),
            RolePredicate(op = StringOp.Eq, value = "Window"),
        ),
        outcome = TriFilter.Show,
    ),
    Rule(
        id = "default-hide-accessory-windowless",
        name = "hide Accessory windowless apps",
        enabled = false,
        predicates = listOf(
            ActivationPolicyPredicate(value = PolicyValue.Accessory),
        ),
        outcome = TriFilter.Hide,
    ),
    Rule(
        id = "default-hide-small-area",
        name = "hide small-area windows",
        predicates = listOf(
            AreaPredicate(op = NumberOp.Lte, value = 100.0),
        ),
        outcome = TriFilter.Hide,
    ),
    Rule(
        id = "default-demote-ff-pip",
        name = "demote FF PiP windows",
        predicates = listOf(
            BundleIdPredicate(value = "org.mozilla.firefox"),
            TitlePredicate(op = StringOp.Contains, value = "Picture-in-Picture"),
        ),
        outcome = TriFilter.Demote,
    ),
)

/** A window decorated with the filter mode classification. */
data class WindowView(
    val window: Window,
    val mode: TriFilter,
    val children: List<WindowView>,
)

/**
 * An app with its windows already classified and sorted: within `windows`,
 * Show items come first, then Demote, then Hide. Order within each group is
 * the original (recency-driven) order.
 */
data class AppView(
    val app: App,
    val windows: List<WindowView>,
    val mode: TriFilter,
)

/** Three buckets at the app level. The UI renders all three; the eventual switcher overlay would render only `show`. */
data class FilteredSnapshot(
    val show: List<AppView>,
    val demote: List<AppView>,
    val hide: List<AppView>,
) {
    val all: List<AppView> get() = show + demote + hide
}

/**
 * Apply rules to the world's raw snapshot.
 *
 * Algorithm:
 * 1. Each real window walks the rule list (first-match-wins, default Show);
 *    `noVisibleWindows` evaluates `false` on real windows.
 * 2. The app's section is derived from those window modes — any Show → Show,
 *    else any Demote → Demote.
 * 3. Otherwise (no surviving windows: zero real windows, or all hidden by
 *    rules) the classifier synthesises a **phantom** window with default
 *    field values and walks the rule list against it; `noVisibleWindows`
 *    evaluates `true`. The phantom's outcome becomes the app's section. If
 *    no rule matches the phantom the app defaults to Hide — windowless
 *    apps stay out of the way unless the user opts them in via a rule.
 *
 * The phantom is invisible to the rest of the UI; only the resulting app
 * section escapes the classifier.
 */
fun World.filteredSnapshot(filters: FilteringRules): FilteredSnapshot {
    val raw = snapshot()
    val all = raw.withWindows + raw.windowless

    val show = mutableListOf<AppView>()
    val demote = mutableListOf<AppView>()
    val hide = mutableListOf<AppView>()

    for (entry in all) {
        val winViews = entry.windows
            .map { classifyWindow(entry.app, it, filters, isPhantom = false) }
            .sortedBy(::modeOrder)
        val mode = appSection(entry.app, winViews, filters)
        val view = AppView(entry.app, winViews, mode)
        when (mode) {
            TriFilter.Show -> show.add(view)
            TriFilter.Demote -> demote.add(view)
            TriFilter.Hide -> hide.add(view)
        }
    }

    return FilteredSnapshot(show, demote, hide)
}

/**
 * Decide an app's section. Windows-first: any decided-Show window → Show,
 * any decided-Demote → Demote. Otherwise the rule chain runs against a
 * phantom window so the user can express "no visible windows → ..." (and
 * any other app-level rule such as "accessory → Hide") declaratively.
 */
private fun appSection(app: App, windows: List<WindowView>, f: FilteringRules): TriFilter {
    if (windows.any { it.mode == TriFilter.Show }) return TriFilter.Show
    if (windows.any { it.mode == TriFilter.Demote }) return TriFilter.Demote
    val phantom = phantomWindow(app)
    return f.rules.firstOrNull { it.matches(app, phantom, isPhantom = true) }?.outcome
        ?: TriFilter.Hide
}

/** Synthetic stand-in window used to evaluate app-level rules for apps
 *  with no visible real windows. All fields default; matching window-side
 *  predicates against it is well-defined. */
private fun phantomWindow(app: App): Window = Window(
    id = -1L,
    pid = app.pid,
    title = "",
)

/**
 * Classify one window (and its children, recursively) by walking the rule
 * list. First-match-wins; default if no rule matches is `Show`.
 */
private fun classifyWindow(app: App, w: Window, f: FilteringRules, isPhantom: Boolean): WindowView {
    val mode = f.rules.firstOrNull { it.matches(app, w, isPhantom) }?.outcome ?: TriFilter.Show
    val childViews = w.children
        .map { classifyWindow(app, it, f, isPhantom = false) }
        .sortedBy(::modeOrder)
    return WindowView(w, mode, childViews)
}

private fun modeOrder(v: TriFilter): Int = when (v) {
    TriFilter.Show -> 0
    TriFilter.Demote -> 1
    TriFilter.Hide -> 2
}

private fun modeOrder(v: WindowView): Int = modeOrder(v.mode)

/**
 * Build the switcher's snapshot from the same filter pipeline the inspector
 * uses. `Show` apps land in the primary group (`withWindows`), `Demote` apps
 * land in the secondary group and sit behind the vertical separator,
 * `Hide` apps are dropped entirely.
 *
 * Within each app, hidden windows are dropped; the rest keep their inspector
 * order (Show first, then Demote — already pre-sorted by [filteredSnapshot]).
 * Child windows (sheets/drawers) are flattened out — they're not navigation
 * targets in the switcher.
 */
fun World.filteredSwitcherSnapshot(filters: FilteringRules): SwitcherSnapshot {
    val fs = filteredSnapshot(filters)
    fun toEntry(av: AppView): AppEntry {
        val visible = av.windows.filter { it.mode != TriFilter.Hide }
        val shownWindowCount = visible.count { it.mode == TriFilter.Show }
        return AppEntry(
            app = av.app,
            windows = visible.map { it.window },
            shownWindowCount = shownWindowCount,
        )
    }
    return SwitcherSnapshot(
        withWindows = fs.show.map(::toEntry),
        windowless = fs.demote.map(::toEntry),
    )
}
