package com.shish.kaltswitch.store

import com.shish.kaltswitch.config.AccentColorChoice
import com.shish.kaltswitch.config.AppConfig
import com.shish.kaltswitch.config.SwitcherSettings
import com.shish.kaltswitch.config.sanitized
import com.shish.kaltswitch.model.ActivationEvent
import com.shish.kaltswitch.model.ActivationLog
import com.shish.kaltswitch.model.App
import com.shish.kaltswitch.model.AppActivationPolicy
import com.shish.kaltswitch.model.FilteringRules
import com.shish.kaltswitch.model.Window
import com.shish.kaltswitch.model.WindowId
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
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<World> = _state.asStateFlow()

    private val _axTrusted = MutableStateFlow(true)
    val axTrusted: StateFlow<Boolean> = _axTrusted.asStateFlow()

    private val _activeAppPid = MutableStateFlow<Int?>(null)
    val activeAppPid: StateFlow<Int?> = _activeAppPid.asStateFlow()

    private val _activeWindowId = MutableStateFlow<WindowId?>(null)
    val activeWindowId: StateFlow<WindowId?> = _activeWindowId.asStateFlow()

    private val _filters = MutableStateFlow(FilteringRules())
    val filters: StateFlow<FilteringRules> = _filters.asStateFlow()

    fun setFilters(f: FilteringRules) {
        _filters.value = f
    }

    private val _switcherSettings = MutableStateFlow(SwitcherSettings())
    val switcherSettings: StateFlow<SwitcherSettings> = _switcherSettings.asStateFlow()

    /** Sanitised on the way in so the runtime path (coroutine `delay`) never
     *  sees a negative value or a zero `repeatIntervalMs` that would tight-loop.
     *  Both config-load (`applyConfig`) and the settings UI go through here. */
    fun setSwitcherSettings(s: SwitcherSettings) {
        _switcherSettings.value = s.sanitized()
    }

    private val _inspectorVisible = MutableStateFlow(true)
    val inspectorVisible: StateFlow<Boolean> = _inspectorVisible.asStateFlow()

    fun setInspectorVisible(visible: Boolean) {
        _inspectorVisible.value = visible
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
     *  [systemAccentRgb] downstream (see ComposeView.effectiveAccentRgb). */
    private val _accentColor = MutableStateFlow<AccentColorChoice>(AccentColorChoice.Custom(0xFFC107))
    val accentColor: StateFlow<AccentColorChoice> = _accentColor.asStateFlow()

    fun setAccentColor(choice: AccentColorChoice) {
        _accentColor.value = choice
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

    /** True while a switcher session is open or just closed. AX/Workspace activation
     *  events arriving in this window are dropped — they describe our own preview-raise
     *  / commit echo, not user-driven activity, and would otherwise corrupt the log. */
    private val _switcherActive = MutableStateFlow(false)
    val switcherActive: StateFlow<Boolean> = _switcherActive.asStateFlow()

    fun setSwitcherActive(active: Boolean) {
        _switcherActive.value = active
    }

    /** Per-pid PNG-encoded application icons. Populated by Swift from
     *  `NSRunningApplication.icon` and consumed by the Compose overlay. */
    private val _iconsByPid = MutableStateFlow<Map<Int, ByteArray>>(emptyMap())
    val iconsByPid: StateFlow<Map<Int, ByteArray>> = _iconsByPid.asStateFlow()

    fun setAppIconPng(pid: Int, png: ByteArray) {
        _iconsByPid.update { it + (pid to png) }
    }

    /** Window position + height + settings-only width. See AppConfig.windowFrame. */
    private val _windowFrame = MutableStateFlow<com.shish.kaltswitch.config.WindowFrame?>(null)
    val windowFrame: StateFlow<com.shish.kaltswitch.config.WindowFrame?> = _windowFrame.asStateFlow()

    fun setWindowFrame(frame: com.shish.kaltswitch.config.WindowFrame?) {
        _windowFrame.value = frame
    }

    /** Swift-friendly variant taking primitives. */
    fun saveWindowFrame(x: Double, y: Double, width: Double, height: Double) {
        _windowFrame.value = com.shish.kaltswitch.config.WindowFrame(x, y, width, height)
    }

    /** Update only the window's width (sidebar / settings-pane width).
     *  Leaves origin and height untouched so the dragger inside Compose
     *  doesn't have to know about the rest of the frame. No-op if the frame
     *  hasn't been seeded yet — Swift will save it on next NSWindow event. */
    fun saveSidebarWidth(width: Double) {
        _windowFrame.update { it?.copy(width = width) }
    }

    private val _inspectorWidth = MutableStateFlow(480.0)
    val inspectorWidth: StateFlow<Double> = _inspectorWidth.asStateFlow()

    fun setInspectorWidth(width: Double) {
        _inspectorWidth.value = width
    }

    /** Swift-friendly accessor — kept for symmetry with `saveWindowFrame`. */
    fun saveInspectorWidth(width: Double) {
        _inspectorWidth.value = width
    }

    fun setAxTrusted(trusted: Boolean) {
        _axTrusted.value = trusted
    }

    private fun setActive(pid: Int?, windowId: WindowId?) {
        _activeAppPid.value = pid
        _activeWindowId.value = windowId
    }

    /** Insert or update one app's record. */
    fun upsertApp(app: App) {
        _state.update { it.copy(runningApps = it.runningApps + (app.pid to app)) }
    }

    /** Remove an app and any windows we knew about for it. Also prunes that
     *  pid's activation history and clears the active-app/window pointers if
     *  they pointed at it — pids are runtime ids that macOS may reuse, and
     *  leaving stale entries would let a future unrelated launch inherit the
     *  recency of the dead one. */
    fun removeApp(pid: Int) {
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

    /** Replace the full window list for a pid. Use for snapshot-style refreshes.
     *  Also prunes any activation events that referenced now-missing windows
     *  (AX window ids are tied to native element lifetimes and can be reused);
     *  app-level events for the pid are kept. Clears [activeWindowId] if the
     *  pointed-at window has disappeared. */
    fun setWindows(pid: Int, windows: List<Window>) {
        val liveIds: Set<WindowId> = collectAllWindowIds(windows)
        _state.update {
            it.copy(
                windowsByPid = it.windowsByPid + (pid to windows),
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

    /** Insert or update a single window. */
    fun upsertWindow(window: Window) {
        _state.update {
            val existing = it.windowsByPid[window.pid].orEmpty()
            val replaced = existing.filter { w -> w.id != window.id } + window
            it.copy(windowsByPid = it.windowsByPid + (window.pid to replaced))
        }
    }

    /**
     * Single canonical "an activation happened" event. Atomically appends to the
     * activation log AND updates the [activeAppPid] / [activeWindowId] pointers,
     * so the inspector's row order (driven by the log) and active-row highlight
     * (driven by the pointers) can never disagree.
     *
     * Dropped while a switcher session is live so our own preview-raise / commit
     * AX echo doesn't pollute either side. The commit's AX echo arrives
     * *after* the session ends (we clear `switcherActive` synchronously when
     * `_ui.value` flips to null), so it lands naturally in the log.
     *
     * `windowId == null` means "we know an app got focus but not which of its
     * windows" — that becomes an app-level event in the log, with the active
     * window pointer cleared until a more specific event arrives.
     */
    fun recordActivation(pid: Int, windowId: WindowId?) {
        if (_switcherActive.value) return
        _state.update {
            it.copy(log = it.log.record(ActivationEvent(pid, windowId)))
        }
        setActive(pid = pid, windowId = windowId)
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
        setWindowFrame(cfg.windowFrame)
        setInspectorWidth(cfg.inspectorWidth)
        setSwitcherSettings(cfg.switcher)
        setInspectorVisible(cfg.inspectorVisible)
        setShowMenubarIcon(cfg.showMenubarIcon)
        setLaunchAtLogin(cfg.launchAtLogin)
        setCurrentSpaceOnly(cfg.currentSpaceOnly)
        setAccentColor(cfg.accentColor)
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
            windowFrame,
            inspectorWidth,
            switcherSettings,
            inspectorVisible,
        ) { filters, frame, inspW, switcher, inspectorVisible ->
            AppConfig(
                filters = filters,
                windowFrame = frame,
                inspectorWidth = inspW,
                switcher = switcher,
                inspectorVisible = inspectorVisible,
            )
        }
        return combine(
            core,
            showMenubarIcon,
            launchAtLogin,
            currentSpaceOnly,
            accentColor,
        ) { base, menubar, launchAtLogin, currentSpaceOnly, accent ->
            base.copy(
                showMenubarIcon = menubar,
                launchAtLogin = launchAtLogin,
                currentSpaceOnly = currentSpaceOnly,
                accentColor = accent,
            )
        }
    }

    /**
     * Convenience for Swift watchers: build an [App] from primitive fields without
     * having to construct enums on the Swift side.
     */
    @Suppress("LongParameterList")
    fun upsertAppFields(
        pid: Int,
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
