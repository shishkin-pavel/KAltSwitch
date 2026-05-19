package com.shish.kaltswitch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
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
import com.shish.kaltswitch.model.BadgeRules
import com.shish.kaltswitch.model.FilteredSnapshot
import com.shish.kaltswitch.model.FilteringRules
import com.shish.kaltswitch.model.PinningRules
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
    panelBgArgb: Long,
    onPanelBgArgbChange: (Long) -> Unit,
    demoteBgArgb: Long,
    onDemoteBgArgbChange: (Long) -> Unit,
    filters: FilteringRules,
    onFiltersChange: (FilteringRules) -> Unit,
    badgeRules: BadgeRules,
    onBadgeRulesChange: (BadgeRules) -> Unit,
    pinningRules: PinningRules,
    onPinningRulesChange: (PinningRules) -> Unit,
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
                items = listOf("General", "Rules", "Badges", "Pinning"),
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
                    panelBgArgb = panelBgArgb,
                    onPanelBgArgbChange = onPanelBgArgbChange,
                    demoteBgArgb = demoteBgArgb,
                    onDemoteBgArgbChange = onDemoteBgArgbChange,
                )
                1 -> FilteringRulesPanel(
                    filters = filters,
                    onChange = onFiltersChange,
                )
                2 -> BadgeRulesPanel(
                    rules = badgeRules,
                    onChange = onBadgeRulesChange,
                )
                3 -> PinningRulesPanel(
                    rules = pinningRules,
                    onChange = onPinningRulesChange,
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
    panelBgArgb: Long,
    onPanelBgArgbChange: (Long) -> Unit,
    demoteBgArgb: Long,
    onDemoteBgArgbChange: (Long) -> Unit,
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
        NativeGroupBox(title = "Switcher overlay colours") {
            ColorSwatchRow(
                label = "Panel background",
                argb = panelBgArgb,
                onChange = onPanelBgArgbChange,
            )
            NativeRowDivider()
            ColorSwatchRow(
                label = "Demoted backdrop",
                argb = demoteBgArgb,
                onChange = onDemoteBgArgbChange,
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
    val customArgb = (choice as? AccentColorChoice.Custom)?.argb ?: 0xFFFFC107L
    NativeRow(label = "Use system colour") {
        NativeToggle(
            checked = isSystem,
            onCheckedChange = { wantSystem ->
                onChange(if (wantSystem) AccentColorChoice.UseSystem else AccentColorChoice.Custom(customArgb))
            },
        )
    }
    if (!isSystem) {
        NativeRowDivider()
        ColorSwatchRow(
            label = "Custom colour",
            argb = customArgb,
            onChange = { argb -> onChange(AccentColorChoice.Custom(argb)) },
        )
    }
}

// ─────────────────────────── Inspector window content ───────────────────────────

/**
 * Live snapshot rendered in the Inspector window. Three sections
 * (Show / Demote / Hide) stack vertically; within each section the apps
 * stack vertically too, each with an inline horizontally-scrolling strip
 * of **window plates**.
 *
 * Each plate carries: title · status chips (focused/main/min/fullscreen
 * /role/subrole) · classifier chip (the rule whose predicates fired, with
 * a TriFilter-colour tint) · dimensions. Click a plate to toggle a
 * detail panel that dumps every AX + CG field on the window — the
 * fastest path from "this row looks wrong" to "ok, here's exactly what
 * we see".
 *
 * Why horizontally-scrolling plates rather than the old wrap-to-window-
 * width text rows: a debug-heavy app like ChatGPT can sport 5+ phantom
 * rows under one header, and stacking them vertically pushed apps
 * off-screen on every refresh. Horizontal strip = constant vertical
 * footprint per app + the user sees all windows of an app side-by-side
 * in classifier order.
 */
@Composable
fun InspectorContent(
    world: World,
    axTrusted: Boolean,
    activeAppPid: Int? = null,
    activeWindowId: WindowId? = null,
    filters: FilteringRules = FilteringRules(),
    pinning: PinningRules = PinningRules(),
    currentSpaceOnly: Boolean = false,
    visibleSpaceIds: List<Long> = emptyList(),
    onGrantAxClick: () -> Unit = {},
) {
    val pal = AppPalette
    val snapshot = remember(world, filters, pinning, currentSpaceOnly, visibleSpaceIds) {
        world.filteredSnapshot(filters, pinning, currentSpaceOnly, visibleSpaceIds)
    }
    // Plate-expansion state is owned here and survives recomposition, so
    // a Hide-bucket plate stays open across re-snapshots while the user
    // is reading it. `rememberSaveable` would also persist across Inspector
    // window close/reopen — overkill for now; debug state is fine to lose
    // on close.
    var expandedWindowIds by remember { mutableStateOf(setOf<WindowId>()) }
    val toggleExpanded: (WindowId) -> Unit = { id ->
        expandedWindowIds = if (id in expandedWindowIds) expandedWindowIds - id else expandedWindowIds + id
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
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            modeSection("Show", snapshot.show, activeAppPid, activeWindowId, expandedWindowIds, toggleExpanded)
            if (snapshot.demote.isNotEmpty()) {
                item { Spacer(Modifier.height(10.dp)) }
                modeSection("Demote", snapshot.demote, activeAppPid, activeWindowId, expandedWindowIds, toggleExpanded)
            }
            if (snapshot.hide.isNotEmpty()) {
                item { Spacer(Modifier.height(10.dp)) }
                modeSection("Hide", snapshot.hide, activeAppPid, activeWindowId, expandedWindowIds, toggleExpanded)
            }
        }
    }
}

private fun LazyListScope.modeSection(
    title: String,
    apps: List<AppView>,
    activeAppPid: Int?,
    activeWindowId: WindowId?,
    expandedIds: Set<WindowId>,
    toggleExpanded: (WindowId) -> Unit,
) {
    item {
        NativeText(
            "$title (${apps.size})",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
    items(apps) { entry ->
        AppBlock(entry, activeAppPid, activeWindowId, expandedIds, toggleExpanded)
    }
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

/**
 * One app's block: header line + horizontally-scrolling window-plate strip
 * underneath. Children of a window are flattened into the same strip with
 * a depth chip — sub-windows are still rare enough that a separate row
 * would waste vertical real-estate.
 */
@Composable
private fun AppBlock(
    view: AppView,
    activeAppPid: Int?,
    activeWindowId: WindowId?,
    expandedIds: Set<WindowId>,
    toggleExpanded: (WindowId) -> Unit,
) {
    val app = view.app
    val isActiveApp = app.pid == activeAppPid
    val baseColor = AppPalette.textPrimary
    val headerColor = baseColor.dimmedFor(view.mode)
    val pictogram = appPictogram(app, isActiveApp)
    val tags = buildList {
        add(policyTag(app.activationPolicy))
        if (app.isHidden) add("hidden")
        if (!app.isFinishedLaunching) add("launching")
        app.bundleId?.let { add(it) }
    }.joinToString(" · ")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NativeText(
                "$pictogram ${app.name}  ${tagText(tags)}",
                color = headerColor,
                fontWeight = if (isActiveApp) FontWeight.Bold else FontWeight.Normal,
                fontSize = 12.sp,
            )
            view.firingRule?.let { rule ->
                // App-level firing rule: came from the windowless-app
                // second pass. Per-window rules surface on the plates
                // themselves; this chip only appears for apps that have
                // no surviving windows.
                ClassifierChip(view.mode, rule)
            }
        }
        val plates = flattenWindows(view.windows, depth = 0)
        if (plates.isEmpty()) {
            // Pure windowless apps (e.g. menubar utilities) get no strip.
            // The header + app-level chip already say everything there is.
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                plates.forEach { (wv, depth) ->
                    Column {
                        WindowPlate(
                            view = wv,
                            depth = depth,
                            appName = app.name,
                            isActive = isActiveApp && wv.window.id == activeWindowId,
                            isExpanded = wv.window.id in expandedIds,
                            onClick = { toggleExpanded(wv.window.id) },
                        )
                        if (wv.window.id in expandedIds) {
                            WindowDetailPanel(wv, appName = app.name)
                        }
                    }
                }
            }
        }
    }
}

/** Recurse a list of [WindowView] subtrees, flattening into (view, depth)
 *  pairs in pre-order. We render children as additional plates in the same
 *  horizontal strip rather than nesting, which keeps the per-app vertical
 *  footprint constant regardless of subtree shape. */
private fun flattenWindows(roots: List<WindowView>, depth: Int): List<Pair<WindowView, Int>> {
    val out = mutableListOf<Pair<WindowView, Int>>()
    fun visit(v: WindowView, d: Int) {
        out.add(v to d)
        v.children.forEach { visit(it, d + 1) }
    }
    roots.forEach { visit(it, depth) }
    return out
}

/**
 * One window's plate: rounded rectangle with title + chips + dimensions,
 * tinted by [WindowView.mode]. Clicking toggles the expanded detail
 * panel that the parent renders below.
 */
@Composable
private fun WindowPlate(
    view: WindowView,
    depth: Int,
    appName: String,
    isActive: Boolean,
    isExpanded: Boolean,
    onClick: () -> Unit,
) {
    val w = view.window
    val pal = AppPalette
    val (bg, border) = plateColorsFor(view.mode, isActive)
    Column(
        Modifier
            .widthIn(min = 200.dp, max = 320.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(
                width = if (isActive) 1.5.dp else 1.dp,
                color = border,
                shape = RoundedCornerShape(6.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        NativeText(
            text = effectiveWindowTitle(w.title, appName),
            color = pal.textPrimary,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
            fontSize = 12.sp,
        )
        // Status + classifier chips on one flow line. FlowRow would be
        // ideal but the chip set rarely exceeds 4 entries — plain Row
        // with horizontal scroll handled by the parent is enough.
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (depth > 0) StatusChip("child·$depth")
            if (isActive) StatusChip("focused", accent = true)
            if (w.isMain) StatusChip("main")
            if (w.isMinimized) StatusChip("min")
            if (w.isFullscreen) StatusChip("full")
            if (!w.role.isNullOrBlank() && w.role != "AXWindow") StatusChip(w.role)
            view.firingRule?.let { rule -> ClassifierChip(view.mode, rule) }
            if (view.firingRule == null && view.mode == TriFilter.Show) {
                // Default-show is implicit but worth labelling so the
                // user can tell "no rule fired" apart from "I forgot
                // to label the chip".
                ClassifierChip(view.mode, null)
            }
        }
        val dims = if (w.width != null && w.height != null) "${w.width.toInt()} × ${w.height.toInt()}" else "—"
        NativeText(
            text = dims + if (isExpanded) "  ▾" else "  ▸",
            color = pal.textSecondary,
            fontSize = 11.sp,
        )
    }
}

/** Bottom of an expanded plate: every AX + CG field we know about, in
 *  rough source order (identity first, AX next, then CG). Two-column
 *  key/value rows; null / blank values stay visible so the user can tell
 *  "didn't bother to read" from "field is null on purpose". */
@Composable
private fun WindowDetailPanel(view: WindowView, appName: String) {
    val w = view.window
    val pal = AppPalette
    val rows = buildList<Pair<String, String>> {
        add("id" to w.id.toString())
        add("pid" to w.pid.toString())
        add("title" to (if (w.title.isBlank()) "(blank → '${effectiveWindowTitle(w.title, appName)}')" else w.title))
        add("mode" to view.mode.name)
        add("rule" to (view.firingRule?.let { "${it.name.ifBlank { it.id }}" } ?: "(default Show)"))
        add("role" to (w.role ?: "—"))
        add("subrole" to (w.subrole ?: "—"))
        add("isMain / isFocused" to "${w.isMain} / ${w.isFocused}")
        add("isMinimized / isFullscreen" to "${w.isMinimized} / ${w.isFullscreen}")
        add("frame" to "${w.x?.toInt() ?: "?"},${w.y?.toInt() ?: "?"} ${w.width?.toInt() ?: "?"}×${w.height?.toInt() ?: "?"}")
        add("cgWindowId" to (w.cgWindowId?.toString() ?: "—"))
        add("cgLayer / cgAlpha" to "${w.cgLayer ?: "—"} / ${w.cgAlpha ?: "—"}")
        add("ownerName" to (w.ownerName ?: "—"))
        add("isOnscreen / onVisibleSpace" to "${w.isOnscreen ?: "—"} / ${w.isOnVisibleSpace ?: "—"}")
        add("spaceIds" to (if (w.spaceIds.isEmpty()) "[]" else w.spaceIds.joinToString()))
        add("children" to view.children.size.toString())
    }
    Column(
        Modifier
            .widthIn(min = 200.dp, max = 320.dp)
            .padding(top = 4.dp, start = 4.dp, end = 4.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        rows.forEach { (k, v) ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NativeText(
                    k,
                    color = pal.textSecondary,
                    fontSize = 10.sp,
                    modifier = Modifier.width(120.dp),
                )
                NativeText(v, color = pal.textPrimary, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun StatusChip(text: String, accent: Boolean = false) {
    val pal = AppPalette
    val bg = if (accent) AccentColor.copy(alpha = 0.18f) else pal.textSecondary.copy(alpha = 0.12f)
    val fg = if (accent) AccentColor else pal.textSecondary
    Box(
        Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(bg)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        NativeText(text, color = fg, fontSize = 10.sp)
    }
}

/** Classifier chip: shows the firing rule's name (or "Show (default)"
 *  when no rule matched) with a colour tint that mirrors the TriFilter
 *  outcome. The rule chip is the single most important new affordance —
 *  it answers "why did this window land here" without leaving the
 *  inspector window. */
@Composable
private fun ClassifierChip(mode: TriFilter, rule: com.shish.kaltswitch.model.Rule?) {
    val (label, fg) = when (mode) {
        TriFilter.Show -> (rule?.let { displayRuleName(it) } ?: "Show (default)") to Color(0xFF60C065)
        TriFilter.Demote -> ("Demote: " + (rule?.let { displayRuleName(it) } ?: "(default)")) to Color(0xFFE0A040)
        TriFilter.Hide -> ("Hide: " + (rule?.let { displayRuleName(it) } ?: "(default)")) to Color(0xFFE57373)
    }
    val bg = fg.copy(alpha = 0.18f)
    Box(
        Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(bg)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        NativeText(label, color = fg, fontSize = 10.sp)
    }
}

private fun displayRuleName(rule: com.shish.kaltswitch.model.Rule): String =
    rule.name.ifBlank { rule.id }

/** Plate background + border, tinted by classification + active state.
 *  Background is a faint version of the outcome colour so the user can
 *  scan the inspector for "amber stripes = my Demote rules fired"
 *  without reading any text. `@Composable` because the active-state
 *  border uses [AccentColor] from the surrounding palette. */
@Composable
private fun plateColorsFor(mode: TriFilter, isActive: Boolean): Pair<Color, Color> {
    val accent = when (mode) {
        TriFilter.Show -> Color(0xFF60C065)
        TriFilter.Demote -> Color(0xFFE0A040)
        TriFilter.Hide -> Color(0xFF888888)
    }
    val bg = accent.copy(alpha = 0.06f)
    val border = if (isActive) AccentColor else accent.copy(alpha = 0.45f)
    return bg to border
}

private fun appPictogram(app: com.shish.kaltswitch.model.App, isActive: Boolean): String = when {
    isActive -> "▶"
    app.isHidden -> "◌"
    !app.isFinishedLaunching -> "…"
    app.activationPolicy == AppActivationPolicy.Accessory -> "◇"
    else -> "•"
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
