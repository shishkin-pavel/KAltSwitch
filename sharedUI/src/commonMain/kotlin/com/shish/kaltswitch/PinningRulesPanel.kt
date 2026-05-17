package com.shish.kaltswitch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shish.kaltswitch.model.PinningRule
import com.shish.kaltswitch.model.PinningRules
import com.shish.kaltswitch.model.Predicate
import com.shish.kaltswitch.model.newPinningRule

/**
 * Editor for window-pinning rules — Settings → Pinning tab. Each card holds
 * a predicate list AND-ed together; when every enabled predicate matches a
 * window, the window is re-parented as a child of the same app's most
 * recently activated non-pinned root window (see `World.applyPinning`).
 *
 * Layout mirrors [FilteringRulesPanel] minus the outcome row — pinning's
 * outcome is implicit ("pin"). The predicate-row editor is shared (see
 * `internal fun PredicateRow` in [FilteringRulesPanel]) so the two tabs
 * feel identical to the user.
 */
@Composable
fun PinningRulesPanel(
    rules: PinningRules,
    onChange: (PinningRules) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((index, rule) in rules.rules.withIndex()) {
            PinningRuleCard(
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
            label = "+ pinning rule",
            onClick = { onChange(rules.copy(rules = rules.rules + newPinningRule())) },
            modifier = Modifier.fillMaxWidth(),
        )
        PinningHelpFooter()
    }
}

@Composable
private fun PinningRuleCard(
    rule: PinningRule,
    onChange: (PinningRule) -> Unit,
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
            ArrowButton("▲", enabled = onMoveUp != null) { onMoveUp?.invoke() }
            ArrowButton("▼", enabled = onMoveDown != null) { onMoveDown?.invoke() }
            NameField(
                value = rule.name,
                placeholder = pinningRuleSummary(rule).ifBlank { "Unnamed pinning rule" },
                onChange = { onChange(rule.copy(name = it)) },
                modifier = Modifier.weight(1f),
            )
            NativeToggle(
                checked = rule.enabled,
                onCheckedChange = { onChange(rule.copy(enabled = it)) },
            )
            IconButton(glyph = "×", onClick = onDelete, color = Color(0xFFE57373))
        }

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
private fun PinningHelpFooter() {
    NativeText(
        "Pinned windows nest as children of the app's most recently activated " +
            "non-pinned window. Demoted children still appear under the parent, " +
            "after non-demoted siblings.",
        color = AppPalette.textSecondary,
        fontSize = 10.sp,
    )
}

/** Short auto-name for an unnamed pinning rule. Mirrors `ruleSummary` from
 *  [FilteringRulesPanel] but without the outcome string — the outcome here
 *  is implicit. */
private fun pinningRuleSummary(rule: PinningRule): String {
    val parts = rule.predicates.asSequence()
        .filter { it.enabled }
        .map(::summarisePredicate)
        .filter { it.isNotBlank() }
        .toList()
    if (parts.isEmpty()) return ""
    val head = parts.take(3).joinToString(" · ")
    return if (parts.size > 3) "$head · …" else head
}

private fun <T> List<T>.swapped(i: Int, j: Int): List<T> = toMutableList().also {
    val tmp = it[i]; it[i] = it[j]; it[j] = tmp
}
