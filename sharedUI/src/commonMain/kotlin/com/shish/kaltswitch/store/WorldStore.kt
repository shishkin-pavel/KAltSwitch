package com.shish.kaltswitch.store

import com.shish.kaltswitch.config.SwitcherSettings
import com.shish.kaltswitch.model.ActivationEvent
import com.shish.kaltswitch.model.ActivationLog
import com.shish.kaltswitch.model.App
import com.shish.kaltswitch.model.AppActivationPolicy
import com.shish.kaltswitch.config.AccentColorChoice
import com.shish.kaltswitch.model.FilteringRules
import com.shish.kaltswitch.model.Window
import com.shish.kaltswitch.model.WindowId
import com.shish.kaltswitch.model.World
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    fun setSwitcherSettings(s: SwitcherSettings) {
        _switcherSettings.value = s
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
     *  Swift uses it to position an NSVisualEffectView under the panel for
     *  the blur backdrop. Null when there is no active session. */
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

    fun removeAppIcon(pid: Int) {
        _iconsByPid.update { it - pid }
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

    /** Replace the entire running-apps set; used at startup seed. */
    fun setRunningApps(apps: Map<Int, App>) {
        _state.update { it.copy(runningApps = apps) }
    }

    /** Insert or update one app's record. */
    fun upsertApp(app: App) {
        _state.update { it.copy(runningApps = it.runningApps + (app.pid to app)) }
    }

    /** Remove an app and any windows we knew about for it. */
    fun removeApp(pid: Int) {
        _state.update {
            it.copy(
                runningApps = it.runningApps - pid,
                windowsByPid = it.windowsByPid - pid,
            )
        }
        _iconsByPid.update { it - pid }
    }

    /** Replace the full window list for a pid. Use for snapshot-style refreshes. */
    fun setWindows(pid: Int, windows: List<Window>) {
        _state.update { it.copy(windowsByPid = it.windowsByPid + (pid to windows)) }
    }

    /** Insert or update a single window. */
    fun upsertWindow(window: Window) {
        _state.update {
            val existing = it.windowsByPid[window.pid].orEmpty()
            val replaced = existing.filter { w -> w.id != window.id } + window
            it.copy(windowsByPid = it.windowsByPid + (window.pid to replaced))
        }
    }

    /** Remove a single window. */
    fun removeWindow(pid: Int, windowId: WindowId) {
        _state.update {
            val existing = it.windowsByPid[pid].orEmpty()
            val filtered = existing.filter { w -> w.id != windowId }
            it.copy(windowsByPid = it.windowsByPid + (pid to filtered))
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

    /** Same convenience for windows. */
    @Suppress("LongParameterList")
    fun upsertWindowFields(
        pid: Int,
        windowId: Long,
        title: String,
        role: String?,
        subrole: String?,
        isMinimized: Boolean,
        isFullscreen: Boolean,
        isFocused: Boolean,
        isMain: Boolean,
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        spaceIds: List<Long> = emptyList(),
    ) {
        upsertWindow(
            Window(
                id = windowId,
                pid = pid,
                title = title,
                role = role,
                subrole = subrole,
                isMinimized = isMinimized,
                isFullscreen = isFullscreen,
                isFocused = isFocused,
                isMain = isMain,
                x = x.takeIf { !it.isNaN() },
                y = y.takeIf { !it.isNaN() },
                width = width.takeIf { !it.isNaN() },
                height = height.takeIf { !it.isNaN() },
                spaceIds = spaceIds,
            )
        )
    }
}
