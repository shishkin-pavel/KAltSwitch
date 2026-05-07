package com.shish.kaltswitch.model

import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * One badge-rule. The rule's three predicates — app name, bundle id, and
 * the main window's title — are AND-ed together; when all match, the
 * switcher overlay paints a [text]-coloured pill at the top-right of the
 * icon (above the dock-badge notification slot, see SwitcherOverlay.AppCell).
 *
 * Each predicate is "skipped" when its value is empty and its op isn't
 * [StringOp.IsEmpty] — that way an unfilled row doesn't accidentally
 * exclude every app. So a rule with only `appName == Firefox` filled in
 * matches every Firefox window regardless of bundle id or title.
 *
 * For the title predicate with [StringOp.Regex], [text] supports `$1`..`$9`
 * to interpolate capture groups (`$0` = full match). Use `$$` to emit a
 * literal `$`. So a single rule with title pattern ` — (.+)$` and badge
 * text `$1` tags every Firefox profile window with its profile name.
 *
 * [colorRgb] is opaque 0xRRGGBB; the renderer auto-picks black or white
 * text based on the colour's luminance.
 */
@Serializable
data class BadgeRule(
    val id: String,
    val name: String = "",
    val enabled: Boolean = true,
    val appNameOp: StringOp = StringOp.Eq,
    val appNameValue: String = "",
    val bundleIdOp: StringOp = StringOp.Eq,
    val bundleIdValue: String = "",
    val titleOp: StringOp = StringOp.Regex,
    val titleValue: String = "",
    val text: String = "",
    val colorRgb: Long = DefaultBadgeColorRgb,
)

const val DefaultBadgeColorRgb: Long = 0x007AFFL    // macOS systemBlue

/**
 * Persisted list of badge rules. Wrapping the list keeps room for
 * future rule-list-level fields (global enable, default colour) without
 * touching every config-loader site.
 */
@Serializable
data class BadgeRules(
    val rules: List<BadgeRule> = emptyList(),
)

/**
 * Apply the rule list to a running [app] and the title of its main window.
 * `title` may be empty when the app has no main window (just-launched, or
 * AX permissions denied) — title-typed predicates are evaluated against
 * the empty string in that case. Returns the first enabled rule's
 * resolved badge — text already has any `$N` references substituted with
 * regex capture groups (regex op only). Returns `null` when no rule
 * matches or every match resolves to an empty string after substitution.
 */
fun BadgeRules.evaluate(app: App, title: String): ResolvedBadge? {
    for (rule in rules) {
        if (!rule.enabled) continue
        val resolved = rule.match(app, title) ?: continue
        if (resolved.text.isEmpty()) continue
        return resolved
    }
    return null
}

data class ResolvedBadge(val text: String, val colorRgb: Long)

private fun BadgeRule.match(app: App, title: String): ResolvedBadge? {
    if (!matchOptional(app.name, appNameOp, appNameValue)) return null
    if (!matchOptional(app.bundleId.orEmpty(), bundleIdOp, bundleIdValue)) return null
    val resolvedText = matchTitleAndExpand(title) ?: return null
    return ResolvedBadge(resolvedText, colorRgb)
}

/**
 * Plain string predicate. An empty [value] paired with anything other than
 * [StringOp.IsEmpty] means "this row was left blank" — treated as skip so
 * an unfilled predicate row doesn't sink the whole rule.
 */
private fun matchOptional(field: String, op: StringOp, value: String): Boolean {
    if (value.isEmpty() && op != StringOp.IsEmpty) return true
    return when (op) {
        StringOp.Eq -> field == value
        StringOp.Contains -> field.contains(value)
        StringOp.Regex -> compileOrNull(value)?.containsMatchIn(field) ?: false
        StringOp.IsEmpty -> field.isEmpty()
    }
}

/**
 * Title predicate plus capture-group expansion in one step. Returns the
 * substituted badge text on match (regex captures expanded; non-regex ops
 * return [text] unchanged), or `null` on no match. Empty pattern + non-
 * IsEmpty op is "skip" — returns [text] verbatim.
 */
private fun BadgeRule.matchTitleAndExpand(title: String): String? {
    if (titleValue.isEmpty() && titleOp != StringOp.IsEmpty) return text
    return when (titleOp) {
        StringOp.Eq -> if (title == titleValue) text else null
        StringOp.Contains -> if (title.contains(titleValue)) text else null
        StringOp.IsEmpty -> if (title.isEmpty()) text else null
        StringOp.Regex -> {
            val rx = compileOrNull(titleValue) ?: return null
            val m = rx.find(title) ?: return null
            expandCaptures(text, m)
        }
    }
}

private fun compileOrNull(pattern: String): Regex? = try {
    Regex(pattern)
} catch (_: Throwable) {
    null
}

/**
 * Replace `$0`..`$9` with the corresponding capture group from [match]
 * (`$0` = full match). `$$` emits a literal `$`. `$N` where N is out of
 * range is left as-is so a typo is visible rather than silently dropped.
 */
internal fun expandCaptures(template: String, match: MatchResult): String {
    if (template.isEmpty()) return template
    val sb = StringBuilder(template.length)
    var i = 0
    while (i < template.length) {
        val c = template[i]
        if (c == '$' && i + 1 < template.length) {
            val next = template[i + 1]
            if (next == '$') {
                sb.append('$')
                i += 2
                continue
            }
            if (next.isDigit()) {
                val idx = next.digitToInt()
                val g = match.groupValues.getOrNull(idx)
                if (g != null) {
                    sb.append(g)
                    i += 2
                    continue
                }
            }
        }
        sb.append(c)
        i++
    }
    return sb.toString()
}

/** Mint a fresh rule with a unique id, mirroring `FilteringRulesPanel.newId()`. */
fun newBadgeRule(): BadgeRule = BadgeRule(id = "b-" + Random.nextLong().toULong().toString(16))
