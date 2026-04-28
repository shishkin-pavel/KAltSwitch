package com.shish.kaltswitch.model

import kotlinx.serialization.Serializable

/** Three-way filter: include normally, push to the demoted bucket, or push to the hidden bucket. */
@Serializable
enum class TriFilter { Show, Demote, Hide }

/**
 * User-configurable filters for the inspector window. Live in `WorldStore`,
 * applied by [filteredSnapshot] before the UI renders.
 *
 * Defaults: everything is `Show` except `windowlessApps`, which `Demote`s by
 * default — apps with no current windows are visually less important.
 */
@Serializable
data class Filters(
    // Apps
    val windowlessApps: TriFilter = TriFilter.Demote,
    val accessoryApps: TriFilter = TriFilter.Show,
    val hiddenApps: TriFilter = TriFilter.Show,
    val launchingApps: TriFilter = TriFilter.Show,
    // Windows
    val minimizedWindows: TriFilter = TriFilter.Show,
    val fullscreenWindows: TriFilter = TriFilter.Show,
    val nonStandardSubroleWindows: TriFilter = TriFilter.Show,
    val untitledWindows: TriFilter = TriFilter.Show,
)

/** A window decorated with the filter mode classification. */
data class WindowView(
    val window: Window,
    val mode: TriFilter,
    val children: List<WindowView>,
)

/**
 * An app with its windows already classified and sorted: within `windows`,
 * Show items come first, then Demote, then Hide. Order within each group is
 * the original (recency-driven) order.
 */
data class AppView(
    val app: App,
    val windows: List<WindowView>,
    val mode: TriFilter,
)

/** Three buckets at the app level. The UI renders all three; the eventual switcher overlay would render only `show`. */
data class FilteredSnapshot(
    val show: List<AppView>,
    val demote: List<AppView>,
    val hide: List<AppView>,
) {
    val all: List<AppView> get() = show + demote + hide
}

/**
 * Apply filters to the world's raw snapshot. Each app and each window get a
 * `TriFilter` mode (the strictest matching rule wins: `Hide > Demote > Show`),
 * then apps are partitioned into three buckets and windows within each app are
 * sorted by mode.
 */
fun World.filteredSnapshot(filters: Filters): FilteredSnapshot {
    val raw = snapshot()
    val all = raw.withWindows + raw.windowless

    val show = mutableListOf<AppView>()
    val demote = mutableListOf<AppView>()
    val hide = mutableListOf<AppView>()

    for (entry in all) {
        val winViews = entry.windows
            .map { classifyWindow(it, filters) }
            .sortedBy(::modeOrder)
        val mode = classifyApp(entry, filters)
        val view = AppView(entry.app, winViews, mode)
        when (mode) {
            TriFilter.Show -> show.add(view)
            TriFilter.Demote -> demote.add(view)
            TriFilter.Hide -> hide.add(view)
        }
    }

    return FilteredSnapshot(show, demote, hide)
}

private fun classifyApp(entry: AppEntry, f: Filters): TriFilter {
    val a = entry.app
    var mode = TriFilter.Show
    if (entry.windows.isEmpty()) mode = strictest(mode, f.windowlessApps)
    if (a.activationPolicy == AppActivationPolicy.Accessory) mode = strictest(mode, f.accessoryApps)
    if (a.isHidden) mode = strictest(mode, f.hiddenApps)
    if (!a.isFinishedLaunching) mode = strictest(mode, f.launchingApps)
    return mode
}

private fun classifyWindow(w: Window, f: Filters): WindowView {
    var mode = TriFilter.Show
    if (w.isMinimized) mode = strictest(mode, f.minimizedWindows)
    if (w.isFullscreen) mode = strictest(mode, f.fullscreenWindows)
    if (w.title.isBlank()) mode = strictest(mode, f.untitledWindows)
    val isStandard = w.subrole == "AXStandardWindow"
    if (!isStandard) mode = strictest(mode, f.nonStandardSubroleWindows)

    val childViews = w.children
        .map { classifyWindow(it, f) }
        .sortedBy(::modeOrder)
    return WindowView(w, mode, childViews)
}

/** [TriFilter.Hide] is strictest, [TriFilter.Show] is loosest. */
private fun strictest(a: TriFilter, b: TriFilter): TriFilter =
    if (modeOrder(a) >= modeOrder(b)) a else b

private fun modeOrder(v: TriFilter): Int = when (v) {
    TriFilter.Show -> 0
    TriFilter.Demote -> 1
    TriFilter.Hide -> 2
}

private fun modeOrder(v: WindowView): Int = modeOrder(v.mode)
