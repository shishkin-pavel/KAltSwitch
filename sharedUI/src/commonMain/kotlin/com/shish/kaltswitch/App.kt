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
import com.shish.kaltswitch.model.AppActivationPolicy
import com.shish.kaltswitch.model.AppEntry
import com.shish.kaltswitch.model.BiFilter
import com.shish.kaltswitch.model.FilteredSnapshot
import com.shish.kaltswitch.model.Filters
import com.shish.kaltswitch.model.TriFilter
import com.shish.kaltswitch.model.Window
import com.shish.kaltswitch.model.WindowId
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
    onGrantAxClick: () -> Unit = {},
) {
    val snapshot = remember(world, filters) { world.filteredSnapshot(filters) }
    Row(Modifier.fillMaxSize().background(Color(0xFF1E1E1E))) {
        FiltersPanel(
            filters = filters,
            onFiltersChange = onFiltersChange,
            modifier = Modifier
                .width(260.dp)
                .fillMaxHeight()
                .background(Color(0xFF181818))
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
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
            sectionHeader("Apps with windows (${snapshot.primary.size})")
            items(snapshot.primary) { entry -> AppRow(entry, activeAppPid, activeWindowId) }
            item { Spacer(Modifier.height(12.dp)) }
            sectionHeader("Windowless (${snapshot.windowless.size})")
            items(snapshot.windowless) { entry -> AppRow(entry, activeAppPid, activeWindowId) }
            if (snapshot.demoted.isNotEmpty()) {
                item { Spacer(Modifier.height(12.dp)) }
                sectionHeader("Demoted by filters (${snapshot.demoted.size})")
                items(snapshot.demoted) { entry -> AppRow(entry, activeAppPid, activeWindowId) }
            }
        }
    }
}

private fun LazyListScope.sectionHeader(title: String) {
    item {
        Text(
            title,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
        )
    }
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
private fun AppRow(entry: AppEntry, activeAppPid: Int?, activeWindowId: WindowId?) {
    val app = entry.app
    val isActiveApp = app.pid == activeAppPid
    val appColor = if (isActiveApp) Color(0xFFFFFFFF) else Color(0xFFE0E0E0)
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
            color = appColor,
            fontWeight = if (isActiveApp) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.bodyMedium,
        )
        entry.windows.forEach { w ->
            WindowSubtree(w, depth = 1, activeAppPid == app.pid, activeWindowId)
        }
    }
}

@Composable
private fun WindowSubtree(
    w: Window,
    depth: Int,
    isInActiveApp: Boolean,
    activeWindowId: WindowId?,
) {
    WindowRow(w, depth = depth, isActive = isInActiveApp && w.id == activeWindowId)
    w.children.forEach { child -> WindowSubtree(child, depth + 1, isInActiveApp, activeWindowId) }
}

@Composable
private fun WindowRow(w: Window, depth: Int, isActive: Boolean) {
    val color = if (isActive) Color(0xFFFFFFFF) else Color(0xFF9E9E9E)
    val pictogram = windowPictogram(w, isActive)
    val tags = buildList {
        if (!w.role.isNullOrBlank()) add("role: " + w.role.removePrefix("AX"))
        if (!w.subrole.isNullOrBlank()) add("subrole: " + w.subrole.removePrefix("AX"))
        if (w.isMain) add("main")
        if (w.width != null && w.height != null) add("${w.width.toInt()}×${w.height.toInt()}")
        if (w.children.isNotEmpty()) add("${w.children.size} child")
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

private fun windowPictogram(w: Window, isActive: Boolean): String = when {
    isActive -> "└▶"
    w.isMinimized -> "└⎽"
    w.isFullscreen -> "└⤢"
    else -> "└─"
}

private fun policyTag(p: AppActivationPolicy): String = when (p) {
    AppActivationPolicy.Regular -> "regular"
    AppActivationPolicy.Accessory -> "accessory"
    AppActivationPolicy.Prohibited -> "prohibited"
}

private fun tagText(s: String): String = if (s.isBlank()) "" else "  · $s"

// ─────────── Filters panel (left) ───────────

@Composable
private fun FiltersPanel(
    filters: Filters,
    onFiltersChange: (Filters) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Filters",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
        )
        FilterGroup("Apps")
        TriRow("Windowless apps", filters.windowlessApps) {
            onFiltersChange(filters.copy(windowlessApps = it))
        }
        TriRow("Accessory apps", filters.accessoryApps) {
            onFiltersChange(filters.copy(accessoryApps = it))
        }
        TriRow("Hidden apps (cmd+H)", filters.hiddenApps) {
            onFiltersChange(filters.copy(hiddenApps = it))
        }
        BiRow("Launching apps", filters.launchingApps) {
            onFiltersChange(filters.copy(launchingApps = it))
        }

        Spacer(Modifier.height(6.dp))
        FilterGroup("Windows")
        BiRow("Minimized", filters.minimizedWindows) {
            onFiltersChange(filters.copy(minimizedWindows = it))
        }
        BiRow("Fullscreen", filters.fullscreenWindows) {
            onFiltersChange(filters.copy(fullscreenWindows = it))
        }
        BiRow("Non-standard subrole", filters.nonStandardSubroleWindows) {
            onFiltersChange(filters.copy(nonStandardSubroleWindows = it))
        }
        BiRow("Untitled", filters.untitledWindows) {
            onFiltersChange(filters.copy(untitledWindows = it))
        }
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
                    text = option.label(),
                    selected = option == value,
                    onClick = { onChange(option) },
                )
            }
        }
    }
}

@Composable
private fun BiRow(label: String, value: BiFilter, onChange: (BiFilter) -> Unit) {
    Column {
        Text(label, color = Color(0xFFE0E0E0), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (option in BiFilter.entries) {
                SegmentChip(
                    text = option.label(),
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

private fun TriFilter.label(): String = when (this) {
    TriFilter.Show -> "Show"
    TriFilter.Demote -> "Demote"
    TriFilter.Hide -> "Hide"
}

private fun BiFilter.label(): String = when (this) {
    BiFilter.Show -> "Show"
    BiFilter.Hide -> "Hide"
}
