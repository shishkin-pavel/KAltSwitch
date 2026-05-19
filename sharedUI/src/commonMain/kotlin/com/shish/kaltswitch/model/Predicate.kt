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
 * stages — once for each real window, then for a synthetic "windowless-app"
 * stand-in if the app turned up empty — and this predicate is the only way
 * for a rule to address the second stage. On real-window evaluations it is
 * always `false`; on phantom evaluations always `true`. This makes it a
 * clean replacement for the old `windowlessApps` fallback toggle.
 *
 * Note: the `isPhantom` arg to [Predicate.matches] is overloaded — the
 * classifier passes `true` for the windowless-app stand-in but never for
 * cross-space CG-phantom rows. CG phantoms therefore look like ordinary
 * windows to the rule chain and should be addressed via the CG-side
 * predicates below ([IsOnscreenPredicate] etc.) rather than this one.
 */
@Serializable
@SerialName("noVisibleWindows")
data class NoVisibleWindowsPredicate(
    override val enabled: Boolean = true,
    override val inverted: Boolean = false,
) : Predicate

// ─────────────── CG-only window predicates (phantom rows) ───────────────
//
// These five address fields populated only by `CGWindowListWatcher` —
// for AX-derived windows the underlying value is `null` and the predicate
// evaluates to `false`. That gives rules a clean "applies to CG phantoms
// only" scope without needing a source discriminator: a rule mixing
// CG-only predicates with [BundleIdPredicate]/[AppNamePredicate] still
// composes correctly because the AX side returns `false` on the first
// CG predicate and short-circuits the AND.

/** `kCGWindowIsOnscreen`. The key discriminator between "real visible
 *  window" and "hidden helper popover the app keeps cached". Combine
 *  with [IsOnVisibleSpacePredicate] to avoid hiding legitimate cross-
 *  space rows which also report `false`. */
@Serializable
@SerialName("isOnscreen")
data class IsOnscreenPredicate(
    override val enabled: Boolean = true,
    override val inverted: Boolean = false,
) : Predicate

/** True iff any of the window's [Window.spaceIds] is in the WorldStore's
 *  current `visibleSpaceIds`. Pre-computed in the Swift enumerator so
 *  predicate evaluation stays ambient-context-free. */
@Serializable
@SerialName("isOnVisibleSpace")
data class IsOnVisibleSpacePredicate(
    override val enabled: Boolean = true,
    override val inverted: Boolean = false,
) : Predicate

/** `kCGWindowLayer`. Zero = regular app window; positive layers cover
 *  status items, the Dock tile, menubar overlays. Numeric so a rule can
 *  say "anything with layer > 0 is decoration, hide it". */
@Serializable
@SerialName("cgLayer")
data class CgLayerPredicate(
    override val enabled: Boolean = true,
    override val inverted: Boolean = false,
    val op: NumberOp = NumberOp.Gt,
    val value: Double = 0.0,
) : Predicate

/** `kCGWindowAlpha` (0–1). Zero-alpha entries are usually stub windows. */
@Serializable
@SerialName("cgAlpha")
data class CgAlphaPredicate(
    override val enabled: Boolean = true,
    override val inverted: Boolean = false,
    val op: NumberOp = NumberOp.Lte,
    val value: Double = 0.0,
) : Predicate

/** `kCGWindowOwnerName`. Matches the WindowServer's view of the owner
 *  process (CFBundleName for user apps, a fixed string for system
 *  services like "Window Server" / "Dock" / "Control Center"). For
 *  user apps it overlaps with [AppNamePredicate]; for system services
 *  it's the only way to address them (those processes don't appear
 *  in `NSWorkspace.runningApplications` and have no `App` record). */
@Serializable
@SerialName("ownerName")
data class OwnerNamePredicate(
    override val enabled: Boolean = true,
    override val inverted: Boolean = false,
    val op: StringOp = StringOp.Eq,
    val value: String = "",
) : Predicate

/** True iff the window has at least one entry in [Window.spaceIds]. The
 *  list is populated via `CGSCopySpacesForWindows`; an empty list means
 *  CGS didn't return a space for this window — typically a menubar-shadow
 *  helper, a cached popover, or some other WindowServer-internal stub.
 *
 *  Crucially this disambiguates "[isOnVisibleSpace]=false because the
 *  window is on another space" (`spaceIds` non-empty, just doesn't
 *  intersect the visible set) from "...because we have no space data"
 *  (`spaceIds` empty). A rule pattern that wants to drop the latter but
 *  keep the former needs both predicates:
 *
 *  ```
 *  IsOnscreenPredicate(inverted = true)         // not visible right now
 *  HasSpaceIdsPredicate(inverted = true)        // and no space data either
 *  → Hide
 *  ```
 *
 *  Returns `false` on AX-derived rows where `spaceIds` is unset (the
 *  field is `List<Long>`, not nullable; AX rows ship an empty list when
 *  the `_AXUIElementGetWindow` resolver failed, so this predicate also
 *  catches that edge case — which is the right behaviour: a window with
 *  no resolvable CGWindowID has no business in the switcher). */
@Serializable
@SerialName("hasSpaceIds")
data class HasSpaceIdsPredicate(
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
    // CG-only predicates: short-circuit to `false` when the underlying
    // field is null (i.e. the row isn't CG-derived). Done **before** the
    // inverted XOR — otherwise an `inverted=true` invocation would silently
    // match every AX row, which is how a CG-targeted "hide menubar
    // shadows" rule once managed to hide every test fixture's windows
    // and broke the suite. The semantic we want here is "predicate
    // doesn't apply to this row → don't fire it regardless of intent".
    when (this) {
        is IsOnscreenPredicate -> if (window.isOnscreen == null) return false
        is IsOnVisibleSpacePredicate -> if (window.isOnVisibleSpace == null) return false
        is CgLayerPredicate -> if (window.cgLayer == null) return false
        is CgAlphaPredicate -> if (window.cgAlpha == null) return false
        is OwnerNamePredicate -> if (window.ownerName == null) return false
        // HasSpaceIdsPredicate intentionally has no applicability check:
        // spaceIds is non-nullable on both sources, and "no space data"
        // is a meaningful question for AX rows too (when
        // _AXUIElementGetWindow failed to resolve a CGWindowID).
        else -> {}
    }
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
        // CG-only fields are nullable; AX-derived rows have them all null.
        // Convention: a CG predicate against a null underlying value
        // evaluates to `false` (i.e. "doesn't apply"). That makes any
        // rule whose predicate list includes a CG predicate inert
        // against AX rows — exactly the scoping we want.
        is IsOnscreenPredicate -> window.isOnscreen == true
        is IsOnVisibleSpacePredicate -> window.isOnVisibleSpace == true
        is CgLayerPredicate -> matchNumber(window.cgLayer?.toDouble(), op, value)
        is CgAlphaPredicate -> matchNumber(window.cgAlpha, op, value)
        is OwnerNamePredicate -> matchString(window.ownerName.orEmpty(), op, value)
        is HasSpaceIdsPredicate -> window.spaceIds.isNotEmpty()
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
