package com.shish.kaltswitch

import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shish.kaltswitch.config.AccentColorChoice
import com.shish.kaltswitch.config.MaxSizeMode
import com.shish.kaltswitch.config.SwitcherPlacement
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

// ─────────────────────────── Settings window content ───────────────────────────

/**
 * Top-level Compose root for the Settings window. The tab bar (General /
 * Rules) sits inline on the window background — no separate toolbar
 * band — and the active tab's content scrolls below it. Theme is
 * driven by the Swift-side `NSAppearance` observer pushing into
 * [com.shish.kaltswitch.store.WorldStore.isDarkMode]; the caller wraps
 * this composable in [ProvideAppPalette] + [ProvideAccent].
 */
@Composable
fun SettingsContent(
    switcherSettings: SwitcherSettings,
    onSwitcherSettingsChange: (SwitcherSettings) -> Unit,
    showMenubarIcon: Boolean,
    onShowMenubarIconChange: (Boolean) -> Unit,
    launchAtLogin: Boolean,
    onLaunchAtLoginChange: (Boolean) -> Unit,
    currentSpaceOnly: Boolean,
    onCurrentSpaceOnlyChange: (Boolean) -> Unit,
    accentColor: AccentColorChoice,
    onAccentColorChange: (AccentColorChoice) -> Unit,
    filters: FilteringRules,
    onFiltersChange: (FilteringRules) -> Unit,
) {
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    val pal = AppPalette
    Column(
        Modifier
            .fillMaxSize()
            .background(pal.windowBg),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            NativeTabBar(
                items = listOf("General", "Rules"),
                selectedIndex = selectedTab,
                onSelect = { selectedTab = it },
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp),
        ) {
            when (selectedTab) {
                0 -> GeneralSection(
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
                1 -> FilteringRulesPanel(
                    filters = filters,
                    onChange = onFiltersChange,
                )
            }
        }
    }
}

// ──────────────────────── General section — switcher knobs ───────────────────────

@Composable
private fun GeneralSection(
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
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        NativeGroupBox(title = "Switcher timing") {
            DelayRow(
                label = "Show delay",
                valueMs = settings.showDelayMs,
                range = 0f..200f,
                onChange = { onChange(settings.copy(showDelayMs = it)) },
            )
            NativeRowDivider()
            DelayRow(
                label = "Auto-advance after",
                valueMs = settings.repeatInitialDelayMs,
                range = 100f..1500f,
                onChange = { onChange(settings.copy(repeatInitialDelayMs = it)) },
            )
            NativeRowDivider()
            DelayRow(
                label = "Auto-advance step",
                valueMs = settings.repeatIntervalMs,
                range = 30f..500f,
                onChange = { onChange(settings.copy(repeatIntervalMs = it)) },
            )
            NativeRowDivider()
            DelayRow(
                label = "Title expand delay",
                valueMs = settings.selectionExpandDelayMs,
                range = 0f..1000f,
                onChange = { onChange(settings.copy(selectionExpandDelayMs = it)) },
            )
        }
        NativeGroupBox(title = "Layout") {
            MaxWidthSetting(
                mode = settings.maxWidthMode,
                percent = settings.maxWidthPercent,
                maxIcons = settings.maxIconsPerRow,
                onChange = { mode, percent, maxIcons ->
                    onChange(settings.copy(
                        maxWidthMode = mode,
                        maxWidthPercent = percent,
                        maxIconsPerRow = maxIcons,
                    ))
                },
            )
            NativeRowDivider()
            CellSizeRow(
                percent = settings.cellSizePercent,
                onChange = { onChange(settings.copy(cellSizePercent = it)) },
            )
        }
        NativeGroupBox(title = "Behaviour") {
            NativeRow(label = "Show menubar icon") {
                NativeToggle(checked = showMenubarIcon, onCheckedChange = onShowMenubarIconChange)
            }
            NativeRowDivider()
            NativeRow(label = "Launch at login") {
                NativeToggle(checked = launchAtLogin, onCheckedChange = onLaunchAtLoginChange)
            }
            NativeRowDivider()
            NativeRow(label = "Current space only") {
                NativeToggle(checked = currentSpaceOnly, onCheckedChange = onCurrentSpaceOnlyChange)
            }
            NativeRowDivider()
            PlacementRow(
                placement = settings.windowPlacement,
                onChange = { onChange(settings.copy(windowPlacement = it)) },
            )
        }
        NativeGroupBox(title = "Accent colour") {
            AccentColorRow(
                choice = accentColor,
                onChange = onAccentColorChange,
            )
        }
    }
}

@Composable
private fun DelayRow(
    label: String,
    valueMs: Long,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Long) -> Unit,
) {
    NativeRow(label = label) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.width(180.dp)) {
                NativeSlider(
                    value = valueMs.toFloat().coerceIn(range),
                    onValueChange = { onChange(it.toLong()) },
                    valueRange = range,
                )
            }
            NativeText("${valueMs} ms", color = AppPalette.textSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PlacementRow(
    placement: SwitcherPlacement,
    onChange: (SwitcherPlacement) -> Unit,
) {
    val options = listOf(
        SwitcherPlacement.MouseScreen to "Mouse",
        SwitcherPlacement.ActiveWindowScreen to "Active window",
        SwitcherPlacement.MainScreen to "Main",
    )
    NativeRow(label = "Open switcher on") {
        NativeTabBar(
            items = options.map { it.second },
            selectedIndex = options.indexOfFirst { it.first == placement }.coerceAtLeast(0),
            onSelect = { idx -> onChange(options[idx].first) },
        )
    }
}

@Composable
private fun CellSizeRow(percent: Int, onChange: (Int) -> Unit) {
    NativeRow(label = "Cell size") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.width(180.dp)) {
                NativeSlider(
                    value = percent.toFloat().coerceIn(50f, 200f),
                    onValueChange = { onChange(it.toInt().coerceIn(50, 200)) },
                    valueRange = 50f..200f,
                )
            }
            NativeText("$percent %", color = AppPalette.textSecondary, fontSize = 12.sp)
        }
    }
}

/**
 * Max-panel-width row: a value-tagged slider with a tab switch above it
 * for the cap unit. Both [percent] and [maxIcons] are kept in the
 * settings so flipping the mode preserves whatever the user dialled in
 * for each.
 */
@Composable
private fun MaxWidthSetting(
    mode: MaxSizeMode,
    percent: Double,
    maxIcons: Int,
    onChange: (mode: MaxSizeMode, percent: Double, maxIcons: Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        NativeRow(label = "Max panel width") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NativeTabBar(
                    items = listOf("% of screen", "Icons / row"),
                    selectedIndex = if (mode == MaxSizeMode.Percent) 0 else 1,
                    onSelect = { idx ->
                        val newMode = if (idx == 0) MaxSizeMode.Percent else MaxSizeMode.MaxIconsPerRow
                        onChange(newMode, percent, maxIcons)
                    },
                )
            }
        }
        NativeRow(label = "") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (mode == MaxSizeMode.Percent) {
                    Box(Modifier.width(180.dp)) {
                        NativeSlider(
                            value = (percent * 100).toFloat().coerceIn(30f, 100f),
                            onValueChange = { onChange(mode, (it / 100.0).coerceIn(0.3, 1.0), maxIcons) },
                            valueRange = 30f..100f,
                        )
                    }
                    NativeText("${(percent * 100).toInt()} %", color = AppPalette.textSecondary, fontSize = 12.sp)
                } else {
                    Box(Modifier.width(180.dp)) {
                        NativeSlider(
                            value = maxIcons.toFloat().coerceIn(1f, 30f),
                            onValueChange = { onChange(mode, percent, it.toInt().coerceIn(1, 50)) },
                            valueRange = 1f..30f,
                        )
                    }
                    NativeText("$maxIcons", color = AppPalette.textSecondary, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun AccentColorRow(
    choice: AccentColorChoice,
    onChange: (AccentColorChoice) -> Unit,
) {
    val isSystem = choice is AccentColorChoice.UseSystem
    val customRgb = (choice as? AccentColorChoice.Custom)?.rgb ?: 0xFFC107L
    NativeRow(label = "Use system colour") {
        NativeToggle(
            checked = isSystem,
            onCheckedChange = { wantSystem ->
                onChange(if (wantSystem) AccentColorChoice.UseSystem else AccentColorChoice.Custom(customRgb))
            },
        )
    }
    if (!isSystem) {
        NativeRowDivider()
        NativeRow(label = "Custom colour") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .width(20.dp)
                        .height(20.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(rgbToColor(customRgb)),
                )
                Box(Modifier.width(90.dp)) {
                    NativeTextField(
                        value = customRgb.toString(16).padStart(6, '0').uppercase(),
                        onValueChange = { raw ->
                            val cleaned = raw.trimStart('#').take(6).uppercase()
                            if (cleaned.length == 6) {
                                cleaned.toLongOrNull(16)?.let { onChange(AccentColorChoice.Custom(it)) }
                            }
                        },
                    )
                }
            }
        }
    }
}

// ─────────────────────────── Inspector window content ───────────────────────────

/**
 * Live snapshot view rendered in the dedicated Inspector window. Three
 * sections (Show / Demote / Hide) listing apps with their windows. Same
 * contents as the pre-split sidebar pane; just hosted in its own
 * NSWindow now and themed via [LocalAppPalette].
 */
@Composable
fun InspectorContent(
    world: World,
    axTrusted: Boolean,
    activeAppPid: Int? = null,
    activeWindowId: WindowId? = null,
    filters: FilteringRules = FilteringRules(),
    currentSpaceOnly: Boolean = false,
    visibleSpaceIds: List<Long> = emptyList(),
    onGrantAxClick: () -> Unit = {},
) {
    val pal = AppPalette
    val snapshot = remember(world, filters, currentSpaceOnly, visibleSpaceIds) {
        world.filteredSnapshot(filters, currentSpaceOnly, visibleSpaceIds)
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(pal.windowBg)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!axTrusted) AxBanner(onGrantAxClick)
        LazyColumn(
            Modifier.fillMaxSize(),
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
        NativeText(
            "$title (${apps.size})",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
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
            .background(AccentColor.copy(alpha = 0.15f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NativeText(
            "⚠  Accessibility permission not granted. Without it the switcher can't see other apps' windows.",
            modifier = Modifier.weight(1f),
            color = AppPalette.textPrimary,
            fontSize = 12.sp,
        )
        NativeButton(label = "Grant…", onClick = onGrantClick, accent = true)
    }
}

@Composable
private fun AppRow(view: AppView, activeAppPid: Int?, activeWindowId: WindowId?) {
    val app = view.app
    val isActiveApp = app.pid == activeAppPid
    val baseColor = AppPalette.textPrimary
    val color = baseColor.dimmedFor(view.mode)
    val pictogram = appPictogram(app, isActiveApp)
    val tags = buildList {
        add(policyTag(app.activationPolicy))
        if (app.isHidden) add("hidden")
        if (!app.isFinishedLaunching) add("launching")
        app.bundleId?.let { add(it) }
    }.joinToString(" · ")
    Column {
        NativeText(
            "$pictogram ${app.name}  ${tagText(tags)}",
            color = color,
            fontWeight = if (isActiveApp) FontWeight.Bold else FontWeight.Normal,
            fontSize = 12.sp,
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
    val baseColor = if (isActive) AppPalette.textPrimary else AppPalette.textSecondary
    val color = baseColor.dimmedFor(view.mode)
    val pictogram = windowPictogram(view, isActive)
    val tags = buildList {
        if (view.mode != TriFilter.Show) add(view.mode.name.lowercase())
        if (!w.role.isNullOrBlank()) add("role: " + w.role)
        if (!w.subrole.isNullOrBlank()) add("subrole: " + w.subrole)
        if (w.isMain) add("main")
        if (w.width != null && w.height != null) add("${w.width.toInt()}×${w.height.toInt()}")
        if (view.children.isNotEmpty()) add("${view.children.size} child")
    }.joinToString(" · ")
    val indent = "    ".repeat(depth)
    NativeText(
        "$indent$pictogram ${effectiveWindowTitle(w.title, appName)}  ${tagText(tags)}",
        color = color,
        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
        fontSize = 11.sp,
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

private fun Color.dimmedFor(mode: TriFilter): Color = when (mode) {
    TriFilter.Show -> this
    TriFilter.Demote -> this.copy(alpha = 0.65f)
    TriFilter.Hide -> this.copy(alpha = 0.35f)
}
