package com.shish.kaltswitch.model

/** macOS process id. Underlying type is `Int` because that's what
 *  `NSRunningApplication.processIdentifier` and the AX APIs return. The
 *  alias is documentation only — the compiler still treats it as `Int`,
 *  so this won't catch swap-with-`appIndex` mistakes; rely on review and
 *  parameter naming for that. We intentionally avoided value classes here
 *  because K/N doesn't unwrap value-class types in nullable scalar / Map
 *  / collection positions, which the Swift bridge runs into immediately
 *  (`recordActivation(pid: Pid, windowId: WindowId?)` shows up to Swift
 *  as `(Int32, id _Nullable)` with no Swift-accessible accessor for the
 *  underlying primitive — see commit log for the crash that taught us). */
typealias Pid = Int

typealias WindowId = Long

enum class AppActivationPolicy { Regular, Accessory, Prohibited }

/**
 * A running macOS application. Most fields come from `NSRunningApplication`; `isHidden`
 * tracks `cmd+H` state via AX `kAXApplicationHidden/Shown` notifications.
 */
data class App(
    val pid: Pid,
    val bundleId: String?,
    val name: String,
    val activationPolicy: AppActivationPolicy = AppActivationPolicy.Regular,
    val isHidden: Boolean = false,
    val isFinishedLaunching: Boolean = true,
    val executablePath: String? = null,
    val launchDateMillis: Long? = null,
    /** Dock-tile badge string ("5", "•", etc.) read from the macOS Dock's
     *  AX `AXStatusLabel`. `null` = no badge / unknown. Updated independently
     *  of the rest of the record by `DockBadgeWatcher`, so [WorldStore.upsertApp]
     *  is careful to preserve it across NSWorkspace-driven re-upserts. */
    val badgeText: String? = null,
)

/**
 * One window of an application. All boolean state and the title come from AX attributes
 * read by the per-app watcher; we keep them on the model so the UI can render pictograms.
 */
data class Window(
    val id: WindowId,
    val pid: Pid,
    val title: String,
    val role: String? = null,
    val subrole: String? = null,
    val isMinimized: Boolean = false,
    val isFullscreen: Boolean = false,
    val isFocused: Boolean = false,
    val isMain: Boolean = false,
    val x: Double? = null,
    val y: Double? = null,
    val width: Double? = null,
    val height: Double? = null,
    /** Child windows (sheets, drawers, popovers) attached to this one via kAXChildWindowsAttribute. */
    val children: List<Window> = emptyList(),
    /** Mission Control space IDs this window belongs to. Populated by the
     *  Swift side via `CGSCopySpacesForWindows`. Empty when the data isn't
     *  available (e.g. the private API was unhappy, or we haven't refreshed
     *  yet) — the classifier treats empty as "skip the space filter". */
    val spaceIds: List<Long> = emptyList(),
)

sealed interface Group {
    val displayName: String
}

data class AppGroup(val app: App) : Group {
    override val displayName: String = app.name
}
