package com.shish.kaltswitch.store

import com.shish.kaltswitch.model.ActivationEvent
import com.shish.kaltswitch.model.ActivationLog
import com.shish.kaltswitch.model.App
import com.shish.kaltswitch.model.AppActivationPolicy
import com.shish.kaltswitch.model.Filters
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

    private val _filters = MutableStateFlow(Filters())
    val filters: StateFlow<Filters> = _filters.asStateFlow()

    fun setFilters(f: Filters) {
        _filters.value = f
    }

    fun setAxTrusted(trusted: Boolean) {
        _axTrusted.value = trusted
    }

    fun setActive(pid: Int?, windowId: WindowId?) {
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

    /** Append an activation event. */
    fun recordEvent(event: ActivationEvent) {
        _state.update { it.copy(log = it.log.record(event)) }
    }

    /** Swift-friendly: record an app-level activation (no window id). */
    fun recordAppActivation(pid: Int, timestampMs: Long) {
        recordEvent(ActivationEvent(timestampMs, pid, windowId = null))
    }

    /** Swift-friendly: record a window-level activation. */
    fun recordWindowActivation(pid: Int, windowId: WindowId, timestampMs: Long) {
        recordEvent(ActivationEvent(timestampMs, pid, windowId))
    }

    /** Swift-friendly: clear active state. */
    fun clearActive() {
        setActive(pid = null, windowId = null)
    }

    /** Swift-friendly: set active app (no window). */
    fun setActiveApp(pid: Int) {
        setActive(pid = pid, windowId = null)
    }

    /** Swift-friendly: set active app + window. */
    fun setActiveAppAndWindow(pid: Int, windowId: WindowId) {
        setActive(pid = pid, windowId = windowId)
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
            )
        )
    }
}
