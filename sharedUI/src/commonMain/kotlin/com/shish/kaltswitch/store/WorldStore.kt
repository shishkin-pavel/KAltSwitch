package com.shish.kaltswitch.store

import com.shish.kaltswitch.model.ActivationEvent
import com.shish.kaltswitch.model.ActivationLog
import com.shish.kaltswitch.model.App
import com.shish.kaltswitch.model.Window
import com.shish.kaltswitch.model.WindowId
import com.shish.kaltswitch.model.World
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Mutable holder of the current [World]. UI observes [state]; native watchers call the mutators. */
class WorldStore(initial: World = World(ActivationLog(), emptyMap(), emptyMap())) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<World> = _state.asStateFlow()

    private val _axTrusted = MutableStateFlow(true)
    val axTrusted: StateFlow<Boolean> = _axTrusted.asStateFlow()

    fun setAxTrusted(trusted: Boolean) {
        _axTrusted.value = trusted
    }

    /** PID of the currently focused application (frontmost). Null until first activation. */
    private val _activeAppPid = MutableStateFlow<Int?>(null)
    val activeAppPid: StateFlow<Int?> = _activeAppPid.asStateFlow()

    /** Window ID of the currently focused window. Null when unknown. */
    private val _activeWindowId = MutableStateFlow<WindowId?>(null)
    val activeWindowId: StateFlow<WindowId?> = _activeWindowId.asStateFlow()

    fun setActive(pid: Int?, windowId: WindowId?) {
        _activeAppPid.value = pid
        _activeWindowId.value = windowId
    }

    fun setRunningApps(apps: Map<Int, App>) {
        _state.update { it.copy(runningApps = apps) }
    }

    fun addRunningApp(app: App) {
        _state.update { it.copy(runningApps = it.runningApps + (app.pid to app)) }
    }

    fun removeRunningApp(pid: Int) {
        _state.update {
            it.copy(
                runningApps = it.runningApps - pid,
                windowsByPid = it.windowsByPid - pid,
            )
        }
    }

    fun setWindows(pid: Int, windows: List<Window>) {
        _state.update { it.copy(windowsByPid = it.windowsByPid + (pid to windows)) }
    }

    fun recordEvent(event: ActivationEvent) {
        _state.update { it.copy(log = it.log.record(event)) }
    }
}
