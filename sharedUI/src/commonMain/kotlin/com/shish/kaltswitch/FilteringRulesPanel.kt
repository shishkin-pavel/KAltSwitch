package com.shish.kaltswitch

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shish.kaltswitch.model.ActivationPolicyPredicate
import com.shish.kaltswitch.model.AppNamePredicate
import com.shish.kaltswitch.model.AreaPredicate
import com.shish.kaltswitch.model.BundleIdPredicate
import com.shish.kaltswitch.model.FilteringRules
import com.shish.kaltswitch.model.HeightPredicate
import com.shish.kaltswitch.model.IsFinishedLaunchingPredicate
import com.shish.kaltswitch.model.IsFocusedPredicate
import com.shish.kaltswitch.model.IsFullscreenPredicate
import com.shish.kaltswitch.model.IsHiddenPredicate
import com.shish.kaltswitch.model.IsMainPredicate
import com.shish.kaltswitch.model.IsMinimizedPredicate
import com.shish.kaltswitch.model.NoVisibleWindowsPredicate
import com.shish.kaltswitch.model.NumberOp
import com.shish.kaltswitch.model.PolicyValue
import com.shish.kaltswitch.model.Predicate
import com.shish.kaltswitch.model.RolePredicate
import com.shish.kaltswitch.model.Rule
import com.shish.kaltswitch.model.StringOp
import com.shish.kaltswitch.model.SubrolePredicate
import com.shish.kaltswitch.model.TitlePredicate
import com.shish.kaltswitch.model.TriFilter
import com.shish.kaltswitch.model.WidthPredicate
import kotlin.random.Random

/**
 * Filter-rules editor surface. Lives in the Settings window's "Rules"
 * tab. Each rule renders as a `RuleCard`; "+ rule" appends a new empty
 * one. Reorder via small ▲/▼ buttons — drag-and-drop in Compose desktop
 * needs an extra dep that isn't worth pulling in for this list size.
 */
@Composable
fun FilteringRulesPanel(
    filters: FilteringRules,
    onChange: (FilteringRules) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((index, rule) in filters.rules.withIndex()) {
            RuleCard(
                rule = rule,
                onChange = { updated ->
                    onChange(filters.copy(rules = filters.rules.toMutableList().also { it[index] = updated }))
                },
                onDelete = {
                    onChange(filters.copy(rules = filters.rules.toMutableList().also { it.removeAt(index) }))
                },
                onMoveUp = if (index == 0) null else {
                    {
                        onChange(filters.copy(rules = filters.rules.swapped(index, index - 1)))
                    }
                },
                onMoveDown = if (index == filters.rules.lastIndex) null else {
                    {
                        onChange(filters.copy(rules = filters.rules.swapped(index, index + 1)))
                    }
                },
            )
        }
        AddRuleButton(onClick = {
            onChange(filters.copy(rules = filters.rules + Rule(id = newId())))
        })
    }
}

@Composable
private fun AddRuleButton(onClick: () -> Unit) {
    NativeButton(
        label = "+ rule",
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun RuleCard(
    rule: Rule,
    onChange: (Rule) -> Unit,
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
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ArrowButton("▲", enabled = onMoveUp != null, onClick = { onMoveUp?.invoke() })
            ArrowButton("▼", enabled = onMoveDown != null, onClick = { onMoveDown?.invoke() })
            NameField(
                value = rule.name,
                placeholder = ruleSummary(rule).ifBlank { "Unnamed rule" },
                onChange = { onChange(rule.copy(name = it)) },
                modifier = Modifier.weight(1f),
            )
            NativeToggle(
                checked = rule.enabled,
                onCheckedChange = { onChange(rule.copy(enabled = it)) },
            )
            IconButton(
                glyph = "×",
                onClick = onDelete,
                color = Color(0xFFE57373),
            )
        }

        // Outcome
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NativeText("Outcome:", color = AppPalette.textSecondary, fontSize = 11.sp)
            for (option in TriFilter.entries) {
                SegmentChip(option.name, selected = option == rule.outcome) {
                    onChange(rule.copy(outcome = option))
                }
            }
        }

        // Predicates
        for ((pIdx, predicate) in rule.predicates.withIndex()) {
            PredicateRow(
                predicate = predicate,
                onChange = { updated ->
                    onChange(rule.copy(
                        predicates = rule.predicates.toMutableList().also { it[pIdx] = updated },
                    ))
                },
                onDelete = {
                    onChange(rule.copy(
                        predicates = rule.predicates.toMutableList().also { it.removeAt(pIdx) },
                    ))
                },
            )
        }

        AddPredicateButton(onAdd = { kind ->
            onChange(rule.copy(predicates = rule.predicates + kind.create()))
        })
    }
}

@Composable
private fun NameField(
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    NativeTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier,
        placeholder = placeholder,
    )
}

@Composable
private fun PredicateRow(
    predicate: Predicate,
    onChange: (Predicate) -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Checkbox(
            checked = predicate.enabled,
            onCheckedChange = { onChange(predicate.withEnabled(it)) },
            colors = CheckboxDefaults.colors(checkedColor = AccentColor),
            modifier = Modifier.size(20.dp),
        )
        // Inversion (NOT) toggle, rendered as a small chip rather than a
        // checkbox so the predicate-row stays one line.
        Box(
            Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(if (predicate.inverted) Color(0xFFE57373) else AppPalette.controlTrack)
                .clickable { onChange(predicate.withInverted(!predicate.inverted)) }
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            NativeText(
                "not",
                color = if (predicate.inverted) Color.Black else AppPalette.textPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        NativeText(
            kindOf(predicate).label,
            color = AppPalette.textPrimary,
            fontSize = 11.sp,
            modifier = Modifier.width(80.dp),
        )
        PredicateOperatorAndValue(
            predicate = predicate,
            onChange = onChange,
            modifier = Modifier.weight(1f),
        )
        IconButton(glyph = "×", onClick = onDelete, color = Color(0xFFE57373))
    }
}

/**
 * Operator dropdown + value editor. The operator set and value-editor type
 * depend on the predicate kind; nullary kinds (booleans like IsMinimized)
 * render no operator/value at all — the row's enable+invert chips fully
 * specify the predicate.
 */
@Composable
private fun PredicateOperatorAndValue(
    predicate: Predicate,
    onChange: (Predicate) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (predicate) {
        is BundleIdPredicate -> StringEditor(predicate.op, predicate.value, modifier) { op, v ->
            onChange(predicate.copy(op = op, value = v))
        }
        is AppNamePredicate -> StringEditor(predicate.op, predicate.value, modifier) { op, v ->
            onChange(predicate.copy(op = op, value = v))
        }
        is TitlePredicate -> StringEditor(predicate.op, predicate.value, modifier) { op, v ->
            onChange(predicate.copy(op = op, value = v))
        }
        is RolePredicate -> StringEditor(predicate.op, predicate.value, modifier) { op, v ->
            onChange(predicate.copy(op = op, value = v))
        }
        is SubrolePredicate -> StringEditor(predicate.op, predicate.value, modifier) { op, v ->
            onChange(predicate.copy(op = op, value = v))
        }
        is WidthPredicate -> NumberEditor(predicate.op, predicate.value, modifier) { op, v ->
            onChange(predicate.copy(op = op, value = v))
        }
        is HeightPredicate -> NumberEditor(predicate.op, predicate.value, modifier) { op, v ->
            onChange(predicate.copy(op = op, value = v))
        }
        is AreaPredicate -> NumberEditor(predicate.op, predicate.value, modifier) { op, v ->
            onChange(predicate.copy(op = op, value = v))
        }
        is ActivationPolicyPredicate -> PolicyEditor(predicate.value, modifier) { v ->
            onChange(predicate.copy(value = v))
        }
        is IsHiddenPredicate,
        is IsFinishedLaunchingPredicate,
        is IsMinimizedPredicate,
        is IsFullscreenPredicate,
        is IsFocusedPredicate,
        is IsMainPredicate,
        is NoVisibleWindowsPredicate -> Box(modifier)   // nullary — nothing to edit
    }
}

@Composable
private fun StringEditor(
    op: StringOp,
    value: String,
    modifier: Modifier,
    onChange: (StringOp, String) -> Unit,
) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OpDropdown(
            label = stringOpLabel(op),
            options = StringOp.entries.map { it to stringOpLabel(it) },
            onPick = { onChange(it, value) },
        )
        if (op != StringOp.IsEmpty) {
            ValueTextField(
                value = value,
                onChange = { onChange(op, it) },
                keyboardType = KeyboardType.Text,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun NumberEditor(
    op: NumberOp,
    value: Double,
    modifier: Modifier,
    onChange: (NumberOp, Double) -> Unit,
) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OpDropdown(
            label = numberOpLabel(op),
            options = NumberOp.entries.map { it to numberOpLabel(it) },
            onPick = { onChange(it, value) },
        )
        ValueTextField(
            value = formatNumber(value),
            onChange = { it.toDoubleOrNull()?.let { d -> onChange(op, d) } },
            keyboardType = KeyboardType.Decimal,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PolicyEditor(
    value: PolicyValue,
    modifier: Modifier,
    onChange: (PolicyValue) -> Unit,
) {
    OpDropdown(
        label = value.name,
        options = PolicyValue.entries.map { it to it.name },
        onPick = onChange,
        modifier = modifier,
    )
}

@Composable
private fun <T> OpDropdown(
    label: String,
    options: List<Pair<T, String>>,
    onPick: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(AppPalette.controlTrack)
                .clickable { expanded = true }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            NativeText(label, color = AppPalette.textPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            NativeText("▾", color = AppPalette.textSecondary, fontSize = 9.sp)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            for ((value, label) in options) {
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onPick(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ValueTextField(
    value: String,
    onChange: (String) -> Unit,
    keyboardType: KeyboardType,
    modifier: Modifier = Modifier,
) {
    NativeTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier,
    )
}

@Composable
private fun AddPredicateButton(onAdd: (PredicateKind) -> Unit) {
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
            NativeText("+ predicate", color = AppPalette.textPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            NativeText("▾", color = AppPalette.textSecondary, fontSize = 9.sp)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            for (kind in PredicateKind.entries) {
                DropdownMenuItem(
                    text = { Text(kind.label) },
                    onClick = {
                        onAdd(kind)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ArrowButton(glyph: String, enabled: Boolean, onClick: () -> Unit) {
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
private fun IconButton(glyph: String, onClick: () -> Unit, color: Color) {
    Box(
        Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(AppPalette.controlTrack)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        NativeText(glyph, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SegmentChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) AccentColor else AppPalette.controlTrack
    val fg = if (selected) Color.White else AppPalette.textPrimary
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        NativeText(text, color = fg, fontSize = 11.sp)
    }
}

// ─────────────── Helpers ───────────────

/**
 * Closed catalogue of predicate kinds rendered in the "+ predicate"
 * dropdown. The `create()` factory mints an instance with sane defaults so
 * the user can immediately see the row in the editor.
 */
private enum class PredicateKind(val label: String, val create: () -> Predicate) {
    BundleId("Bundle ID", { BundleIdPredicate() }),
    AppName("App name", { AppNamePredicate() }),
    IsHidden("Is hidden", { IsHiddenPredicate() }),
    IsFinishedLaunching("Is launching", { IsFinishedLaunchingPredicate(inverted = true) }),
    ActivationPolicy("Activation policy", { ActivationPolicyPredicate() }),
    Title("Title", { TitlePredicate() }),
    Role("Role", { RolePredicate() }),
    Subrole("Subrole", { SubrolePredicate() }),
    IsMinimized("Is minimized", { IsMinimizedPredicate() }),
    IsFullscreen("Is fullscreen", { IsFullscreenPredicate() }),
    IsFocused("Is focused", { IsFocusedPredicate() }),
    IsMain("Is main", { IsMainPredicate() }),
    Width("Width (px)", { WidthPredicate() }),
    Height("Height (px)", { HeightPredicate() }),
    Area("Area (px²)", { AreaPredicate() }),
    NoVisibleWindows("No visible windows", { NoVisibleWindowsPredicate() }),
}

private fun kindOf(p: Predicate): PredicateKind = when (p) {
    is BundleIdPredicate -> PredicateKind.BundleId
    is AppNamePredicate -> PredicateKind.AppName
    is IsHiddenPredicate -> PredicateKind.IsHidden
    is IsFinishedLaunchingPredicate -> PredicateKind.IsFinishedLaunching
    is ActivationPolicyPredicate -> PredicateKind.ActivationPolicy
    is TitlePredicate -> PredicateKind.Title
    is RolePredicate -> PredicateKind.Role
    is SubrolePredicate -> PredicateKind.Subrole
    is IsMinimizedPredicate -> PredicateKind.IsMinimized
    is IsFullscreenPredicate -> PredicateKind.IsFullscreen
    is IsFocusedPredicate -> PredicateKind.IsFocused
    is IsMainPredicate -> PredicateKind.IsMain
    is WidthPredicate -> PredicateKind.Width
    is HeightPredicate -> PredicateKind.Height
    is AreaPredicate -> PredicateKind.Area
    is NoVisibleWindowsPredicate -> PredicateKind.NoVisibleWindows
}

private fun Predicate.withEnabled(v: Boolean): Predicate = when (this) {
    is BundleIdPredicate -> copy(enabled = v)
    is AppNamePredicate -> copy(enabled = v)
    is IsHiddenPredicate -> copy(enabled = v)
    is IsFinishedLaunchingPredicate -> copy(enabled = v)
    is ActivationPolicyPredicate -> copy(enabled = v)
    is TitlePredicate -> copy(enabled = v)
    is RolePredicate -> copy(enabled = v)
    is SubrolePredicate -> copy(enabled = v)
    is IsMinimizedPredicate -> copy(enabled = v)
    is IsFullscreenPredicate -> copy(enabled = v)
    is IsFocusedPredicate -> copy(enabled = v)
    is IsMainPredicate -> copy(enabled = v)
    is WidthPredicate -> copy(enabled = v)
    is HeightPredicate -> copy(enabled = v)
    is AreaPredicate -> copy(enabled = v)
    is NoVisibleWindowsPredicate -> copy(enabled = v)
}

private fun Predicate.withInverted(v: Boolean): Predicate = when (this) {
    is BundleIdPredicate -> copy(inverted = v)
    is AppNamePredicate -> copy(inverted = v)
    is IsHiddenPredicate -> copy(inverted = v)
    is IsFinishedLaunchingPredicate -> copy(inverted = v)
    is ActivationPolicyPredicate -> copy(inverted = v)
    is TitlePredicate -> copy(inverted = v)
    is RolePredicate -> copy(inverted = v)
    is SubrolePredicate -> copy(inverted = v)
    is IsMinimizedPredicate -> copy(inverted = v)
    is IsFullscreenPredicate -> copy(inverted = v)
    is IsFocusedPredicate -> copy(inverted = v)
    is IsMainPredicate -> copy(inverted = v)
    is WidthPredicate -> copy(inverted = v)
    is HeightPredicate -> copy(inverted = v)
    is AreaPredicate -> copy(inverted = v)
    is NoVisibleWindowsPredicate -> copy(inverted = v)
}

private fun stringOpLabel(op: StringOp): String = when (op) {
    StringOp.Eq -> "=="
    StringOp.Contains -> "contains"
    StringOp.Regex -> "regex"
    StringOp.IsEmpty -> "is empty"
}

private fun numberOpLabel(op: NumberOp): String = when (op) {
    NumberOp.Gt -> ">"
    NumberOp.Gte -> "≥"
    NumberOp.Lt -> "<"
    NumberOp.Lte -> "≤"
}

private fun formatNumber(d: Double): String =
    if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

/**
 * Compact one-line summary used as a placeholder for an unnamed rule.
 * Shows up to the first three enabled predicates joined with " · ", with a
 * leading "not " for inverted ones. Mirrors what a user might write by
 * hand so the auto-name matches their mental model.
 */
private fun ruleSummary(rule: Rule): String {
    val parts = rule.predicates.asSequence()
        .filter { it.enabled }
        .map(::summarisePredicate)
        .filter { it.isNotBlank() }
        .toList()
    if (parts.isEmpty()) return ""
    val head = parts.take(3).joinToString(" · ")
    return if (parts.size > 3) "$head · …" else head
}

private fun summarisePredicate(p: Predicate): String {
    val core = when (p) {
        is BundleIdPredicate -> stringSummary("bundleId", p.op, p.value)
        is AppNamePredicate -> stringSummary("name", p.op, p.value)
        is TitlePredicate -> stringSummary("title", p.op, p.value)
        is RolePredicate -> stringSummary("role", p.op, p.value)
        is SubrolePredicate -> stringSummary("subrole", p.op, p.value)
        is IsHiddenPredicate -> "isHidden"
        is IsFinishedLaunchingPredicate -> "isFinishedLaunching"
        is ActivationPolicyPredicate -> "policy == ${p.value.name}"
        is IsMinimizedPredicate -> "isMinimized"
        is IsFullscreenPredicate -> "isFullscreen"
        is IsFocusedPredicate -> "isFocused"
        is IsMainPredicate -> "isMain"
        is WidthPredicate -> "width ${numberOpLabel(p.op)} ${formatNumber(p.value)}"
        is HeightPredicate -> "height ${numberOpLabel(p.op)} ${formatNumber(p.value)}"
        is AreaPredicate -> "area ${numberOpLabel(p.op)} ${formatNumber(p.value)}"
        is NoVisibleWindowsPredicate -> "no visible windows"
    }
    return if (p.inverted) "not $core" else core
}

private fun stringSummary(field: String, op: StringOp, value: String): String = when (op) {
    StringOp.Eq -> "$field == '$value'"
    StringOp.Contains -> "$field contains '$value'"
    StringOp.Regex -> "$field ~= /$value/"
    StringOp.IsEmpty -> "$field is empty"
}

private fun <T> List<T>.swapped(i: Int, j: Int): List<T> = toMutableList().also {
    val tmp = it[i]; it[i] = it[j]; it[j] = tmp
}

private fun newId(): String = "r-" + Random.nextLong().toULong().toString(16)
