package com.shish.kaltswitch

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shish.kaltswitch.model.BadgeRule
import com.shish.kaltswitch.model.BadgeRules
import com.shish.kaltswitch.model.StringOp
import com.shish.kaltswitch.model.newBadgeRule

/**
 * Editor for the title-pattern → badge mapping. Each card has:
 *   - a name field (defaults to a synthesised summary if blank)
 *   - the title operator + value (Eq / Contains / Regex / IsEmpty)
 *   - the badge text (supports `$1`..`$9` capture groups when op is Regex)
 *   - a colour swatch + 6-digit hex field
 *   - reorder ▲▼ and delete ×
 * Mirrors the structural choices in [FilteringRulesPanel] so the two
 * Settings tabs feel of a piece.
 */
@Composable
fun BadgeRulesPanel(
    rules: BadgeRules,
    onChange: (BadgeRules) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((index, rule) in rules.rules.withIndex()) {
            BadgeRuleCard(
                rule = rule,
                onChange = { updated ->
                    onChange(rules.copy(rules = rules.rules.toMutableList().also { it[index] = updated }))
                },
                onDelete = {
                    onChange(rules.copy(rules = rules.rules.toMutableList().also { it.removeAt(index) }))
                },
                onMoveUp = if (index == 0) null else {
                    { onChange(rules.copy(rules = rules.rules.swapped(index, index - 1))) }
                },
                onMoveDown = if (index == rules.rules.lastIndex) null else {
                    { onChange(rules.copy(rules = rules.rules.swapped(index, index + 1))) }
                },
            )
        }
        NativeButton(
            label = "+ badge",
            onClick = { onChange(rules.copy(rules = rules.rules + newBadgeRule())) },
            modifier = Modifier.fillMaxWidth(),
        )
        BadgeHelpFooter()
    }
}

@Composable
private fun BadgeRuleCard(
    rule: BadgeRule,
    onChange: (BadgeRule) -> Unit,
    onDelete: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(AppPalette.groupBg)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Header — reorder, name, enable, delete.
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ArrowChip("▲", enabled = onMoveUp != null) { onMoveUp?.invoke() }
            ArrowChip("▼", enabled = onMoveDown != null) { onMoveDown?.invoke() }
            BadgePreview(text = rule.text.ifEmpty { "?" }, colorRgb = rule.colorRgb)
            NativeTextField(
                value = rule.name,
                onValueChange = { onChange(rule.copy(name = it)) },
                placeholder = ruleSummary(rule).ifBlank { "Unnamed badge" },
                modifier = Modifier.weight(1f),
            )
            NativeToggle(
                checked = rule.enabled,
                onCheckedChange = { onChange(rule.copy(enabled = it)) },
            )
            DeleteChip(onClick = onDelete)
        }

        // App-name match — narrow to a specific app first.
        StringPredicateRow(
            label = "Match app name",
            op = rule.appNameOp,
            value = rule.appNameValue,
            placeholder = stringValuePlaceholder(rule.appNameOp, "Firefox"),
            onChange = { op, value ->
                onChange(rule.copy(appNameOp = op, appNameValue = value))
            },
        )

        // Bundle-id match — exact string from `NSRunningApplication.bundleIdentifier`.
        StringPredicateRow(
            label = "Match bundle ID",
            op = rule.bundleIdOp,
            value = rule.bundleIdValue,
            placeholder = stringValuePlaceholder(rule.bundleIdOp, "org.mozilla.firefox"),
            onChange = { op, value ->
                onChange(rule.copy(bundleIdOp = op, bundleIdValue = value))
            },
        )

        // Title match — last so the user reads it next to "Badge text"
        // (regex captures from here flow into $1..$9 there).
        StringPredicateRow(
            label = "Match title",
            op = rule.titleOp,
            value = rule.titleValue,
            placeholder = titleValuePlaceholder(rule.titleOp),
            onChange = { op, value ->
                onChange(rule.copy(titleOp = op, titleValue = value))
            },
        )

        // Badge text — what we paint on the icon.
        NativeRow(label = "Badge text") {
            NativeTextField(
                value = rule.text,
                onValueChange = { onChange(rule.copy(text = it)) },
                placeholder = if (rule.titleOp == StringOp.Regex) "e.g. \$1" else "e.g. Personal",
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Colour — swatch + 6-digit hex.
        NativeRow(label = "Colour") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(rgbToColor(rule.colorRgb)),
                )
                Box(Modifier.width(90.dp)) {
                    NativeTextField(
                        value = rule.colorRgb.toString(16).padStart(6, '0').uppercase(),
                        onValueChange = { raw ->
                            val cleaned = raw.trimStart('#').take(6).uppercase()
                            if (cleaned.length == 6) {
                                cleaned.toLongOrNull(16)?.let { onChange(rule.copy(colorRgb = it)) }
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * One labelled row holding a [StringOp] dropdown and a value text field.
 * Used three times per badge-rule card (app name, bundle id, title). The
 * value field is hidden for [StringOp.IsEmpty] since that op is nullary.
 */
@Composable
private fun StringPredicateRow(
    label: String,
    op: StringOp,
    value: String,
    placeholder: String,
    onChange: (StringOp, String) -> Unit,
) {
    NativeRow(label = label) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            StringOpDropdown(op) { newOp -> onChange(newOp, value) }
            if (op != StringOp.IsEmpty) {
                NativeTextField(
                    value = value,
                    onValueChange = { onChange(op, it) },
                    placeholder = placeholder,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun BadgePreview(text: String, colorRgb: Long) {
    val bg = rgbToColor(colorRgb)
    Box(
        Modifier
            .widthIn(min = 24.dp)
            .height(18.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(bg)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        NativeText(
            text,
            color = contrastingTextColor(colorRgb),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun StringOpDropdown(op: StringOp, onPick: (StringOp) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(AppPalette.controlTrack)
                .clickable { expanded = true }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            NativeText(
                stringOpLabel(op),
                color = AppPalette.textPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            NativeText("▾", color = AppPalette.textSecondary, fontSize = 9.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (option in StringOp.entries) {
                DropdownMenuItem(
                    text = { Text(stringOpLabel(option)) },
                    onClick = {
                        onPick(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ArrowChip(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    val bg = if (enabled) AppPalette.controlTrack else AppPalette.controlFill
    val fg = if (enabled) AppPalette.textPrimary else AppPalette.textSecondary
    Box(
        Modifier
            .size(20.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        NativeText(glyph, color = fg, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DeleteChip(onClick: () -> Unit) {
    Box(
        Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(AppPalette.controlTrack)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        NativeText("×", color = Color(0xFFE57373), fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BadgeHelpFooter() {
    NativeText(
        "Tip: with the Regex operator, use \$1..\$9 in Badge text to insert capture groups (e.g. pattern ` — (.+)\$` with text `\$1` tags every Firefox profile).",
        color = AppPalette.textSecondary,
        fontSize = 10.sp,
    )
}

private fun stringOpLabel(op: StringOp): String = when (op) {
    StringOp.Eq -> "=="
    StringOp.Contains -> "contains"
    StringOp.Regex -> "regex"
    StringOp.IsEmpty -> "is empty"
}

private fun titleValuePlaceholder(op: StringOp): String = when (op) {
    StringOp.Eq -> "exact title"
    StringOp.Contains -> "substring"
    StringOp.Regex -> "regex pattern"
    StringOp.IsEmpty -> ""
}

/**
 * Per-op placeholder text for the app-name / bundle-id rows. Eq +
 * Contains share an [example] hint so the user sees a concrete sample
 * (e.g. "Firefox") even before they pick an op.
 */
private fun stringValuePlaceholder(op: StringOp, example: String): String = when (op) {
    StringOp.Eq -> example
    StringOp.Contains -> example
    StringOp.Regex -> "regex pattern"
    StringOp.IsEmpty -> ""
}

private fun ruleSummary(rule: BadgeRule): String {
    val parts = buildList {
        addPredicateSummary("name", rule.appNameOp, rule.appNameValue)?.let { add(it) }
        addPredicateSummary("bundleId", rule.bundleIdOp, rule.bundleIdValue)?.let { add(it) }
        addPredicateSummary("title", rule.titleOp, rule.titleValue)?.let { add(it) }
    }
    val head = if (parts.isEmpty()) "Unfiltered badge" else parts.joinToString(" · ")
    val tail = if (rule.text.isNotEmpty()) " → '${rule.text}'" else ""
    return head + tail
}

/**
 * Compact "field op value" string for [ruleSummary]. Returns null when the
 * predicate is in its skip state — empty value with anything other than
 * [StringOp.IsEmpty] — so the summary doesn't get cluttered with rows the
 * user hasn't actually filled in.
 */
private fun addPredicateSummary(field: String, op: StringOp, value: String): String? {
    if (value.isEmpty() && op != StringOp.IsEmpty) return null
    return when (op) {
        StringOp.Eq -> "$field == '$value'"
        StringOp.Contains -> "$field contains '$value'"
        StringOp.Regex -> "$field ~= /$value/"
        StringOp.IsEmpty -> "$field is empty"
    }
}

/**
 * Pick black-on-light or white-on-dark for a given background RGB. WCAG
 * relative-luminance threshold of 0.5 is good enough for short tag pills —
 * we don't need contrast-ratio fidelity here, just a legible default for
 * whatever colour the user pastes in. Re-used by the live badge in the
 * switcher overlay so the editor preview matches what they'll see.
 */
internal fun contrastingTextColor(rgb: Long): Color {
    val r = ((rgb shr 16) and 0xFF) / 255.0
    val g = ((rgb shr 8) and 0xFF) / 255.0
    val b = (rgb and 0xFF) / 255.0
    val lum = 0.2126 * r + 0.7152 * g + 0.0722 * b
    return if (lum > 0.55) Color.Black else Color.White
}

private fun <T> List<T>.swapped(i: Int, j: Int): List<T> = toMutableList().also {
    val tmp = it[i]; it[i] = it[j]; it[j] = tmp
}
