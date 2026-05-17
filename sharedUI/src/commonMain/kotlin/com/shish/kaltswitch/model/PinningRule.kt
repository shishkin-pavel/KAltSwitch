package com.shish.kaltswitch.model

import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * One pinning rule. AND of [predicates], evaluated per top-level window. When
 * every enabled predicate matches, the window is "pinned" — re-parented as a
 * child of the same app's most recently activated non-pinned root window (see
 * `World.applyPinning`).
 *
 * Predicates re-use the [Predicate] machinery from [FilteringRules] so the
 * editor UI is the same (title / role / subrole / size / etc.). Unlike
 * filtering rules, there's no `outcome` — the rule itself is the outcome
 * (pin yes/no), and pin-target resolution is handled by the pinning pass.
 *
 * A rule with no enabled predicates is inert (matches nothing); this is the
 * default state of a freshly-created rule, so adding one is safe before the
 * user wires up its predicates.
 */
@Serializable
data class PinningRule(
    val id: String,
    val name: String = "",
    val enabled: Boolean = true,
    val predicates: List<Predicate> = emptyList(),
)

/** Persisted list of pinning rules. */
@Serializable
data class PinningRules(
    val rules: List<PinningRule> = emptyList(),
)

/** True iff every enabled predicate matches the (app, window) pair. The
 *  pinning pass only consults top-level windows, so `isPhantom = false`. */
fun PinningRule.matches(app: App, window: Window): Boolean {
    if (!enabled) return false
    val active = predicates.filter { it.enabled }
    if (active.isEmpty()) return false
    return active.all { it.matches(app, window, isPhantom = false) }
}

/** True iff any enabled rule matches — the window should be pinned. */
fun PinningRules.matches(app: App, window: Window): Boolean =
    rules.any { it.matches(app, window) }

/** Mint a fresh rule with a unique id, mirroring the pattern in
 *  [newBadgeRule] / `FilteringRulesPanel.newId()`. */
fun newPinningRule(): PinningRule =
    PinningRule(id = "p-" + Random.nextLong().toULong().toString(16))
