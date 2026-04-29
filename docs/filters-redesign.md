# Rule-based filters — design

Status: **approved**, implementation in progress. See §9 for the resolved
open questions.

## 1. Goal

Replace the current eight-toggle `Filters` data class with an **ordered list
of user-defined rules** ("если `bundleId == com.apple.finder` И `title == ''`
→ `Hide`"), plus a small set of fallback toggles for "everything else". The
inspector grows a rule editor; the switcher and inspector continue to share
exactly one classification pipeline.

The current `Filters` is a **fixed schema** of eight predicates with global
TriFilter outcomes. It can't express user intent that combines properties
("Finder + empty-title", not "Finder" or "empty-title" globally) and the set
of predicates is hard-coded.

## 2. End-state user model

A **Rule** is:

- An ordered list of **predicates**, each of which is independently
  *enabled*, optionally *inverted* (`not`), and tests one window-or-app
  property. Disabled predicates do not contribute. Enabled predicates are
  ANDed.
- An **outcome** — `Show`, `Demote`, or `Hide`.

Rules are evaluated **per-window**. The first rule whose enabled-predicate
conjunction matches a window decides that window's mode (see §3 for
first-match-wins reasoning). Predicates can read either app properties
(every window of that app sees the same value) or window properties.

There are **no separate fallback toggles**. The classifier resolves an
app's section like this:

1. Walk rules per real window — first-match-wins, default `Show`. The
   `noVisibleWindows` predicate evaluates `false` on real windows.
2. Derive the app's section from those modes: any `Show` window → `Show`,
   else any `Demote` → `Demote`.
3. Otherwise (the app has zero real windows, or every real window came
   out `Hide`), synthesise a **phantom** window with default field values
   and walk the rules against it. `noVisibleWindows` evaluates `true`
   here. The phantom's outcome becomes the app's section. If no rule
   matches, the default is `Show` — the whole pipeline is opt-in,
   nothing is demoted or hidden without an explicit user rule.

This collapses the old two-subsystem (rules + fallback toggles) design
into one. Anything the fallbacks did is expressible as a normal rule:

- `windowlessApps = Demote` ≡ rule `noVisibleWindows → Demote`
- `accessoryApps = Hide` ≡ rule `activationPolicy == Accessory → Hide`
  (matches both real windows and the phantom, so the app's window
  classifications and section both turn into Hide)
- `hiddenApps = Hide` ≡ rule `isHidden → Hide`

The user's classic "explicit beats fallback" example is now just
first-match-wins:

```
1. activationPolicy == Accessory  AND  role != ""   → Show
2. activationPolicy == Accessory                     → Hide
```

Window matches Rule 1 → `Show`; Rule 2 never gets a turn. App's section
is `Show` because at least one window is `Show`.

### 2.1 Predicate catalogue (proposed v1)

App-side (the value is the same for every window of the app):

- `bundleId` — `==` / `!=` / `contains` / `regex`
- `appName` — `==` / `!=` / `contains` / `regex`
- `isHidden` — boolean (cmd+H state)
- `activationPolicy` — `==` / `!=` `Regular | Accessory | Prohibited`
- `isFinishedLaunching` — boolean

Window-side:

- `title` — `==` / `!=` / `contains` / `regex` / `isEmpty`
- `role` — `==` / `!=` (e.g. `AXWindow`, `AXSheet`)
- `subrole` — `==` / `!=` (e.g. `AXStandardWindow`, `AXDialog`)
- `isMinimized` — boolean
- `isFullscreen` — boolean
- `isFocused` — boolean
- `isMain` — boolean
- `width` — `>` / `>=` / `<` / `<=` (px)
- `height` — `>` / `>=` / `<` / `<=` (px)
- `area` — `>` / `>=` / `<` / `<=` (px², shorthand for `width*height`)

App / context-side:

- `noVisibleWindows` — true when the classifier is evaluating the synthetic
  phantom window for an app with no surviving (Show / Demote) real windows.
  Always `false` against real windows. This is the only way for a rule to
  participate in app-level fallback decisions.

`inverted` is a separate UI affordance from `!=` because it composes nicely
with `regex` / `contains` / `isEmpty` (e.g. *not-isEmpty* is much clearer
than its absence).

**Null handling for string predicates**: when a window's `title`, `role`,
`subrole`, or an app's `bundleId` / `appName` is `null` (or absent), the
matcher substitutes the empty string `""` before evaluating `==` / `!=` /
`contains` / `regex` / `isEmpty`. So `title == ""` matches both
explicitly-empty and AX-missing titles; `bundleId == ""` matches apps with
no bundle ID at all. This keeps every predicate total — there's no third
"unknown" outcome to reason about in the UI.

## 3. Evaluation semantics

**First-match-wins, per window.** A rule with no enabled predicates matches
nothing (it's an inert draft, not a wildcard). Both real and phantom
windows default to `Show` when no rule matches — the entire system is
opt-in, nothing is demoted or hidden without an explicit user rule.

Rationale: first-match-wins matches the firewall / mail-rules mental model
("rules are processed in order") and is order-explainable. Combined with
the phantom-window mechanism (§2), every classification — per-window and
app-level — runs through the same rule chain. There is no separate
fallback subsystem to reason about.

## 4. Schema

Polymorphic sealed class hierarchy, kotlinx-serialization with type
discriminator. Sketch:

```kotlin
@Serializable
data class Rule(
    val id: String,                  // stable for reorder
    val name: String = "",           // user-editable label, optional
    val predicates: List<Predicate>,
    val outcome: TriFilter,
    val enabled: Boolean = true,     // top-level kill switch per rule
)

@Serializable
sealed interface Predicate {
    val enabled: Boolean
    val inverted: Boolean
}

@Serializable @SerialName("bundleId")
data class BundleIdPredicate(
    override val enabled: Boolean = true,
    override val inverted: Boolean = false,
    val op: StringOp,                // Eq, Contains, Regex
    val value: String,
) : Predicate

@Serializable @SerialName("isMinimized")
data class IsMinimizedPredicate(
    override val enabled: Boolean = true,
    override val inverted: Boolean = false,
) : Predicate

// ... one variant per predicate kind from §2.1
```

Top-level container — picked the name `FilteringRules`:

```kotlin
@Serializable
data class FilteringRules(
    val rules: List<Rule> = emptyList(),
)
```

A single ordered list. No fallback fields — they're now expressible as
ordinary rules using the `noVisibleWindows` predicate (§2).

The flow-publishing API in `WorldStore` keeps the name `filters` for
continuity (no Swift-side rename ripple) but the type underneath is
`FilteringRules`.

### 4.1 Migration

`AppConfig.schemaVersion` is at `3`. Each redesign so far drops fields the
new code doesn't recognise — `kotlinx.serialization { ignoreUnknownKeys =
true }` makes that a no-op for upgrades. Defaults populate the new
shape. We accept a clean slate over migrating user-tuned values; the user
is on a development branch and can re-author rules.

## 5. Inspector UI

The settings sidebar (currently 260 dp fixed) widens to ~320 dp by default
and grows a **draggable separator** between the sidebar and the inspector
when the inspector is open. Dragging it updates **both** stored widths in
lockstep — `windowFrame.width` (the sidebar) and `inspectorWidth` (the
right pane). The window's outer width = sidebar + inspector when shown.

Inside the sidebar, the existing Settings panel stays at the top; below
it the "Filtering rules" panel is a vertical list of **rule cards** with
an "Add rule" button at the bottom. Each rule card shows:

- Drag handle (left) — drag to reorder.
- Rule-enable toggle (top-right corner — A/B-test a rule by toggling it
  off without losing the predicates).
- Editable name field; placeholder is the auto-generated summary
  (e.g. `bundleId == com.apple.finder · title == ''`).
- Outcome segmented control (`Show` / `Demote` / `Hide`) — colour-coded
  to match the existing TriRow chips.
- One row per predicate, plus an `+ predicate` button. Each predicate row:
  - enable checkbox / chip
  - `not` toggle
  - field name (e.g. "Bundle ID")
  - operator dropdown
  - value field (text / number, depending on op)
  - delete-predicate `×` button on hover
- Delete-rule `×` (top-right, on hover, with confirm).

Compact density — predicates render as one line per predicate. With many
disabled predicates or empty rules, the card collapses to a one-line
summary; click expands.

(Detailed pixel mockup is out of scope for this doc — it'll grow during
implementation.)

## 6. Performance

Per-window evaluation = `O(R · P)` where R = rules, P = avg-enabled
predicates per rule. With R ≤ ~30 and P ≤ ~5, every active window is
evaluated in tens of comparisons. Regex predicates **pre-compile** their
pattern at rule load / save (cache `Regex` instance on the predicate or in
a parallel structure indexed by rule id). Other ops are constant-time
string / number comparisons.

The whole `filteredSnapshot` recomputes on `world` or `filters` change
(`remember(world, filters)` on the Compose side), same as today.

## 7. Out of scope for v1

- Predicate **OR** within a rule (workaround: two rules with the same
  outcome).
- **Rule groups** / nested logic.
- **Live preview** of how many windows each rule currently catches —
  desirable, deferred until the basic editor lands.
- **Default seed rules** — empty until we tune a useful set on real
  workloads, as the user requested.
- **Import / export** of rule sets — JSON file already round-trips through
  `ConfigStore`, so power users can hand-edit; a UI is post-MVP.
- **Per-rule "stop on match" toggle** — first-match-wins is the only mode
  for v1.

## 9. Resolved questions

1. **First-match-wins** — confirmed.
2. **Fallback toggles** — removed entirely (revision 2). Their behaviours
   are expressible as rules using the new `noVisibleWindows` predicate.
3. **Predicate catalogue** — §2.1 set + `noVisibleWindows`. `executablePath`
   / display ID can be added later.
4. **`area`** — kept as separate predicate.
5. **Rule names** — auto-generated default, user-replaceable.
6. **Migration** — clean break, no preservation of old field values.
7. **Per-rule `enabled` toggle** — kept (A/B-testing a rule).
8. **Editor location** — inside the existing settings sidebar, replacing
   today's filter panel. Sidebar widens; new draggable separator updates
   both `sidebarWidth` and `inspectorWidth` together (§5).
9. **Schema name** — picked `FilteringRules` for the container.
10. **Validation** — inline error; invalid predicate evaluates to `false`
    (never blocks save).
11. **Phantom-window default** — `Show` (revision 3). The entire
    classification pipeline is opt-in: a fresh install with no rules
    classifies every app as `Show`. Users author explicit rules to
    demote (`noVisibleWindows → Demote`) or hide (`activationPolicy ==
    Accessory → Hide`) anything they don't want in the switcher.
