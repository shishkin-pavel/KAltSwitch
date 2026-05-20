package com.shish.kaltswitch.store

import com.shish.kaltswitch.config.AccentColorChoice
import com.shish.kaltswitch.config.AppConfig
import com.shish.kaltswitch.config.SwitcherSettings
import com.shish.kaltswitch.config.sanitized
import com.shish.kaltswitch.model.ActivationEvent
import com.shish.kaltswitch.model.ActivationLog
import com.shish.kaltswitch.model.App
import com.shish.kaltswitch.model.AppActivationPolicy
import com.shish.kaltswitch.model.BadgeRules
import com.shish.kaltswitch.model.FilteringRules
import com.shish.kaltswitch.model.Pid
import com.shish.kaltswitch.model.PinningRules
import com.shish.kaltswitch.model.Window
import com.shish.kaltswitch.model.WindowId
import com.shish.kaltswitch.model.WindowSource
import com.shish.kaltswitch.model.World
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update

/**
 * Mutable holder of the current [World]. UI observes [state]; native watchers (Swift
 * `AppRegistry` / `AxAppWatcher` over the framework boundary) call the mutators.
 *
 * All public methods are safe to call from any thread; under the hood `MutableStateFlow`
 * is atomic.
 */
class WorldStore(initial: World = World(ActivationLog(), emptyMap(), emptyMap())) {
    // Initial-state convenience: any Window pre-populated through the
    // [World] constructor — typical in unit tests and at startup-before-
    // first-snapshot — is treated as **AX-known**. Without this the
    // first `applyAxSnapshot` call wouldn't retract those windows (the
    // retraction branch is gated on `AX in sources`), and the tests'
    // "setWindows replaces the AX list" intent would silently break.
    // Production paths never go through the constructor with non-empty
    // windowsByPid — they call mutators that set sources themselves —
    // so the normalisation is a no-op in the live build.
    private val _state = MutableStateFlow(
        initial.copy(
            windowsByPid = initial.windowsByPid.mapValues { (_, ws) ->
                ws.map(::ensureAxSourceRecursively)
            },
        ),
    )
    val state: StateFlow<World> = _state.asStateFlow()

    private val _axTrusted = MutableStateFlow(true)
    val axTrusted: StateFlow<Boolean> = _axTrusted.asStateFlow()

    private val _activeAppPid = MutableStateFlow<Pid?>(null)
    val activeAppPid: StateFlow<Pid?> = _activeAppPid.asStateFlow()

    private val _activeWindowId = MutableStateFlow<WindowId?>(null)
    val activeWindowId: StateFlow<WindowId?> = _activeWindowId.asStateFlow()

    private val _filters = MutableStateFlow(FilteringRules())
    val filters: StateFlow<FilteringRules> = _filters.asStateFlow()

    fun setFilters(f: FilteringRules) {
        _filters.value = f
    }

    /** Title-pattern → (text, colour) badge rules driving the Settings →
     *  Badges tab and the custom-badge pill rendered in the switcher overlay. */
    private val _badgeRules = MutableStateFlow(BadgeRules())
    val badgeRules: StateFlow<BadgeRules> = _badgeRules.asStateFlow()

    fun setBadgeRules(b: BadgeRules) {
        _badgeRules.value = b
    }

    /** Predicate-based pinning rules: matching top-level windows are
     *  re-parented under the most recently activated root of the same app.
     *  Consumed by [World.applyPinning] inside `snapshot()`. */
    private val _pinning = MutableStateFlow(PinningRules())
    val pinning: StateFlow<PinningRules> = _pinning.asStateFlow()

    fun setPinning(p: PinningRules) {
        _pinning.value = p
    }

    private val _switcherSettings = MutableStateFlow(SwitcherSettings())
    val switcherSettings: StateFlow<SwitcherSettings> = _switcherSettings.asStateFlow()

    /** Sanitised on the way in so the runtime path (coroutine `delay`) never
     *  sees a negative value or a zero `repeatIntervalMs` that would tight-loop.
     *  Both config-load (`applyConfig`) and the settings UI go through here. */
    fun setSwitcherSettings(s: SwitcherSettings) {
        _switcherSettings.value = s.sanitized()
    }

    private val _showMenubarIcon = MutableStateFlow(true)
    val showMenubarIcon: StateFlow<Boolean> = _showMenubarIcon.asStateFlow()

    fun setShowMenubarIcon(show: Boolean) {
        _showMenubarIcon.value = show
    }

    private val _launchAtLogin = MutableStateFlow(false)
    val launchAtLogin: StateFlow<Boolean> = _launchAtLogin.asStateFlow()

    fun setLaunchAtLogin(enabled: Boolean) {
        _launchAtLogin.value = enabled
    }

    /** When true, the classifier hides windows that aren't on any of
     *  [visibleSpaceIds]. Default false = show windows from every space. */
    private val _currentSpaceOnly = MutableStateFlow(false)
    val currentSpaceOnly: StateFlow<Boolean> = _currentSpaceOnly.asStateFlow()

    fun setCurrentSpaceOnly(enabled: Boolean) {
        _currentSpaceOnly.value = enabled
    }

    /** Master switch for the file at `~/Library/Logs/KAltSwitch.log`. The
     *  macOS-side observer in `ComposeView` pushes this into the
     *  `com.shish.kaltswitch.log.loggingEnabled` package-level flag that
     *  [com.shish.kaltswitch.log.log] consults on every call. */
    private val _loggingEnabled = MutableStateFlow(true)
    val loggingEnabled: StateFlow<Boolean> = _loggingEnabled.asStateFlow()

    fun setLoggingEnabled(enabled: Boolean) {
        _loggingEnabled.value = enabled
    }

    /** Mission Control "current" space IDs across every connected display.
     *  Updated by the Swift side on `NSWorkspace.activeSpaceDidChangeNotification`.
     *  Empty list means we don't have the data — the classifier treats that
     *  as "feature unavailable" and skips the space filter regardless of
     *  [currentSpaceOnly]. */
    private val _visibleSpaceIds = MutableStateFlow<List<Long>>(emptyList())
    val visibleSpaceIds: StateFlow<List<Long>> = _visibleSpaceIds.asStateFlow()

    fun setVisibleSpaceIds(ids: List<Long>) {
        _visibleSpaceIds.value = ids
    }

    /** User-selected accent. Resolved to an actual RGB by combining with
     *  [systemAccentRgb] downstream (see ComposeView.effectiveAccentRgb).
     *  The Long is ARGB-packed since v7. */
    private val _accentColor = MutableStateFlow<AccentColorChoice>(AccentColorChoice.Custom(0xFFFFC107L))
    val accentColor: StateFlow<AccentColorChoice> = _accentColor.asStateFlow()

    fun setAccentColor(choice: AccentColorChoice) {
        _accentColor.value = choice
    }

    /** ARGB-packed switcher panel backdrop. Defaults to the originally
     *  hardcoded near-black; persisted via [AppConfig.switcherPanelBgArgb]. */
    private val _switcherPanelBgArgb = MutableStateFlow(0xFF1B1B1FL)
    val switcherPanelBgArgb: StateFlow<Long> = _switcherPanelBgArgb.asStateFlow()

    fun setSwitcherPanelBgArgb(argb: Long) {
        _switcherPanelBgArgb.value = argb
    }

    /** ARGB-packed tint laid over the panel inside the demoted block.
     *  Alpha is meaningful — the panel plate shows through. */
    private val _switcherDemoteBgArgb = MutableStateFlow(0x33000000L)
    val switcherDemoteBgArgb: StateFlow<Long> = _switcherDemoteBgArgb.asStateFlow()

    fun setSwitcherDemoteBgArgb(argb: Long) {
        _switcherDemoteBgArgb.value = argb
    }

    /** Swift pushes `NSColor.controlAccentColor` packed as 0xRRGGBB whenever
     *  the system colour changes (via `NSSystemColorsDidChangeNotification`).
     *  Null means we haven't read it yet — UI falls back to the Custom default. */
    private val _systemAccentRgb = MutableStateFlow<Long?>(null)
    val systemAccentRgb: StateFlow<Long?> = _systemAccentRgb.asStateFlow()

    fun setSystemAccentRgb(rgb: Long) {
        _systemAccentRgb.value = rgb
    }

    /** Compose-reported size of the visible switcher panel (in dp).
     *  Swift uses it to resize the NSPanel to match the visible content
     *  rect on every layout pass. Null when there is no active session. */
    private val _switcherPanelSize = MutableStateFlow<Pair<Double, Double>?>(null)
    val switcherPanelSize: StateFlow<Pair<Double, Double>?> = _switcherPanelSize.asStateFlow()

    fun setSwitcherPanelSize(width: Double, height: Double) {
        _switcherPanelSize.value = width to height
    }

    fun clearSwitcherPanelSize() {
        _switcherPanelSize.value = null
    }

    /** Per-pid PNG-encoded application icons. Populated by Swift from
     *  `NSRunningApplication.icon` and consumed by the Compose overlay. */
    private val _iconsByPid = MutableStateFlow<Map<Pid, ByteArray>>(emptyMap())
    val iconsByPid: StateFlow<Map<Pid, ByteArray>> = _iconsByPid.asStateFlow()

    fun setAppIconPng(pid: Pid, png: ByteArray) {
        _iconsByPid.update { it + (pid to png) }
    }

    /** Settings window position + size. Updated by Swift on
     *  `NSWindow.didMove` / `didEndLiveResize`. */
    private val _settingsWindowFrame = MutableStateFlow<com.shish.kaltswitch.config.WindowFrame?>(null)
    val settingsWindowFrame: StateFlow<com.shish.kaltswitch.config.WindowFrame?> = _settingsWindowFrame.asStateFlow()

    fun setSettingsWindowFrame(frame: com.shish.kaltswitch.config.WindowFrame?) {
        _settingsWindowFrame.value = frame
    }

    fun saveSettingsWindowFrame(x: Double, y: Double, width: Double, height: Double) {
        _settingsWindowFrame.value = com.shish.kaltswitch.config.WindowFrame(x, y, width, height)
    }

    /** Inspector window position + size. */
    private val _inspectorWindowFrame = MutableStateFlow<com.shish.kaltswitch.config.WindowFrame?>(null)
    val inspectorWindowFrame: StateFlow<com.shish.kaltswitch.config.WindowFrame?> = _inspectorWindowFrame.asStateFlow()

    fun setInspectorWindowFrame(frame: com.shish.kaltswitch.config.WindowFrame?) {
        _inspectorWindowFrame.value = frame
    }

    fun saveInspectorWindowFrame(x: Double, y: Double, width: Double, height: Double) {
        _inspectorWindowFrame.value = com.shish.kaltswitch.config.WindowFrame(x, y, width, height)
    }

    /** Whether macOS is currently in dark mode. Pushed by Swift from
     *  `NSApp.effectiveAppearance` (KVO) so the Compose theme provider can
     *  swap palettes live. Default `false` (Light) — Swift seeds the real
     *  value at launch before any window is shown. */
    private val _isDarkMode = MutableStateFlow(false)
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    fun setIsDarkMode(dark: Boolean) {
        _isDarkMode.value = dark
    }

    fun setAxTrusted(trusted: Boolean) {
        _axTrusted.value = trusted
    }

    private fun setActive(pid: Pid?, windowId: WindowId?) {
        _activeAppPid.value = pid
        _activeWindowId.value = windowId
    }

    /** Insert or update one app's record. Preserves the previous record's
     *  [App.badgeText] when the caller didn't supply one — the badge stream
     *  from `DockBadgeWatcher` is independent of the NSWorkspace-driven
     *  upsert path, so we mustn't blow it away on every workspace event. */
    fun upsertApp(app: App) {
        _state.update {
            val merged = if (app.badgeText == null) {
                val prevBadge = it.runningApps[app.pid]?.badgeText
                if (prevBadge != null) app.copy(badgeText = prevBadge) else app
            } else app
            it.copy(runningApps = it.runningApps + (app.pid to merged))
        }
    }

    /** Update the dock badge string for an existing pid. No-op if the pid is
     *  unknown — the badge for an app we haven't seen yet would be lost
     *  anyway, and the next upsert + immediate rescan from the dock watcher
     *  will re-deliver it. */
    fun setAppBadge(pid: Pid, badgeText: String?) {
        _state.update {
            val app = it.runningApps[pid] ?: return@update it
            if (app.badgeText == badgeText) return@update it
            it.copy(runningApps = it.runningApps + (pid to app.copy(badgeText = badgeText)))
        }
    }

    // ─────────────────────── Window storage (one heap, per-source membership) ───────────────────────
    //
    // `World.windowsByPid` is the live, authoritative window map. No
    // derived-merge step: every Window in here is a single record that
    // tracks **which native subsystems currently see it** via the
    // [Window.sources] set. Mutators add or remove membership; a window
    // is dropped exactly when its [Window.sources] becomes empty.
    //
    // Two source-views feed in today:
    //
    //  * **AX** (`AxAppWatcher` per pid). Provides title, role, subrole,
    //    minimised / fullscreen / focused / main, child-window tree,
    //    AX-derived geometry. Reactive (event-driven via per-process
    //    AXObserver). [applyAxSnapshot] is the per-pid full-snapshot
    //    update; [upsertAxWindow] is the per-window patch path for
    //    title-changed / minimised-toggled / etc.
    //
    //  * **CG** (`CGWindowListWatcher`). Provides isOnscreen, isOn-
    //    VisibleSpace, cgLayer, cgAlpha, ownerName, and cross-space
    //    visibility (`CGWindowListCopyWindowInfo` sees windows on every
    //    space, unlike AX). Pull-driven on a 2 s background poll.
    //    [applyCgSnapshot] is the only mutator.
    //
    // Both source-paths are symmetric: a "missing from new snapshot"
    // entry removes that source from `sources`. The drop happens iff
    // sources collapses to empty. This makes the cmd+W flow look like:
    //
    //    AX-destroyed → `applyAxSnapshot` removes AX from this window's
    //    sources (window stays alive with `{CG}` if CG still has it,
    //    drops if `{AX}` was the only source) → AppRegistry's existing
    //    `onCgWindowIdSetChanged` callback kicks `cgWindowListWatcher
    //    .refresh()` → ~80 ms later `applyCgSnapshot` either retracts
    //    CG (confirming destruction → drop) or keeps the window with
    //    refreshed CG fields (e.g. space-drag — window survived).
    //
    // **Field preservation across source loss.** When a source retracts
    // we keep that source's last-known fields on the Window. A window
    // that was {AX, CG} and loses AX keeps its real title (last AX
    // value) instead of falling back to ownerName. Same the other way
    // for CG fields. The fields go stale, but staleness is far less
    // visible than a "title flipped to App Name" flash.
    //
    // **Active-window pruning.** Every mutator that can drop a window
    // calls [pruneActivationStateForPid] so the activation log and
    // `_activeWindowId` don't leak references to dropped ids.

    /** Remove an app and any windows we knew about for it. Also prunes that
     *  pid's activation history and clears the active-app/window pointers if
     *  they pointed at it — pids are runtime ids that macOS may reuse, and
     *  leaving stale entries would let a future unrelated launch inherit the
     *  recency of the dead one. */
    fun removeApp(pid: Pid) {
        _state.update {
            it.copy(
                runningApps = it.runningApps - pid,
                windowsByPid = it.windowsByPid - pid,
                log = it.log.withoutPid(pid),
            )
        }
        _iconsByPid.update { it - pid }
        if (_activeAppPid.value == pid) {
            setActive(pid = null, windowId = null)
        }
    }

    /**
     * Reconcile the [pid]'s window list with AX's freshest snapshot.
     *
     * - For each entry in [axWindows]: patch the matching live window
     *   (lookup by `cgWindowId`) with the new AX-side fields, or add it
     *   fresh if there's no live record yet. `AX` joins `sources` either
     *   way.
     * - For every live window with `AX in sources` whose `cgWindowId`
     *   isn't in [axWindows]: drop AX from `sources`. If `sources`
     *   becomes empty → drop the window entirely. Otherwise the window
     *   stays with last-known AX fields (preserved by source-cycle).
     *
     * Windows with `null` cgWindowId (rare — `_AXUIElementGetWindow`
     * occasionally fails) are matched by `id` as a fallback so we don't
     * accidentally treat the same window as new every refresh.
     */
    fun applyAxSnapshot(pid: Pid, axWindows: List<Window>) {
        val existing = currentWindowsFor(pid)
        val newAxByCgId: Map<Long, Window> = axWindows.mapNotNull { w ->
            w.cgWindowId?.let { it to w }
        }.toMap()
        val newAxIdsWithoutCg: Set<WindowId> = axWindows
            .filter { it.cgWindowId == null }
            .mapTo(HashSet()) { it.id }

        val updated = mutableListOf<Window>()
        val handledFromNew: HashSet<Long> = HashSet()
        val handledFromNewIds: HashSet<WindowId> = HashSet()

        for (w in existing) {
            val cgId = w.cgWindowId
            val matchByCgId = cgId?.let { newAxByCgId[it] }
            val matchById = if (cgId == null && w.id in newAxIdsWithoutCg) {
                axWindows.first { it.id == w.id && it.cgWindowId == null }
            } else null
            val match = matchByCgId ?: matchById
            when {
                match != null -> {
                    updated += patchAxFields(existing = w, fresh = match)
                    if (matchByCgId != null && cgId != null) handledFromNew += cgId
                    matchById?.let { handledFromNewIds += it.id }
                }
                WindowSource.AX in w.sources -> {
                    // AX previously saw this window, no longer does.
                    // Drop AX from sources; keep window if any other
                    // source still has it.
                    val withoutAx = w.copy(sources = w.sources - WindowSource.AX)
                    if (withoutAx.sources.isNotEmpty()) updated += withoutAx
                    // else: drop (don't append)
                }
                else -> {
                    // AX never had it (CG-only window) — leave alone.
                    updated += w
                }
            }
        }
        // Add brand-new AX-observed windows that didn't match any
        // existing entry by either path.
        for (w in axWindows) {
            val isNew = (w.cgWindowId != null && w.cgWindowId !in handledFromNew) ||
                (w.cgWindowId == null && w.id !in handledFromNewIds)
            if (isNew) {
                updated += w.copy(sources = w.sources + WindowSource.AX)
            }
        }

        commitWindowsFor(pid, updated)
    }

    /**
     * Drop windows by `cgWindowId` for a single pid — the fast-path
     * destroyed-handler in [AxAppWatcher] calls this after a synchronous
     * `CGWindowListCreateDescriptionFromArray` probe has already
     * confirmed with the WindowServer that the windows really are gone.
     *
     * Because the caller has authority from CoreGraphics, we don't go
     * through the source-bookkeeping reconciliation — those windows are
     * unconditionally removed regardless of which `sources` they were
     * in. `applyAxSnapshot` running immediately after would observe the
     * same retraction independently; this call just lets the UI see the
     * row disappear in the next paint instead of waiting ~80 ms for the
     * off-main CG refresh round-trip.
     *
     * No-op for cgWindowIds not in the store. Empty input is also a
     * no-op. Activation log + active-window pointer are pruned via the
     * shared [commitWindowsFor] path.
     */
    fun dropWindowsByCgWindowIds(pid: Pid, cgWindowIds: List<Long>) {
        if (cgWindowIds.isEmpty()) return
        val toDrop = cgWindowIds.toHashSet()
        val existing = currentWindowsFor(pid)
        val updated = existing.filter { w ->
            val cg = w.cgWindowId ?: return@filter true
            cg !in toDrop
        }
        if (updated.size == existing.size) return
        commitWindowsFor(pid, updated)
    }

    /**
     * Flip [Window.isMinimized] (and mirror the CG-side [Window.isOnscreen])
     * for the given (pid, windowId) **synchronously**, before AX/CG
     * notifications have caught up.
     *
     * Why we touch isOnscreen too: AX delivers
     * `kAXWindowMiniaturizedNotification` within ~200 ms of our SET call,
     * but the CG snapshot lags by up to a full cgwl interval (~2 s). In
     * the gap the window carries `isMinimized=false, isOnscreen=false`
     * (un-minimize case) or `isMinimized=true, isOnscreen=true`
     * (minimize case), and the [default-hide-cg-hidden-helper] filter
     * rule matches the former pattern — the just-restored window
     * disappears from the switcher until the cgwl refresh catches up.
     * Optimistically anticipating the CG flip closes the gap.
     *
     * Only touches CG fields when this window has CG in its sources —
     * otherwise the eventual cgwl refresh would have nothing to write
     * back and we'd be inventing data. AX-only rows just get the
     * isMinimized flip.
     *
     * Operates on top-level windows only — `cmd+M` always targets a
     * top-level (the switcher cursor doesn't address sheets / drawers).
     * No-op if [windowId] doesn't match any top-level window of [pid].
     */
    fun setWindowMinimizedOptimistic(pid: Pid, windowId: WindowId, minimized: Boolean) {
        val existing = currentWindowsFor(pid)
        var changed = false
        val updated = existing.map { w ->
            if (w.id != windowId) return@map w
            val nextOnscreen =
                if (WindowSource.CG in w.sources) !minimized else w.isOnscreen
            val patched = w.copy(isMinimized = minimized, isOnscreen = nextOnscreen)
            if (patched != w) changed = true
            patched
        }
        if (!changed) return
        commitWindowsFor(pid, updated)
    }

    /**
     * Resolve a window's [Window.cgWindowId] by its (pid, windowId).
     *
     * Used by the Swift commit path as a final fallback after the
     * AX-watcher's live-element lookup (`_AXUIElementGetWindow`) and
     * the CG enumerator's phantom map. Those two only resolve their
     * own "first-hand" windows — AX resolves AX-visible windows on
     * the current Space, the phantom map resolves CG-only entries
     * by their cgwid-as-id. Neither covers the cross-source case:
     * a window that arrived via AX (so its `id` is an AX-CFHash) and
     * is now CG-only because AX retracted on a Space change. The
     * store still carries that window with its `cgWindowId` intact —
     * this accessor surfaces it.
     *
     * Walks children too (sheets / drawers carry their own cgwids).
     * Returns null if no window with that id exists for the pid, or
     * if the found window has no cgwid resolved.
     */
    fun cgWindowIdFor(pid: Pid, windowId: WindowId): Long? {
        val list = _state.value.windowsByPid[pid] ?: return null
        fun visit(w: Window): Long? {
            if (w.id == windowId) return w.cgWindowId
            for (c in w.children) {
                val found = visit(c)
                if (found != null) return found
            }
            return null
        }
        for (w in list) {
            val found = visit(w)
            if (found != null) return found
        }
        return null
    }

    /**
     * Patch a single AX-observed window. Used for per-window events
     * (title-changed, minimised, …) where we don't need to walk the
     * whole pid's list. Adds or updates by `cgWindowId` (or `id` as
     * fallback). Never drops anything.
     */
    fun upsertAxWindow(window: Window) {
        val existing = currentWindowsFor(window.pid)
        val pid = window.pid
        val cgId = window.cgWindowId
        val replaced = mutableListOf<Window>()
        var found = false
        for (w in existing) {
            val match = (cgId != null && w.cgWindowId == cgId) ||
                (cgId == null && w.id == window.id)
            if (match) {
                replaced += patchAxFields(existing = w, fresh = window)
                found = true
            } else {
                replaced += w
            }
        }
        if (!found) {
            replaced += window.copy(sources = window.sources + WindowSource.AX)
        }
        commitWindowsFor(pid, replaced)
    }

    /**
     * Reconcile the global CG-side window view across every pid.
     *
     * - For each entry in [allCgWindows]: patch the matching live window
     *   (lookup by pid + cgWindowId) with the new CG-side fields, or
     *   add fresh if there's no live record. `CG` joins `sources`.
     * - For every live window with `CG in sources` whose `cgWindowId`
     *   isn't in [allCgWindows]: drop CG from `sources`. If `sources`
     *   becomes empty → drop. Otherwise window stays with last-known
     *   CG fields.
     */
    fun applyCgSnapshot(allCgWindows: List<Window>) {
        val newCgByKey: Map<Pair<Pid, Long>, Window> = allCgWindows.mapNotNull { w ->
            w.cgWindowId?.let { (w.pid to it) to w }
        }.toMap()
        val newCgIdsByPid: Map<Pid, Set<Long>> = allCgWindows
            .groupBy { it.pid }
            .mapValues { entry -> entry.value.mapNotNullTo(HashSet()) { it.cgWindowId } }

        // Pids we need to recompute: union of (pids currently in store)
        // and (pids in the new snapshot).
        val touchedPids: Set<Pid> = _state.value.windowsByPid.keys + newCgByKey.keys.map { it.first }

        for (pid in touchedPids) {
            val existing = currentWindowsFor(pid)
            val newCgIdsForPid = newCgIdsByPid[pid].orEmpty()
            val updated = mutableListOf<Window>()
            val handledFromNew: HashSet<Long> = HashSet()
            for (w in existing) {
                val cgId = w.cgWindowId
                val match = cgId?.let { newCgByKey[pid to it] }
                when {
                    match != null -> {
                        updated += patchCgFields(existing = w, fresh = match)
                        handledFromNew += cgId
                    }
                    WindowSource.CG in w.sources -> {
                        val withoutCg = w.copy(sources = w.sources - WindowSource.CG)
                        if (withoutCg.sources.isNotEmpty()) updated += withoutCg
                    }
                    else -> {
                        // CG didn't have it (AX-only window) — leave alone.
                        updated += w
                    }
                }
            }
            // Add brand-new CG-observed windows for this pid.
            for ((key, w) in newCgByKey) {
                if (key.first != pid) continue
                if (key.second in handledFromNew) continue
                updated += w.copy(sources = w.sources + WindowSource.CG)
            }
            commitWindowsFor(pid, updated)
        }
    }

    /** Merge AX-side fields from [fresh] into [existing], preserving
     *  [existing]'s CG-side fields and source bits, then add AX to
     *  sources. Used by both [applyAxSnapshot] and [upsertAxWindow]. */
    private fun patchAxFields(existing: Window, fresh: Window): Window =
        existing.copy(
            id = fresh.id,
            title = fresh.title,
            role = fresh.role,
            subrole = fresh.subrole,
            isMinimized = fresh.isMinimized,
            isFullscreen = fresh.isFullscreen,
            isFocused = fresh.isFocused,
            isMain = fresh.isMain,
            x = fresh.x,
            y = fresh.y,
            width = fresh.width,
            height = fresh.height,
            children = fresh.children,
            spaceIds = fresh.spaceIds,
            cgWindowId = fresh.cgWindowId ?: existing.cgWindowId,
            sources = existing.sources + WindowSource.AX,
        )

    /** Merge CG-side fields from [fresh] into [existing], preserving
     *  [existing]'s AX-side fields and source bits, then add CG. */
    private fun patchCgFields(existing: Window, fresh: Window): Window {
        val nextSpaceIds = if (WindowSource.AX in existing.sources) existing.spaceIds else fresh.spaceIds
        return existing.copy(
            cgWindowId = fresh.cgWindowId ?: existing.cgWindowId,
            ownerName = fresh.ownerName,
            isOnscreen = fresh.isOnscreen,
            isOnVisibleSpace = fresh.isOnVisibleSpace,
            cgLayer = fresh.cgLayer,
            cgAlpha = fresh.cgAlpha,
            spaceIds = nextSpaceIds,
            sources = existing.sources + WindowSource.CG,
        )
    }

    private fun currentWindowsFor(pid: Pid): List<Window> =
        _state.value.windowsByPid[pid].orEmpty()

    /** Initial-state normaliser — recursively adds [WindowSource.AX] to
     *  every window's sources. Only called from the [WorldStore] constructor
     *  when the caller pre-populated `windowsByPid` directly; see the
     *  doc on the `_state` initialiser for why this is needed. */
    private fun ensureAxSourceRecursively(w: Window): Window {
        val withSource =
            if (WindowSource.AX in w.sources) w
            else w.copy(sources = w.sources + WindowSource.AX)
        val patchedChildren = withSource.children.map(::ensureAxSourceRecursively)
        return if (patchedChildren == withSource.children) withSource
        else withSource.copy(children = patchedChildren)
    }

    /**
     * Persist a new window list for a single pid: writes to
     * `_state.windowsByPid`, prunes the activation log so dropped
     * windows don't linger in history, and clears `_activeWindowId`
     * if it pointed at a now-dropped window.
     */
    private fun commitWindowsFor(pid: Pid, windows: List<Window>) {
        val liveIds: Set<WindowId> = collectAllWindowIds(windows)
        _state.update {
            it.copy(
                windowsByPid =
                    if (windows.isEmpty()) it.windowsByPid - pid
                    else it.windowsByPid + (pid to windows),
                log = it.log.withoutMissingWindows(pid, liveIds),
            )
        }
        if (_activeAppPid.value == pid) {
            val activeWid = _activeWindowId.value
            if (activeWid != null && activeWid !in liveIds) {
                _activeWindowId.value = null
            }
        }
    }

    /** Recursively collect every window id under [windows], including
     *  attached sheets/drawers/popovers reported as children. AX often
     *  reports those as part of the parent's tree, and the activation log
     *  may contain events for them; we want to keep those events live. */
    private fun collectAllWindowIds(windows: List<Window>): Set<WindowId> {
        val out = HashSet<WindowId>()
        fun visit(w: Window) {
            out.add(w.id)
            for (c in w.children) visit(c)
        }
        for (w in windows) visit(w)
        return out
    }

    /**
     * Single canonical "an activation happened" event. Atomically appends to the
     * activation log AND updates the [activeAppPid] / [activeWindowId] pointers,
     * so the inspector's row order (driven by the log) and active-row highlight
     * (driven by the pointers) can never disagree.
     *
     * Used to be gated by a `_switcherActive` flag while a session was live so
     * our own preview-raise / commit AX echo wouldn't pollute the log. Both
     * concerns went away with the timestamp-based recency model and the
     * preview-raise wiring being disabled: the commit's explicit record
     * coincides with the AX echo (deduped by [ActivationLog.record] bumping
     * the same pid to the front idempotently), and side-effect echoes from
     * cmd+M / cmd+W et al. are now treated as legitimate signals — they
     * reflect what macOS actually focused, which is what the switcher should
     * surface next time.
     *
     * `windowId == null` means "we know an app got focus but not which of its
     * windows" — that becomes an app-level event in the log, with the active
     * window pointer cleared until a more specific event arrives.
     */
    fun recordActivation(pid: Pid, windowId: WindowId?) {
        _state.update {
            it.copy(log = it.log.record(ActivationEvent(pid, windowId)))
        }
        setActive(pid = pid, windowId = windowId)
    }

    /**
     * Window-only counterpart of [recordActivation]. Bumps the per-app
     * window order without promoting the app in [appOrder] and without
     * shifting the active-app/window pointers.
     *
     * Used by switcher actions that change a window's *state* (cmd+M
     * minimize / restore) but don't mean "I want this app now". The user
     * is still browsing inside the switcher — releasing cmd to commit is
     * the path that promotes the app via the regular [recordActivation].
     * See `[ActivationLog.recordWindow]`.
     */
    fun recordWindowActivation(pid: Pid, windowId: WindowId) {
        _state.update {
            it.copy(log = it.log.recordWindow(pid, windowId))
        }
    }

    /** Clear the active-app/window pointers without touching the log. Used when
     *  no app is frontmost (e.g. user logged out of session, all apps quit). */
    fun clearActive() {
        setActive(pid = null, windowId = null)
    }

    /**
     * Apply every persisted field from [cfg] in one call. Used at startup
     * after `ConfigStore.load()` returns a non-null config; centralises the
     * fan-out so adding a new persisted setting touches one place instead
     * of two (here + the [configFlow] producer below).
     */
    fun applyConfig(cfg: AppConfig) {
        setFilters(cfg.filters)
        setBadgeRules(cfg.badges)
        setPinning(cfg.pinning)
        setSettingsWindowFrame(cfg.settingsWindowFrame)
        setInspectorWindowFrame(cfg.inspectorWindowFrame)
        setSwitcherSettings(cfg.switcher)
        setShowMenubarIcon(cfg.showMenubarIcon)
        setLaunchAtLogin(cfg.launchAtLogin)
        setCurrentSpaceOnly(cfg.currentSpaceOnly)
        setLoggingEnabled(cfg.loggingEnabled)
        // Migrate legacy v6 RGB accents (alpha-byte == 0 when interpreted as
        // ARGB) up to opaque. Every shipped pre-v7 config has alpha = 0 in
        // the stored Long; without this fix-up they'd render fully
        // transparent and the highlight would disappear. See
        // [AccentColorChoice] KDoc for the back-compat reasoning.
        val accent = cfg.accentColor
        val fixedAccent = if (
            accent is AccentColorChoice.Custom && (accent.argb ushr 24) == 0L
        ) {
            AccentColorChoice.Custom(0xFF000000L or accent.argb)
        } else {
            accent
        }
        setAccentColor(fixedAccent)
        setSwitcherPanelBgArgb(cfg.switcherPanelBgArgb)
        setSwitcherDemoteBgArgb(cfg.switcherDemoteBgArgb)
    }

    /**
     * Cold flow that emits a fresh [AppConfig] snapshot every time any
     * persisted field changes. The first emission is the current value at
     * subscribe time; downstream callers typically `.drop(1)` so the load →
     * combine-replay path doesn't immediately overwrite the file we just
     * read.
     *
     * Built in two stages because `combine` has overloads up to 5 flows;
     * once we cross that bound we stitch a second `combine` on top. Behaves
     * identically to a single combine.
     */
    fun configFlow(): Flow<AppConfig> {
        val core = combine(
            filters,
            settingsWindowFrame,
            inspectorWindowFrame,
            switcherSettings,
            showMenubarIcon,
        ) { filters, settingsFrame, inspectorFrame, switcher, menubar ->
            AppConfig(
                filters = filters,
                settingsWindowFrame = settingsFrame,
                inspectorWindowFrame = inspectorFrame,
                switcher = switcher,
                showMenubarIcon = menubar,
            )
        }
        // Fold the three colour flows into one so the outer combine stays at
        // five arms (combine has a 5-flow overload; adding a third tier just
        // to spell them out separately would only add nesting noise).
        val colors = combine(
            accentColor,
            switcherPanelBgArgb,
            switcherDemoteBgArgb,
        ) { accent, panelBg, demoteBg ->
            Triple(accent, panelBg, demoteBg)
        }
        // Pair badge + pinning rules so the outer combine stays within its
        // 5-arm overload. Both are predicate-rule collections without their
        // own colour/numeric arms — sharing one slot reads fine.
        val ruleExtras = combine(badgeRules, pinning) { b, p -> b to p }
        // Bundle the three behaviour booleans into a single arm so the outer
        // combine stays within its 5-overload bound as the schema grows.
        val behaviour = combine(launchAtLogin, currentSpaceOnly, loggingEnabled) { l, c, lg ->
            Triple(l, c, lg)
        }
        return combine(
            core,
            behaviour,
            colors,
            ruleExtras,
        ) { base, behaviourT, colorsT, extras ->
            base.copy(
                launchAtLogin = behaviourT.first,
                currentSpaceOnly = behaviourT.second,
                loggingEnabled = behaviourT.third,
                accentColor = colorsT.first,
                switcherPanelBgArgb = colorsT.second,
                switcherDemoteBgArgb = colorsT.third,
                badges = extras.first,
                pinning = extras.second,
            )
        }
    }

    /**
     * Convenience for Swift watchers: build an [App] from primitive fields without
     * having to construct enums on the Swift side.
     */
    @Suppress("LongParameterList")
    fun upsertAppFields(
        pid: Pid,
        bundleId: String?,
        name: String,
        activationPolicyRaw: Long,
        isHidden: Boolean,
        isFinishedLaunching: Boolean,
        executablePath: String?,
        launchDateMillis: Long,
    ) {
        val policy = when (activationPolicyRaw.toInt()) {
            0 -> AppActivationPolicy.Regular
            1 -> AppActivationPolicy.Accessory
            else -> AppActivationPolicy.Prohibited
        }
        upsertApp(
            App(
                pid = pid,
                bundleId = bundleId,
                name = name,
                activationPolicy = policy,
                isHidden = isHidden,
                isFinishedLaunching = isFinishedLaunching,
                executablePath = executablePath,
                launchDateMillis = launchDateMillis.takeIf { it != 0L },
            )
        )
    }
}
