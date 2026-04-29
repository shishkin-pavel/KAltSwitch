package com.shish.kaltswitch

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shish.kaltswitch.config.AccentColorChoice
import com.shish.kaltswitch.config.SwitcherSettings
import com.shish.kaltswitch.model.AppActivationPolicy
import com.shish.kaltswitch.model.AppView
import com.shish.kaltswitch.model.FilteredSnapshot
import com.shish.kaltswitch.model.FilteringRules
import com.shish.kaltswitch.model.TriFilter
import com.shish.kaltswitch.model.WindowId
import com.shish.kaltswitch.model.WindowView
import com.shish.kaltswitch.model.World
import com.shish.kaltswitch.model.filteredSnapshot

@Composable
fun App(
    world: World,
    axTrusted: Boolean = true,
    activeAppPid: Int? = null,
    activeWindowId: WindowId? = null,
    filters: FilteringRules = FilteringRules(),
    onFiltersChange: (FilteringRules) -> Unit = {},
    switcherSettings: SwitcherSettings = SwitcherSettings(),
    onSwitcherSettingsChange: (SwitcherSettings) -> Unit = {},
    inspectorVisible: Boolean = true,
    onInspectorVisibleChange: (Boolean) -> Unit = {},
    showMenubarIcon: Boolean = true,
    onShowMenubarIconChange: (Boolean) -> Unit = {},
    launchAtLogin: Boolean = false,
    onLaunchAtLoginChange: (Boolean) -> Unit = {},
    sidebarWidth: Double = DefaultSidebarWidth,
    onSidebarWidthChange: (Double) -> Unit = {},
    onInspectorWidthChange: (Double) -> Unit = {},
    currentSpaceOnly: Boolean = false,
    onCurrentSpaceOnlyChange: (Boolean) -> Unit = {},
    visibleSpaceIds: List<Long> = emptyList(),
    accentColor: AccentColorChoice = AccentColorChoice.Custom(0xFFC107),
    onAccentColorChange: (AccentColorChoice) -> Unit = {},
    systemAccentRgb: Long? = null,
    onGrantAxClick: () -> Unit = {},
) {
    val snapshot = remember(world, filters, currentSpaceOnly, visibleSpaceIds) {
        world.filteredSnapshot(filters, currentSpaceOnly, visibleSpaceIds)
    }
    val density = LocalDensity.current
    val resolvedAccent = resolveAccent(accentColor, systemAccentRgb)
    ProvideAccent(resolvedAccent) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF1E1E1E))) {
        val totalWidthPx = with(density) { maxWidth.toPx() }
        val handleWidthPx = with(density) { SeparatorWidth.toPx() }
        val minSidebarPx = with(density) { MinSidebarWidth.toPx() }
        val minInspectorPx = with(density) { MinInspectorWidth.toPx() }

        // Clamp sidebar to a sensible range relative to the current window
        // width — needed so launches at unusual sizes (or stale persisted
        // widths from a wider window) don't blow out the inspector.
        val effectiveSidebarPx = if (inspectorVisible) {
            val sidebarPx = with(density) { sidebarWidth.dp.toPx() }
            sidebarPx.coerceIn(
                minimumValue = minSidebarPx,
                maximumValue = (totalWidthPx - handleWidthPx - minInspectorPx).coerceAtLeast(minSidebarPx),
            )
        } else {
            totalWidthPx
        }
        val effectiveSidebarDp = with(density) { effectiveSidebarPx.toDp() }

        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .width(effectiveSidebarDp)
                    .fillMaxHeight()
                    .background(Color(0xFF181818))
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SettingsPanel(
                    settings = switcherSettings,
                    onChange = onSwitcherSettingsChange,
                    showMenubarIcon = showMenubarIcon,
                    onShowMenubarIconChange = onShowMenubarIconChange,
                    launchAtLogin = launchAtLogin,
                    onLaunchAtLoginChange = onLaunchAtLoginChange,
                    currentSpaceOnly = currentSpaceOnly,
                    onCurrentSpaceOnlyChange = onCurrentSpaceOnlyChange,
                    accentColor = accentColor,
                    onAccentColorChange = onAccentColorChange,
                )
                Spacer(Modifier.height(2.dp))
                FilteringRulesPanel(
                    filters = filters,
                    onChange = onFiltersChange,
                    inspectorVisible = inspectorVisible,
                    onToggleInspector = { onInspectorVisibleChange(!inspectorVisible) },
                )
            }
            if (inspectorVisible) {
                // Drag separator: shifts the sidebar/inspector boundary inside
                // the same Compose canvas — the host window's overall width
                // doesn't change. We persist both widths so toggling the
                // inspector (which Swift uses to grow/shrink the window by
                // exactly `inspectorWidth`) stays in sync.
                DragHandle(
                    onDrag = { dx ->
                        val newSidebarPx = (effectiveSidebarPx + dx).coerceIn(
                            minimumValue = minSidebarPx,
                            maximumValue = totalWidthPx - handleWidthPx - minInspectorPx,
                        )
                        val newInspectorPx = totalWidthPx - newSidebarPx - handleWidthPx
                        with(density) {
                            onSidebarWidthChange(newSidebarPx.toDp().value.toDouble())
                            onInspectorWidthChange(newInspectorPx.toDp().value.toDouble())
                        }
                    },
                )
                InspectorPanel(
                    snapshot = snapshot,
                    axTrusted = axTrusted,
                    activeAppPid = activeAppPid,
                    activeWindowId = activeWindowId,
                    onGrantAxClick = onGrantAxClick,
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(16.dp),
                )
            }
        }
    }
    }
}

private val DefaultSidebarWidth = 320.0
private val SeparatorWidth = 4.dp
private val MinSidebarWidth = 240.dp
private val MinInspectorWidth = 240.dp

@Composable
private fun DragHandle(onDrag: (Float) -> Unit) {
    val state = rememberDraggableState { delta -> onDrag(delta) }
    Box(
        Modifier
            .width(SeparatorWidth)
            .fillMaxHeight()
            .background(Color(0xFF101010))
            .draggable(
                orientation = Orientation.Horizontal,
                state = state,
            ),
    )
}

// ─────────── Inspector (right) ───────────

@Composable
private fun InspectorPanel(
    snapshot: FilteredSnapshot,
    axTrusted: Boolean,
    activeAppPid: Int?,
    activeWindowId: WindowId?,
    onGrantAxClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!axTrusted) AxBanner(onGrantAxClick)
        LazyColumn(
            Modifier.padding(start = 4.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            modeSection("Show", snapshot.show, activeAppPid, activeWindowId)
            if (snapshot.demote.isNotEmpty()) {
                item { Spacer(Modifier.height(12.dp)) }
                modeSection("Demote", snapshot.demote, activeAppPid, activeWindowId)
            }
            if (snapshot.hide.isNotEmpty()) {
                item { Spacer(Modifier.height(12.dp)) }
                modeSection("Hide", snapshot.hide, activeAppPid, activeWindowId)
            }
        }
    }
}

private fun LazyListScope.modeSection(
    title: String,
    apps: List<AppView>,
    activeAppPid: Int?,
    activeWindowId: WindowId?,
) {
    item {
        Text(
            "$title (${apps.size})",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
        )
    }
    items(apps) { entry -> AppRow(entry, activeAppPid, activeWindowId) }
}

@Composable
private fun AxBanner(onGrantClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF3A2C0A))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "⚠️  Accessibility permission not granted. Without it we can't see other apps' windows.",
            color = AccentColor,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = onGrantClick,
            colors = ButtonDefaults.buttonColors(containerColor = AccentColor, contentColor = Color.Black),
        ) { Text("Grant…") }
    }
}

@Composable
private fun AppRow(view: AppView, activeAppPid: Int?, activeWindowId: WindowId?) {
    val app = view.app
    val isActiveApp = app.pid == activeAppPid
    val baseColor = if (isActiveApp) Color(0xFFFFFFFF) else Color(0xFFE0E0E0)
    val color = baseColor.dimmedFor(view.mode)
    val pictogram = appPictogram(app, isActiveApp)
    val tags = buildList {
        add(policyTag(app.activationPolicy))
        if (app.isHidden) add("hidden")
        if (!app.isFinishedLaunching) add("launching")
        app.bundleId?.let { add(it) }
    }.joinToString(" · ")
    Column {
        Text(
            "$pictogram ${app.name}  ${tagText(tags)}",
            color = color,
            fontWeight = if (isActiveApp) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.bodyMedium,
        )
        view.windows.forEach { wv ->
            WindowSubtree(wv, appName = app.name, depth = 1, isInActiveApp = activeAppPid == app.pid, activeWindowId = activeWindowId)
        }
    }
}

@Composable
private fun WindowSubtree(
    view: WindowView,
    appName: String,
    depth: Int,
    isInActiveApp: Boolean,
    activeWindowId: WindowId?,
) {
    WindowRow(view, appName = appName, depth = depth, isActive = isInActiveApp && view.window.id == activeWindowId)
    view.children.forEach { child ->
        WindowSubtree(child, appName, depth + 1, isInActiveApp, activeWindowId)
    }
}

@Composable
private fun WindowRow(view: WindowView, appName: String, depth: Int, isActive: Boolean) {
    val w = view.window
    val baseColor = if (isActive) Color(0xFFFFFFFF) else Color(0xFF9E9E9E)
    val color = baseColor.dimmedFor(view.mode)
    val pictogram = windowPictogram(view, isActive)
    val tags = buildList {
        if (view.mode != TriFilter.Show) add(view.mode.name.lowercase())
        if (!w.role.isNullOrBlank()) add("role: " + w.role.removePrefix("AX"))
        if (!w.subrole.isNullOrBlank()) add("subrole: " + w.subrole.removePrefix("AX"))
        if (w.isMain) add("main")
        if (w.width != null && w.height != null) add("${w.width.toInt()}×${w.height.toInt()}")
        if (view.children.isNotEmpty()) add("${view.children.size} child")
    }.joinToString(" · ")
    val indent = "    ".repeat(depth)
    Text(
        "$indent$pictogram ${effectiveWindowTitle(w.title, appName)}  ${tagText(tags)}",
        color = color,
        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
        style = MaterialTheme.typography.bodySmall,
    )
}

private fun appPictogram(app: com.shish.kaltswitch.model.App, isActive: Boolean): String = when {
    isActive -> "▶"
    app.isHidden -> "◌"
    !app.isFinishedLaunching -> "…"
    app.activationPolicy == AppActivationPolicy.Accessory -> "◇"
    else -> "•"
}

private fun windowPictogram(view: WindowView, isActive: Boolean): String = when {
    isActive -> "└▶"
    view.window.isMinimized -> "└⎽"
    view.window.isFullscreen -> "└⤢"
    else -> "└─"
}

private fun policyTag(p: AppActivationPolicy): String = when (p) {
    AppActivationPolicy.Regular -> "regular"
    AppActivationPolicy.Accessory -> "accessory"
    AppActivationPolicy.Prohibited -> "prohibited"
}

private fun tagText(s: String): String = if (s.isBlank()) "" else "  · $s"

/** Visually weaken a color based on filter mode so demoted/hidden rows recede. */
private fun Color.dimmedFor(mode: TriFilter): Color = when (mode) {
    TriFilter.Show -> this
    TriFilter.Demote -> this.copy(alpha = 0.65f)
    TriFilter.Hide -> this.copy(alpha = 0.35f)
}

// ─────────── Settings panel (left) ───────────

@Composable
private fun SettingsPanel(
    settings: SwitcherSettings,
    onChange: (SwitcherSettings) -> Unit,
    showMenubarIcon: Boolean,
    onShowMenubarIconChange: (Boolean) -> Unit,
    launchAtLogin: Boolean,
    onLaunchAtLoginChange: (Boolean) -> Unit,
    currentSpaceOnly: Boolean,
    onCurrentSpaceOnlyChange: (Boolean) -> Unit,
    accentColor: AccentColorChoice,
    onAccentColorChange: (AccentColorChoice) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "Settings",
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(Modifier.height(4.dp))
        DelaySlider(
            label = "Show delay",
            valueMs = settings.showDelayMs,
            range = 0f..200f,
            onChange = { onChange(settings.copy(showDelayMs = it)) },
        )
        DelaySlider(
            label = "Preview delay",
            valueMs = settings.previewDelayMs,
            range = 50f..1000f,
            onChange = { onChange(settings.copy(previewDelayMs = it)) },
        )
        DelaySlider(
            label = "Auto-advance after",
            valueMs = settings.repeatInitialDelayMs,
            range = 100f..1500f,
            onChange = { onChange(settings.copy(repeatInitialDelayMs = it)) },
        )
        DelaySlider(
            label = "Auto-advance step",
            valueMs = settings.repeatIntervalMs,
            range = 30f..500f,
            onChange = { onChange(settings.copy(repeatIntervalMs = it)) },
        )
        ToggleRow(
            label = "Preview-raise on hover",
            checked = settings.previewEnabled,
            onCheckedChange = { onChange(settings.copy(previewEnabled = it)) },
        )
        ToggleRow(
            label = "Show menubar icon",
            checked = showMenubarIcon,
            onCheckedChange = onShowMenubarIconChange,
        )
        ToggleRow(
            label = "Launch at login",
            checked = launchAtLogin,
            onCheckedChange = onLaunchAtLoginChange,
        )
        ToggleRow(
            label = "Current space only",
            checked = currentSpaceOnly,
            onCheckedChange = onCurrentSpaceOnlyChange,
        )
        Spacer(Modifier.height(2.dp))
        AccentColorRow(
            choice = accentColor,
            onChange = onAccentColorChange,
        )
    }
}

/**
 * Two-row "Accent colour" widget: a Use-system toggle and (when off) a hex
 * input the user types directly into. We accept 6-digit hex with or without
 * a leading `#`; bad input is silently ignored so partial typing doesn't
 * spam invalid `setAccentColor` calls.
 */
@Composable
private fun AccentColorRow(
    choice: AccentColorChoice,
    onChange: (AccentColorChoice) -> Unit,
) {
    val isSystem = choice is AccentColorChoice.UseSystem
    val customRgb = (choice as? AccentColorChoice.Custom)?.rgb ?: 0xFFC107L
    ToggleRow(
        label = "Use system accent color",
        checked = isSystem,
        onCheckedChange = { wantSystem ->
            onChange(if (wantSystem) AccentColorChoice.UseSystem else AccentColorChoice.Custom(customRgb))
        },
    )
    if (!isSystem) {
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Custom color",
                color = Color(0xFFE0E0E0),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
            )
            // Visual swatch + hex input. Click-to-edit happens via the
            // BasicTextField; the swatch reflects the currently-stored
            // RGB so the user has a feedback loop while typing.
            Box(
                Modifier
                    .width(20.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(rgbToColor(customRgb)),
            )
            HexField(
                rgb = customRgb,
                onChange = { onChange(AccentColorChoice.Custom(it)) },
            )
        }
    }
}

@Composable
private fun HexField(rgb: Long, onChange: (Long) -> Unit) {
    val text = rgb.toString(16).padStart(6, '0').uppercase()
    Box(
        Modifier
            .width(80.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF1A1A1A))
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        androidx.compose.foundation.text.BasicTextField(
            value = text,
            onValueChange = { raw ->
                val cleaned = raw.trimStart('#').take(6).uppercase()
                if (cleaned.length == 6) {
                    cleaned.toLongOrNull(16)?.let(onChange)
                }
            },
            singleLine = true,
            cursorBrush = androidx.compose.ui.graphics.SolidColor(AccentColor),
            textStyle = androidx.compose.ui.text.TextStyle(
                color = Color(0xFFE0E0E0),
                fontSize = 12.sp,
            ),
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            color = Color(0xFFE0E0E0),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = AccentColor,
                checkedThumbColor = Color.Black,
            ),
        )
    }
}

@Composable
private fun DelaySlider(
    label: String,
    valueMs: Long,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Long) -> Unit,
) {
    // macOS settings-style row: label left, value right, slider underneath
    // with reduced height. labelSmall keeps it visually quiet.
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = Color(0xFFE0E0E0), style = MaterialTheme.typography.labelSmall)
            Text(
                "${valueMs} ms",
                color = AccentColor,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Slider(
            value = valueMs.toFloat().coerceIn(range),
            onValueChange = { onChange(it.toLong()) },
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = AccentColor,
                activeTrackColor = AccentColor,
                inactiveTrackColor = Color(0x33FFFFFF),
            ),
            modifier = Modifier.height(20.dp),
        )
    }
}

