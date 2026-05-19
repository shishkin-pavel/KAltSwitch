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
 * Which native subsystem currently *sees* a particular [Window]. Stored on
 * the window itself as [Window.sources]; mutators flip membership as each
 * source observes / loses sight of the window.
 *
 * The merge convention is **no source is privileged**: a window is alive
 * iff at least one source has it in view. AX losing visibility doesn't
 * imply destruction (the window may have moved to a different Mission
 * Control space, which AX is silently filtered out from), CG losing
 * visibility doesn't either (CG-only cross-space windows can blip on
 * short refresh gaps). The window is dropped exactly when [Window.sources]
 * becomes empty. See `WorldStore.applyAxSnapshot` / `applyCgSnapshot`
 * for the per-source mutation rules.
 */
@kotlinx.serialization.Serializable
enum class WindowSource { AX, CG }

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
    /** WindowServer-level identifier resolved via `_AXUIElementGetWindow`
     *  on the Swift side (for AX-derived windows) or read directly from
     *  `kCGWindowNumber` (for phantom windows enumerated through
     *  `CGWindowListCopyWindowInfo` to surface cross-space entries).
     *
     *  Used as the de-dup key when merging the AX and phantom window
     *  streams in [com.shish.kaltswitch.store.WorldStore], and as the
     *  commit-time fallback that lets the SkyLight focus call target a
     *  specific window when no live AX element exists for it (windows
     *  on a different Mission Control space). `null` when the resolver
     *  failed — typically a transient AX-element-not-yet-bridged state. */
    val cgWindowId: Long? = null,
    // ─────────── CGWindowList-only attributes (phantom windows) ───────────
    //
    // These five fields carry information that's only available through
    // `CGWindowListCopyWindowInfo`, used by the phantom enumerator to
    // surface cross-space windows. All are `null` for AX-derived rows —
    // AX doesn't expose them, and forging values from AX-only fields
    // would either lie or surprise. Predicates that target them
    // therefore evaluate to `false` against AX rows (matchNumber/matchString
    // on null already does the right thing), which lets a single rule
    // chain handle both sources without an `if cgPhantom` toggle.
    //
    // [isOnVisibleSpace] is the only derived field — Swift computes it
    // at enumeration time by intersecting [spaceIds] with the WorldStore's
    // current `visibleSpaceIds`. Doing it there (rather than re-deriving
    // inside [Predicate.matches]) keeps predicate evaluation ambient-
    // context-free, matching how every other window-side predicate works.

    /** CGWindowServer's `kCGWindowIsOnscreen` — true iff this window is
     *  currently visible on a connected display. The discriminator that
     *  separates "real user-facing window" from "hidden helper / preview
     *  popover the app keeps around for instant reuse" on the *current*
     *  space. Cross-space real windows also report false here, so a rule
     *  that drops `isOnscreen=false` must AND with [isOnVisibleSpace]=true
     *  to avoid hiding legitimate off-space rows. */
    val isOnscreen: Boolean? = null,
    /** Pre-computed in Swift: true iff at least one of this window's
     *  [spaceIds] is in the WorldStore's `visibleSpaceIds`. Lets a
     *  predicate distinguish "on the current space" from "on another
     *  space" without needing to feed [visibleSpaceIds] into evaluation. */
    val isOnVisibleSpace: Boolean? = null,
    /** `kCGWindowLayer`. Zero = regular app window. Positive layers
     *  belong to system overlays (menu bar, Dock tile, status items),
     *  which we don't want in the switcher. Negative values exist but
     *  are uncommon. */
    val cgLayer: Int? = null,
    /** `kCGWindowAlpha` (0–1). Zero-alpha entries are typically stub
     *  windows the WindowServer keeps around but never draws. */
    val cgAlpha: Double? = null,
    /** `kCGWindowOwnerName` — the bundle's CFBundleName as seen by the
     *  WindowServer. For user apps it equals [App.name]; for system
     *  helpers ("Window Server", "Dock", "Control Center", …) the only
     *  way to address them in rules. */
    val ownerName: String? = null,
    /** Which native subsystems currently have this window in view.
     *  Drives the drop decision in [com.shish.kaltswitch.store.WorldStore]:
     *  a window is removed exactly when [sources] becomes empty. Empty
     *  default is for tests and other constructors that don't care about
     *  source bookkeeping — the live mutators always set it. */
    val sources: Set<WindowSource> = emptySet(),
)

sealed interface Group {
    val displayName: String
}

data class AppGroup(val app: App) : Group {
    override val displayName: String = app.name
}
