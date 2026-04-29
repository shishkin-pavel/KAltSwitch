package com.shish.kaltswitch.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** String-comparison operator. `IsEmpty` is the only nullary op. */
@Serializable
enum class StringOp { Eq, Contains, Regex, IsEmpty }

/** Numeric-comparison operator (window dimensions). */
@Serializable
enum class NumberOp { Gt, Gte, Lt, Lte }

/** UI-friendly mirror of [AppActivationPolicy] used as predicate value. */
@Serializable
enum class PolicyValue { Regular, Accessory, Prohibited }

/**
 * One predicate inside a [Rule]. Sealed-polymorphic so each kind carries
 * its own typed value(s); kotlinx-serialization writes a `type` discriminator
 * derived from `@SerialName`.
 *
 * Two rule-wide flags:
 * - [enabled]: switched off in the UI; the rule treats the predicate as if
 *   absent. Default `true` so newly-added predicates take effect immediately.
 * - [inverted]: logical NOT around the result. Composes with `Eq` / `Contains`
 *   / `Regex` / `IsEmpty` so the user doesn't need a separate `!=` operator.
 */
@Serializable
sealed interface Predicate {
    val enabled: Boolean
    val inverted: Boolean
}

// ─────────────── App-side predicates ───────────────

@Serializable
@SerialName("bundleId")
data class BundleIdPredicate(
    override val enabled: Boolean = true,
    override val inverted: Boolean = false,
    val op: StringOp = StringOp.Eq,
    val value: String = "",
) : Predicate

@Serializable
@SerialName("appName")
data class AppNamePredicate(
    override val enabled: Boolean = true,
    override val inverted: Boolean = false,
    val op: StringOp = StringOp.Eq,
    val value: String = "",
) : Predicate

@Serializable
@SerialName("isHidden")
data class IsHiddenPredicate(
    override val enabled: Boolean = true,
    override val inverted: Boolean = false,
) : Predicate

@Serializable
@SerialName("isFinishedLaunching")
data class IsFinishedLaunchingPredicate(
    override val enabled: Boolean = true,
    override val inverted: Boolean = false,
) : Predicate

@Serializable
@SerialName("activationPolicy")
data class ActivationPolicyPredicate(
    override val enabled: Boolean = true,
    override val inverted: Boolean = false,
    val value: PolicyValue = PolicyValue.Regular,
) : Predicate

// ─────────────── Window-side predicates ───────────────

@Serializable
@SerialName("title")
data class TitlePredicate(
    override val enabled: Boolean = true,
    override val inverted: Boolean = false,
    val op: StringOp = StringOp.Eq,
    val value: String = "",
) : Predicate

@Serializable
@SerialName("role")
data class RolePredicate(
    override val enabled: Boolean = true,
    override val inverted: Boolean = false,
    val op: StringOp = StringOp.Eq,
    val value: String = "",
) : Predicate

@Serializable
@SerialName("subrole")
data class SubrolePredicate(
    override val enabled: Boolean = true,
    override val inverted: Boolean = false,
    val op: StringOp = StringOp.Eq,
    val value: String = "",
) : Predicate

@Serializable
@SerialName("isMinimized")
data class IsMinimizedPredicate(
    override val enabled: Boolean = true,
    override val inverted: Boolean = false,
) : Predicate

@Serializable
@SerialName("isFullscreen")
data class IsFullscreenPredicate(
    override val enabled: Boolean = true,
    override val inverted: Boolean = false,
) : Predicate

@Serializable
@SerialName("isFocused")
data class IsFocusedPredicate(
    override val enabled: Boolean = true,
    override val inverted: Boolean = false,
) : Predicate

@Serializable
@SerialName("isMain")
data class IsMainPredicate(
    override val enabled: Boolean = true,
    override val inverted: Boolean = false,
) : Predicate

@Serializable
@SerialName("width")
data class WidthPredicate(
    override val enabled: Boolean = true,
    override val inverted: Boolean = false,
    val op: NumberOp = NumberOp.Gte,
    val value: Double = 0.0,
) : Predicate

@Serializable
@SerialName("height")
data class HeightPredicate(
    override val enabled: Boolean = true,
    override val inverted: Boolean = false,
    val op: NumberOp = NumberOp.Gte,
    val value: Double = 0.0,
) : Predicate

@Serializable
@SerialName("area")
data class AreaPredicate(
    override val enabled: Boolean = true,
    override val inverted: Boolean = false,
    val op: NumberOp = NumberOp.Gte,
    val value: Double = 0.0,
) : Predicate

/**
 * App-level predicate: true iff the app has no surviving (Show or Demote)
 * windows after rule evaluation. The classifier evaluates rules in two
 * stages — once for each real window, then for a synthetic "phantom" window
 * if the app turned up empty — and this predicate is the only way for a
 * rule to address the second stage. On real-window evaluations it is
 * always `false`; on phantom evaluations always `true`. This makes it a
 * clean replacement for the old `windowlessApps` fallback toggle.
 */
@Serializable
@SerialName("noVisibleWindows")
data class NoVisibleWindowsPredicate(
    override val enabled: Boolean = true,
    override val inverted: Boolean = false,
) : Predicate

// ─────────────── Evaluation ───────────────

/**
 * Evaluate this predicate against an (app, window) pair. [isPhantom] = true
 * means the classifier is asking what to do with a synthetic stand-in window
 * for an app that has no visible real windows — used to decide the section
 * for windowless / all-windows-hidden apps.
 *
 * Always total — unknown / nullable string fields are coerced to `""`,
 * missing numeric dimensions evaluate to `false`, an invalid regex pattern
 * yields `false`. The phantom window has empty title, null role/subrole,
 * all booleans `false`, and null dimensions, so each window-side predicate
 * keeps a sensible interpretation against it.
 */
fun Predicate.matches(app: App, window: Window, isPhantom: Boolean): Boolean {
    val raw = when (this) {
        is BundleIdPredicate -> matchString(app.bundleId.orEmpty(), op, value)
        is AppNamePredicate -> matchString(app.name, op, value)
        is IsHiddenPredicate -> app.isHidden
        is IsFinishedLaunchingPredicate -> app.isFinishedLaunching
        is ActivationPolicyPredicate -> app.activationPolicy.toPolicyValue() == value
        is TitlePredicate -> matchString(window.title, op, value)
        is RolePredicate -> matchString(window.role.orEmpty(), op, value)
        is SubrolePredicate -> matchString(window.subrole.orEmpty(), op, value)
        is IsMinimizedPredicate -> window.isMinimized
        is IsFullscreenPredicate -> window.isFullscreen
        is IsFocusedPredicate -> window.isFocused
        is IsMainPredicate -> window.isMain
        is WidthPredicate -> matchNumber(window.width, op, value)
        is HeightPredicate -> matchNumber(window.height, op, value)
        is AreaPredicate -> {
            val w = window.width
            val h = window.height
            val area = if (w != null && h != null) w * h else null
            matchNumber(area, op, value)
        }
        is NoVisibleWindowsPredicate -> isPhantom
    }
    return raw xor inverted
}

private fun matchString(value: String, op: StringOp, expected: String): Boolean = when (op) {
    StringOp.Eq -> value == expected
    StringOp.Contains -> if (expected.isEmpty()) true else value.contains(expected)
    StringOp.Regex -> compileRegexOrNull(expected)?.containsMatchIn(value) ?: false
    StringOp.IsEmpty -> value.isEmpty()
}

private fun matchNumber(value: Double?, op: NumberOp, threshold: Double): Boolean {
    if (value == null || value.isNaN()) return false
    return when (op) {
        NumberOp.Gt -> value > threshold
        NumberOp.Gte -> value >= threshold
        NumberOp.Lt -> value < threshold
        NumberOp.Lte -> value <= threshold
    }
}

private fun compileRegexOrNull(pattern: String): Regex? = try {
    Regex(pattern)
} catch (_: Throwable) {
    null
}

private fun AppActivationPolicy.toPolicyValue(): PolicyValue = when (this) {
    AppActivationPolicy.Regular -> PolicyValue.Regular
    AppActivationPolicy.Accessory -> PolicyValue.Accessory
    AppActivationPolicy.Prohibited -> PolicyValue.Prohibited
}
