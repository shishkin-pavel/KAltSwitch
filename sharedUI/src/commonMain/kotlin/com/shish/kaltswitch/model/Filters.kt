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
    /** True if this rule ships built into the app (one of [SeedRules]) and
     *  must not be edited at runtime. Built-ins are kept in lock-step with
     *  their canonical seed by [withSeedDefaults], which resets predicates,
     *  name, and outcome to the seed every load — only [enabled] (and the
     *  rule's position in the chain) is user-controllable. The UI gates
     *  every editor on this flag; the classifier doesn't care. Serialised
     *  so a `config.json` round-trip preserves the distinction even though
     *  it's also re-derived from id on load. */
    val builtIn: Boolean = false,
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
 * Reconcile [rules] with the shipped [SeedRules]:
 *
 *  1. **Normalise** any rule whose `id` is in [SeedRules] back to that
 *     seed's canonical predicates / name / outcome / `builtIn=true`,
 *     preserving only the user's `enabled` flag. Built-ins are immutable
 *     to the user (see [Rule.builtIn]); this is the enforcement step.
 *     Also retroactively marks `builtIn=true` on rules that pre-date the
 *     flag's introduction.
 *  2. **Append missing seeds** to the end of the chain. Each newly-
 *     shipped default lands after all existing rules so it doesn't
 *     silently re-order the user's chain. Position policy is the
 *     SeedRules list order itself — that's the order a fresh config
 *     gets via the data-class default.
 *
 * Doesn't drop rules with unknown ids — old defaults removed from
 * [SeedRules] continue to live in the user's config until the user
 * deletes them. Silently dropping a rule mid-run is the kind of
 * surprise that makes a user reach for their bug tracker.
 */
fun FilteringRules.withSeedDefaults(): FilteringRules {
    val seedById = SeedRules.associateBy { it.id }
    val normalised = rules.map { existing ->
        val seed = seedById[existing.id] ?: return@map existing
        seed.copy(enabled = existing.enabled)
    }
    val existingIds = normalised.mapTo(HashSet()) { it.id }
    val missing = SeedRules.filterNot { it.id in existingIds }
    if (missing.isEmpty()) return copy(rules = normalised)
    return copy(rules = normalised + missing)
}

/**
 * Default ruleset. IDs are stable strings so JSON round-trips don't churn
 * them and so the rules are recognisable in `config.json` if a user pokes
 * around. Tests that want a guaranteed-empty list pass `rules = emptyList()`
 * explicitly.
 *
 * **List order matters.** [withSeedDefaults] appends missing seeds in this
 * order. A fresh install — and any user upgrading after a new seed is
 * added — sees rules in exactly this sequence. Earlier rules win because
 * the classifier is first-match. The shape below is the result of the
 * user's curation: switcher dialog + accessory show + minimised demote +
 * AXUnknown hide come first because they make the largest dent on real
 * traffic; CG-phantom triage rules come after so they don't get in the
 * way of AX-side classification; demote-hidden lands last as a low-
 * priority fallthrough.
 *
 * **Removed seeds (history).** Earlier shipped defaults that the user
 * disabled, weren't pulling weight, or duplicated a user-side rule have
 * been deleted from this list: `default-hide-finder-untitled`,
 * `default-hide-accessory-windowless`, `default-hide-hidden-apps`,
 * `default-show-accessory-windows`, `default-demote-ff-pip`, and the
 * twelve `default-hide-cg-<ownerName>` rules (Window Server / Dock /
 * Control Center / …). [withSeedDefaults] doesn't re-add them; if they
 * persist in a user's config (we don't auto-drop unknown ids), the
 * Settings UI lets the user delete them — for `builtIn=true` rows the
 * delete affordance is wired alongside the enable toggle now they're
 * no longer maintained by us.
 */
val SeedRules: List<Rule> = listOf(
    Rule(
        // Hides the borderless switcher overlay (subrole AXSystemDialog
        // on macOS for `.nonactivatingPanel` style) while leaving the
        // Inspector and Settings windows visible — those are
        // AXStandardWindow.
        id = "default-hide-kaltswitch-dialog",
        name = "hide KAltSwitch switcher dialog",
        predicates = listOf(
            BundleIdPredicate(value = "com.shish.kaltswitch"),
            SubrolePredicate(op = StringOp.Eq, value = "AXSystemDialog"),
        ),
        outcome = TriFilter.Hide,
    ),
    Rule(
        // Surfaces menubar / utility-style apps when they have a real
        // standard window open. Without it, accessory apps (Things,
        // 1Password, certain dev tools) don't appear in the switcher
        // even when they have a frontable window.
        id = "default-show-accessory-standard",
        name = "show Accessory Windows",
        predicates = listOf(
            ActivationPolicyPredicate(value = PolicyValue.Accessory),
            RolePredicate(op = StringOp.Eq, value = "AXWindow"),
            SubrolePredicate(op = StringOp.Eq, value = "AXStandardWindow"),
        ),
        outcome = TriFilter.Show,
    ),
    Rule(
        // IntelliJ IDEA / Android Studio / various Electron tools
        // dump utility floaters into the window list with `subrole =
        // AXUnknown`. They're typically tool windows the user
        // doesn't want to cmd+tab to.
        id = "default-hide-axunknown",
        name = "hide AXUnknown windows (IntelliJ Idea / Android Studio / ...)",
        predicates = listOf(
            SubrolePredicate(op = StringOp.Eq, value = "AXUnknown"),
        ),
        outcome = TriFilter.Hide,
    ),
    Rule(
        // Sub-100-px-area sliver windows. Pickers, hidden Spotlight-
        // adjacent search panes that never grew, ghost frames. Floor
        // tuned conservatively — any window large enough to interact
        // with is well above 100 px².
        id = "default-hide-small-area",
        name = "hide small-area windows",
        predicates = listOf(
            AreaPredicate(op = NumberOp.Lte, value = 100.0),
        ),
        outcome = TriFilter.Hide,
    ),
    // ─────────── CG phantom triage (cross-space window enumeration) ───────────
    //
    // Rules below address rows surfaced by the Swift `CGWindowListWatcher`,
    // i.e. windows enumerated through `CGWindowListCopyWindowInfo` to cover
    // macOS's AX-only-current-space limitation. They use the CG-only
    // predicates (`IsOnscreen…` / `CgLayer…` / `Owner…`) which short-circuit
    // to `false` on AX-derived rows, so each rule is automatically scoped
    // to CG phantoms without an explicit source flag.
    Rule(
        // CG returns ~10 layer>0 entries on a typical desktop: menu bar,
        // Dock tile, status bar items, Mission Control overlays.
        id = "default-hide-cg-overlay-layer",
        name = "hide CG overlay layers",
        predicates = listOf(
            CgLayerPredicate(op = NumberOp.Gt, value = 0.0),
        ),
        outcome = TriFilter.Hide,
    ),
    Rule(
        // The discriminator that prompted this whole rule chain. A
        // phantom window that's off-screen *and* on the current space is
        // a hidden helper (autocomplete popover, autofill panel, settings
        // pane the app keeps cached). Off-screen rows on *another* space
        // are legitimate cross-space windows — those have
        // `isOnVisibleSpace=false` and this rule deliberately doesn't
        // touch them.
        //
        // Two extra guards keep the rule away from windows the user
        // demoted on purpose. A minimised window reports
        // `isOnscreen=false` once the CG snapshot refreshes (it's been
        // pulled into the Dock); same for every window of a hidden app
        // (cmd+H drops them off-screen too). Without the guards, both
        // classes would race past `default-demote-minimised` /
        // `default-demote-hidden` (which sit later in the chain) and
        // disappear from the switcher entirely ~one cgwl tick after
        // the user pressed cmd+M / cmd+H. AX-only rows are already
        // excluded by [IsOnscreenPredicate]'s null short-circuit above,
        // so the new guards only affect AX+CG rows, which is exactly
        // the population that carries trustworthy `isMinimized` /
        // `isHidden` signals.
        id = "default-hide-cg-hidden-helper",
        name = "hide hidden CG helpers (off-screen on current space)",
        predicates = listOf(
            IsOnscreenPredicate(inverted = true),
            IsOnVisibleSpacePredicate(),
            IsMinimizedPredicate(inverted = true),
            IsHiddenPredicate(inverted = true),
        ),
        outcome = TriFilter.Hide,
    ),
    Rule(
        // Catches the menubar-shadow phantoms apps maintain for their
        // menu items (typically size=1800×39 pos=0,0 with spaceIds=[]
        // because CGS doesn't tie them to a Mission Control space).
        id = "default-hide-cg-no-space-data",
        name = "hide off-screen CG entries with no space data",
        predicates = listOf(
            IsOnscreenPredicate(inverted = true),
            HasSpaceIdsPredicate(inverted = true),
        ),
        outcome = TriFilter.Hide,
    ),
    Rule(
        id = "default-hide-cg-zero-alpha",
        name = "hide zero-alpha CG entries",
        predicates = listOf(
            CgAlphaPredicate(op = NumberOp.Lte, value = 0.0),
        ),
        outcome = TriFilter.Hide,
    ),
    Rule(
        // Catches CursorUIViewService's many 54×54/64×64 cursor sprites,
        // tooltip popovers (~200×40), autocomplete dropdowns, etc. Floor
        // borrowed from the alt-tab-macos heuristic; loosened a touch so
        // floating tool palettes still qualify.
        id = "default-hide-cg-tiny",
        name = "hide tiny CG entries (< 80×60)",
        predicates = listOf(
            OwnerNamePredicate(op = StringOp.IsEmpty, inverted = true),
            AreaPredicate(op = NumberOp.Lt, value = 4800.0),
        ),
        outcome = TriFilter.Hide,
    ),
    Rule(
        // Accessory apps (LSUIElement = true) without any open windows
        // shouldn't sit in the cmd+tab list — they're typically
        // menubar utilities the user reaches via their status item.
        // Composing [NoVisibleWindowsPredicate] with the activation
        // policy keeps regular utility apps that *do* have a window
        // visible (Things, 1Password, ...) — that's the
        // `default-show-accessory-standard` rule's job earlier in the
        // chain.
        id = "default-hide-accessory-windowless",
        name = "hide accessory apps without windows",
        predicates = listOf(
            NoVisibleWindowsPredicate(),
            ActivationPolicyPredicate(value = PolicyValue.Accessory),
        ),
        outcome = TriFilter.Hide,
    ),
    Rule(
        // Per-window demote: a minimised window is still a valid
        // switch target (cmd+tab to it then it un-minimises), but it
        // shouldn't sit in the primary row.
        id = "default-demote-minimised",
        name = "demote minimised windows",
        predicates = listOf(
            IsMinimizedPredicate(),
        ),
        outcome = TriFilter.Demote,
    ),
    Rule(
        // cmd+H → app.isHidden → demote the app's windows. Lands at the
        // end of the chain so any earlier Hide / Demote rule can still
        // pre-empt (e.g. AXUnknown subrole takes priority over the
        // generic demote-hidden fallthrough).
        id = "default-demote-hidden",
        name = "demote hidden windows",
        predicates = listOf(
            IsHiddenPredicate(),
        ),
        outcome = TriFilter.Demote,
    ),
).map { it.copy(builtIn = true) }   // single source of truth for the built-in flag

/** A window decorated with the filter mode classification. [firingRule] is
 *  the [Rule] whose predicate chain matched and decided [mode]; `null`
 *  means no rule matched and the default fell through (typically `Show`).
 *  Surfaced to the inspector so each window plate can render the rule
 *  name as a badge — that turns "why is this here / why is this hidden"
 *  into a one-glance answer instead of a rule-table archaeology session. */
data class WindowView(
    val window: Window,
    val mode: TriFilter,
    val children: List<WindowView>,
    val firingRule: Rule? = null,
)

/**
 * An app with its windows already classified and sorted: within `windows`,
 * Show items come first, then Demote, then Hide. Order within each group is
 * the original (recency-driven) order.
 *
 * [firingRule] applies only to the app-level second pass — the rule that
 * matched against the synthetic "windowless-app" placeholder. For apps
 * whose section was derived from a window outcome (any Show window → Show
 * app, any Demote → Demote), it stays `null`; in that case the per-window
 * `firingRule` is the source of truth.
 */
data class AppView(
    val app: App,
    val windows: List<WindowView>,
    val mode: TriFilter,
    val firingRule: Rule? = null,
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
 * 2. If [currentSpaceOnly] is on and we have [visibleSpaceIds], any window
 *    that doesn't share a space with the current visible set is forced to
 *    Hide regardless of the rule outcome. The space filter is intentionally
 *    *outside* the rule chain — it's a global "what should I see right
 *    now" toggle, not a property the user reasons about per-rule.
 * 3. The app's section is derived from those window modes — any Show → Show,
 *    else any Demote → Demote.
 * 4. Otherwise (no surviving windows: zero real windows, or all hidden by
 *    rules / the space filter) the classifier synthesises a **phantom**
 *    window with default field values and walks the rule list against it;
 *    `noVisibleWindows` evaluates `true`. The phantom's outcome becomes the
 *    app's section. If no rule matches the phantom the app defaults to Hide
 *    — windowless apps stay out of the way unless the user opts them in
 *    via a rule.
 *
 * The phantom is invisible to the rest of the UI; only the resulting app
 * section escapes the classifier.
 */
fun World.filteredSnapshot(
    filters: FilteringRules,
    pinning: PinningRules = PinningRules(),
    currentSpaceOnly: Boolean = false,
    visibleSpaceIds: List<Long> = emptyList(),
): FilteredSnapshot {
    val raw = snapshot(pinning)
    val all = raw.withWindows + raw.windowless

    val show = mutableListOf<AppView>()
    val demote = mutableListOf<AppView>()
    val hide = mutableListOf<AppView>()

    val spaceFilterActive = currentSpaceOnly && visibleSpaceIds.isNotEmpty()
    val visibleSet = visibleSpaceIds.toHashSet()

    for (entry in all) {
        val winViews = entry.windows
            .map { classifyWindow(entry.app, it, filters, isPhantom = false) }
            .map { if (spaceFilterActive) maskOffSpace(it, visibleSet) else it }
            .sortedBy(::modeOrder)
        val (mode, appRule) = appSection(entry.app, winViews, filters)
        val view = AppView(entry.app, winViews, mode, firingRule = appRule)
        when (mode) {
            TriFilter.Show -> show.add(view)
            TriFilter.Demote -> demote.add(view)
            TriFilter.Hide -> hide.add(view)
        }
    }

    return FilteredSnapshot(show, demote, hide)
}

/** Force [view] (and its children) to Hide if their `spaceIds` share no
 *  members with the currently visible set. Windows we don't have space
 *  data for (empty `spaceIds`) keep their classification — staleness
 *  during the brief window between observation and the next refresh
 *  shouldn't make them disappear from the switcher.
 *
 *  Clears [WindowView.firingRule] when the mask forces Hide — the actual
 *  driver is the global space toggle, not any rule the user can edit,
 *  so the inspector showing "rule: foo" next to a space-masked row would
 *  be misleading. The mode-vs-rule asymmetry is documented on
 *  [WindowView]. */
private fun maskOffSpace(view: WindowView, visible: Set<Long>): WindowView {
    val onCurrent = view.window.spaceIds.isEmpty() || view.window.spaceIds.any { it in visible }
    val newMode = if (onCurrent) view.mode else TriFilter.Hide
    val newRule = if (onCurrent) view.firingRule else null
    val newChildren = view.children.map { maskOffSpace(it, visible) }
    return if (newMode == view.mode && newChildren === view.children) view
    else view.copy(mode = newMode, children = newChildren, firingRule = newRule)
}

/**
 * Decide an app's section. Windows-first: any decided-Show window → Show,
 * any decided-Demote → Demote. Otherwise the rule chain runs against a
 * phantom window so the user can express "no visible windows → ..." (and
 * any other app-level rule such as "accessory → Hide") declaratively.
 *
 * Phantom default = `Show`. The whole pipeline is opt-in: nothing is
 * demoted or hidden without an explicit rule. Users who want windowless
 * apps demoted or accessory utilities hidden author a rule for it
 * (e.g. `noVisibleWindows → Demote`, `activationPolicy == Accessory → Hide`).
 */
private fun appSection(app: App, windows: List<WindowView>, f: FilteringRules): Pair<TriFilter, Rule?> {
    if (windows.any { it.mode == TriFilter.Show }) return TriFilter.Show to null
    if (windows.any { it.mode == TriFilter.Demote }) return TriFilter.Demote to null
    val phantom = phantomWindow(app)
    val matched = f.rules.firstOrNull { it.matches(app, phantom, isPhantom = true) }
    return (matched?.outcome ?: TriFilter.Show) to matched
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
    val matched = f.rules.firstOrNull { it.matches(app, w, isPhantom) }
    val mode = matched?.outcome ?: TriFilter.Show
    val childViews = w.children
        .map { classifyWindow(app, it, f, isPhantom = false) }
        .sortedBy(::modeOrder)
    return WindowView(w, mode, childViews, firingRule = matched)
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
fun World.filteredSwitcherSnapshot(
    filters: FilteringRules,
    pinning: PinningRules = PinningRules(),
    currentSpaceOnly: Boolean = false,
    visibleSpaceIds: List<Long> = emptyList(),
): SwitcherSnapshot {
    val fs = filteredSnapshot(filters, pinning, currentSpaceOnly, visibleSpaceIds)
    fun toEntry(av: AppView): AppEntry {
        val visible = av.windows.filter { it.mode != TriFilter.Hide }
        // `shownWindowCount` now counts the leading prefix of the navigable
        // (DFS-flat) list that sits under Show-classified roots — pinned
        // children inherit their root's scope. Demote-classified children of
        // a Show root therefore stay in the Shown range (cmd+` visits them);
        // children of a Demote root stay in All-only. Simple rule: walk
        // leading Show top-level views and sum (1 + kept descendants).
        val shownTop = visible.count { it.mode == TriFilter.Show }
        val demotedIds = HashSet<WindowId>()
        for (v in visible) collectDemotedIds(v, demotedIds)
        return AppEntry(
            app = av.app,
            windows = visible.map { it.toSwitcherWindow() },
            shownTopWindowCount = shownTop,
            demotedWindowIds = demotedIds,
            windowRecency = log.windowOrder(av.app.pid),
        )
        // shownWindowCount is a derived property on AppEntry now (size of
        // shownNavigableWindows) — no need to pre-compute it here.
    }
    return SwitcherSnapshot(
        withWindows = fs.show.map(::toEntry),
        windowless = fs.demote.map(::toEntry),
    )
}

/**
 * Project a classified [WindowView] back to a plain [Window] for the
 * switcher, propagating classification into the nested child tree:
 *  - `Hide` children are dropped (the switcher never renders them).
 *  - `Show` and `Demote` children are kept; order already reflects
 *    `classifyWindow`'s `Show before Demote before Hide` sort, so a
 *    demoted child naturally appears after its sibling Show children
 *    under the same parent.
 *
 * Caller is responsible for filtering Hide *roots* before invoking this —
 * the function asserts only that the receiver itself is being rendered.
 */
private fun WindowView.toSwitcherWindow(): Window {
    val keptChildren = children
        .filter { it.mode != TriFilter.Hide }
        .map { it.toSwitcherWindow() }
    return window.copy(children = keptChildren)
}

/** Walk [v] and its non-Hide descendants, adding ids of Demote-classified
 *  windows to [out]. Used to seed `AppEntry.demotedWindowIds`. */
private fun collectDemotedIds(v: WindowView, out: MutableSet<WindowId>) {
    if (v.mode == TriFilter.Demote) out.add(v.window.id)
    for (c in v.children) if (c.mode != TriFilter.Hide) collectDemotedIds(c, out)
}
