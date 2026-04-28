package com.shish.kaltswitch.model

/** Three-way filter: include normally, push to the demoted bucket at the end, or drop entirely. */
enum class TriFilter { Show, Demote, Hide }

/** Two-way filter for cases where Demote doesn't make sense. */
enum class BiFilter { Show, Hide }

/**
 * User-configurable filters for the inspector window. Live in `WorldStore`,
 * applied by [filteredSnapshot] before the UI renders.
 */
data class Filters(
    // Apps
    val windowlessApps: TriFilter = TriFilter.Demote,         // current default: own section at the end
    val accessoryApps: TriFilter = TriFilter.Show,
    val hiddenApps: TriFilter = TriFilter.Show,
    val launchingApps: BiFilter = BiFilter.Show,

    // Windows (Demote within an app's window list isn't obviously useful yet — keep
    // these binary; we can revisit if "minimized goes to bottom" feels natural later).
    val minimizedWindows: BiFilter = BiFilter.Show,
    val fullscreenWindows: BiFilter = BiFilter.Show,
    val nonStandardSubroleWindows: BiFilter = BiFilter.Show,  // anything not AXStandardWindow
    val untitledWindows: BiFilter = BiFilter.Show,
)

/** Result of applying [Filters] to a [SwitcherSnapshot]. */
data class FilteredSnapshot(
    val primary: List<AppEntry>,
    val windowless: List<AppEntry>,
    val demoted: List<AppEntry>,
)

/**
 * Apply filters to the world's raw snapshot. Returns three buckets:
 *  - `primary`: apps with windows, kept in the main section
 *  - `windowless`: apps that have no (post-filter) windows, in their own section
 *  - `demoted`: apps that the filter classified as "Demote", regardless of windows
 */
fun World.filteredSnapshot(filters: Filters): FilteredSnapshot {
    val raw = snapshot()

    val primary = mutableListOf<AppEntry>()
    val windowless = mutableListOf<AppEntry>()
    val demoted = mutableListOf<AppEntry>()

    val all = raw.withWindows + raw.windowless

    for (entry in all) {
        // 1. Filter the windows of this app.
        val keptWindows = entry.windows.mapNotNull { applyWindowFilters(it, filters) }
        val newEntry = entry.copy(windows = keptWindows)

        // 2. Decide which bucket the app goes into.
        val mode = classifyApp(newEntry, filters)
        when (mode) {
            TriFilter.Hide -> { /* drop */ }
            TriFilter.Demote -> demoted.add(newEntry)
            TriFilter.Show -> {
                if (keptWindows.isEmpty() && filters.windowlessApps == TriFilter.Show) {
                    // app shows but has no windows after filter → windowless section
                    windowless.add(newEntry)
                } else if (keptWindows.isEmpty()) {
                    // windowless filter is Demote/Hide and applied above; if Show, falls in windowless.
                    windowless.add(newEntry)
                } else {
                    primary.add(newEntry)
                }
            }
        }
    }

    return FilteredSnapshot(primary, windowless, demoted)
}

private fun classifyApp(entry: AppEntry, f: Filters): TriFilter {
    val a = entry.app
    // First the absolute Hide rules — once any rule says Hide, we drop.
    if (!a.isFinishedLaunching && f.launchingApps == BiFilter.Hide) return TriFilter.Hide
    if (a.isHidden && f.hiddenApps == TriFilter.Hide) return TriFilter.Hide
    if (a.activationPolicy == AppActivationPolicy.Accessory && f.accessoryApps == TriFilter.Hide) return TriFilter.Hide
    if (entry.windows.isEmpty() && f.windowlessApps == TriFilter.Hide) return TriFilter.Hide

    // Demote rules — if any matches, demote.
    if (a.isHidden && f.hiddenApps == TriFilter.Demote) return TriFilter.Demote
    if (a.activationPolicy == AppActivationPolicy.Accessory && f.accessoryApps == TriFilter.Demote) return TriFilter.Demote
    if (entry.windows.isEmpty() && f.windowlessApps == TriFilter.Demote) return TriFilter.Demote

    return TriFilter.Show
}

/**
 * Recursively filter a window subtree. Returns the rewritten window with
 * filtered children, or `null` to drop the whole subtree.
 *
 * Note: child windows are also subject to filtering. A demoted child is dropped
 * since "demote within an app's window list" doesn't have a natural meaning yet
 * — leave that to a future enhancement.
 */
private fun applyWindowFilters(w: Window, f: Filters): Window? {
    if (w.isMinimized && f.minimizedWindows == BiFilter.Hide) return null
    if (w.isFullscreen && f.fullscreenWindows == BiFilter.Hide) return null
    if (w.title.isBlank() && f.untitledWindows == BiFilter.Hide) return null
    val isStandard = w.subrole == "AXStandardWindow"
    if (!isStandard && f.nonStandardSubroleWindows == BiFilter.Hide) return null

    // Filter children recursively.
    val keptChildren = w.children.mapNotNull { applyWindowFilters(it, f) }
    return w.copy(children = keptChildren)
}
