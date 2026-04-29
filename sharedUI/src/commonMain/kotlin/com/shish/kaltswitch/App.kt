package com.shish.kaltswitch

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import com.shish.kaltswitch.config.SwitcherSettings
import com.shish.kaltswitch.model.AppActivationPolicy
import com.shish.kaltswitch.model.AppView
import com.shish.kaltswitch.model.FilteredSnapshot
import com.shish.kaltswitch.model.Filters
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
    filters: Filters = Filters(),
    onFiltersChange: (Filters) -> Unit = {},
    switcherSettings: SwitcherSettings = SwitcherSettings(),
    onSwitcherSettingsChange: (SwitcherSettings) -> Unit = {},
    inspectorVisible: Boolean = true,
    onInspectorVisibleChange: (Boolean) -> Unit = {},
    onGrantAxClick: () -> Unit = {},
) {
    val snapshot = remember(world, filters) { world.filteredSnapshot(filters) }
    Row(Modifier.fillMaxSize().background(Color(0xFF1E1E1E))) {
        // Sidebar: fixed-width when inspector is visible, full-width when
        // hidden so the user has room to read the settings comfortably
        // without an empty right pane.
        val sidebarModifier = if (inspectorVisible) {
            Modifier.width(260.dp)
        } else {
            Modifier.fillMaxWidth()
        }
        Column(
            sidebarModifier
                .fillMaxHeight()
                .background(Color(0xFF181818))
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SettingsPanel(
                settings = switcherSettings,
                onChange = onSwitcherSettingsChange,
            )
            Spacer(Modifier.height(2.dp))
            FiltersPanel(
                filters = filters,
                onFiltersChange = onFiltersChange,
                inspectorVisible = inspectorVisible,
                onToggleInspector = { onInspectorVisibleChange(!inspectorVisible) },
            )
        }
        if (inspectorVisible) {
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
            color = Color(0xFFFFC107),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = onGrantClick,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFC107), contentColor = Color.Black),
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
            WindowSubtree(wv, depth = 1, activeAppPid == app.pid, activeWindowId)
        }
    }
}

@Composable
private fun WindowSubtree(
    view: WindowView,
    depth: Int,
    isInActiveApp: Boolean,
    activeWindowId: WindowId?,
) {
    WindowRow(view, depth = depth, isActive = isInActiveApp && view.window.id == activeWindowId)
    view.children.forEach { child ->
        WindowSubtree(child, depth + 1, isInActiveApp, activeWindowId)
    }
}

@Composable
private fun WindowRow(view: WindowView, depth: Int, isActive: Boolean) {
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
        "$indent$pictogram ${w.title.ifBlank { "(untitled)" }}  ${tagText(tags)}",
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

// ─────────── Filters panel (left) ───────────

@Composable
private fun SettingsPanel(
    settings: SwitcherSettings,
    onChange: (SwitcherSettings) -> Unit,
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
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Preview-raise on hover",
                color = Color(0xFFE0E0E0),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = settings.previewEnabled,
                onCheckedChange = { onChange(settings.copy(previewEnabled = it)) },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = Color(0xFFFFC107),
                    checkedThumbColor = Color.Black,
                ),
            )
        }
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
                color = Color(0xFFFFC107),
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Slider(
            value = valueMs.toFloat().coerceIn(range),
            onValueChange = { onChange(it.toLong()) },
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFFFFC107),
                activeTrackColor = Color(0xFFFFC107),
                inactiveTrackColor = Color(0x33FFFFFF),
            ),
            modifier = Modifier.height(20.dp),
        )
    }
}

@Composable
private fun FiltersPanel(
    filters: Filters,
    onFiltersChange: (Filters) -> Unit,
    inspectorVisible: Boolean = true,
    onToggleInspector: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Filters",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            // Toggle the right-side inspector pane. Chevron flips direction
            // depending on which way the toggle would move the divider.
            Box(
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0x22FFFFFF))
                    .clickable(onClick = onToggleInspector)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    if (inspectorVisible) "›" else "‹",
                    color = Color(0xFFE0E0E0),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        FilterGroup("Apps")
        TriRow("Windowless apps", filters.windowlessApps) { onFiltersChange(filters.copy(windowlessApps = it)) }
        TriRow("Accessory apps", filters.accessoryApps) { onFiltersChange(filters.copy(accessoryApps = it)) }
        TriRow("Hidden apps (cmd+H)", filters.hiddenApps) { onFiltersChange(filters.copy(hiddenApps = it)) }
        TriRow("Launching apps", filters.launchingApps) { onFiltersChange(filters.copy(launchingApps = it)) }

        Spacer(Modifier.height(6.dp))
        FilterGroup("Windows")
        TriRow("Minimized", filters.minimizedWindows) { onFiltersChange(filters.copy(minimizedWindows = it)) }
        TriRow("Fullscreen", filters.fullscreenWindows) { onFiltersChange(filters.copy(fullscreenWindows = it)) }
        TriRow("Non-standard subrole", filters.nonStandardSubroleWindows) {
            onFiltersChange(filters.copy(nonStandardSubroleWindows = it))
        }
        TriRow("Untitled", filters.untitledWindows) { onFiltersChange(filters.copy(untitledWindows = it)) }
    }
}

@Composable
private fun FilterGroup(title: String) {
    Text(
        title,
        color = Color(0xFFB0BEC5),
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun TriRow(label: String, value: TriFilter, onChange: (TriFilter) -> Unit) {
    Column {
        Text(label, color = Color(0xFFE0E0E0), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (option in TriFilter.entries) {
                SegmentChip(
                    text = option.name,
                    selected = option == value,
                    onClick = { onChange(option) },
                )
            }
        }
    }
}

@Composable
private fun SegmentChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) Color(0xFFFFC107) else Color(0xFF2A2A2A)
    val fg = if (selected) Color.Black else Color(0xFFCCCCCC)
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(text, color = fg, fontSize = 11.sp)
    }
}
