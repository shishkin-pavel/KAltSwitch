package com.shish.kaltswitch

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.shish.kaltswitch.config.AppConfig
import com.shish.kaltswitch.config.ConfigStore
import com.shish.kaltswitch.native.requestAxPermission
import com.shish.kaltswitch.store.WorldStore
import com.shish.kaltswitch.switcher.SwitcherController
import com.shish.kaltswitch.viewcontroller.ComposeNSViewDelegate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import platform.AppKit.NSWindow

/** Singleton store. Swift's `AppRegistry` mutates it; the Compose UI observes it. */
val store = WorldStore().also { initStore ->
    // Load persisted config on first access so the UI starts with the user's
    // filters, the saved frame + inspector width, and switcher delays.
    ConfigStore.load()?.let { cfg ->
        initStore.setFilters(cfg.filters)
        initStore.setWindowFrame(cfg.windowFrame)
        initStore.setInspectorWidth(cfg.inspectorWidth)
        initStore.setSwitcherSettings(cfg.switcher)
        initStore.setInspectorVisible(cfg.inspectorVisible)
        initStore.setShowMenubarIcon(cfg.showMenubarIcon)
        initStore.setLaunchAtLogin(cfg.launchAtLogin)
        initStore.setCurrentSpaceOnly(cfg.currentSpaceOnly)
    }
}

/**
 * Persists config changes to disk. Combine emits whenever any of the underlying
 * StateFlows changes; `drop(1)` skips the initial combined emission so we don't
 * immediately overwrite the file we just loaded.
 */
private val configScope = CoroutineScope(Dispatchers.Main).also { scope ->
    // combine() has overloads up to 5 flows. Once we cross that, build the
    // AppConfig in two stages: the inspector-related fields first, then merge
    // in the system-wide toggles. Functionally equivalent to a single combine.
    val coreCfg = kotlinx.coroutines.flow.combine(
        store.filters,
        store.windowFrame,
        store.inspectorWidth,
        store.switcherSettings,
        store.inspectorVisible,
    ) { filters, frame, inspW, switcher, inspectorVisible ->
        AppConfig(
            filters = filters,
            windowFrame = frame,
            inspectorWidth = inspW,
            switcher = switcher,
            inspectorVisible = inspectorVisible,
        )
    }
    kotlinx.coroutines.flow.combine(
        coreCfg,
        store.showMenubarIcon,
        store.launchAtLogin,
        store.currentSpaceOnly,
    ) { base, menubar, launchAtLogin, currentSpaceOnly ->
        base.copy(
            showMenubarIcon = menubar,
            launchAtLogin = launchAtLogin,
            currentSpaceOnly = currentSpaceOnly,
        )
    }
        .drop(1)
        .onEach { ConfigStore.save(it) }
        .launchIn(scope)
}

/**
 * Singleton switcher controller. Swift's `HotkeyController` calls
 * `onShortcut` / `onModifierReleased` / `onEsc`; the Compose overlay observes [SwitcherController.ui].
 *
 * Swift wires `onRaiseWindow` / `onCommitActivation` to AX-side actions after launch.
 */
val switcherController = SwitcherController(
    store = store,
    scope = CoroutineScope(Dispatchers.Main),
)

/** Lifetime scope for cross-boundary observers. Cancelled on app termination. */
private val bridgeScope = CoroutineScope(Dispatchers.Main)

/**
 * Subscribe Swift to overlay session-active transitions. `active == true` means a
 * switcher session has just started — the overlay panel should be ordered front
 * and made key (even if it's still inside `showDelay` and visually transparent),
 * because making it key from t=0 is what lets `NSPanel.sendEvent` see the
 * modifier-release `flagsChanged` events without relying on AX-permission-gated
 * `CGEventTap`.
 */
fun observeSwitcherSession(onChange: (Boolean) -> Unit) {
    switcherController.ui
        .map { it != null }
        .distinctUntilChanged()
        .onEach(onChange)
        .launchIn(bridgeScope)
}

/** Subscribe Swift to overlay visible transitions (post-`showDelay`). The panel
 *  toggles its `alphaValue` between 0 and 1 on this signal so the contents only
 *  appear after the delay, while the panel itself stays key from session start. */
fun observeSwitcherVisibility(onChange: (Boolean) -> Unit) {
    switcherController.ui
        .map { it?.visible == true }
        .distinctUntilChanged()
        .onEach(onChange)
        .launchIn(bridgeScope)
}

/** Subscribe Swift to inspector-visibility transitions so the inspector
 *  window's title can switch between "Settings" and "Settings/Inspector".
 *  StateFlow is already conflated, so no extra `distinctUntilChanged`. */
fun observeInspectorVisible(onChange: (Boolean) -> Unit) {
    store.inspectorVisible
        .onEach(onChange)
        .launchIn(bridgeScope)
}

/** Whether the menubar status item should be installed. */
fun observeShowMenubarIcon(onChange: (Boolean) -> Unit) {
    store.showMenubarIcon
        .onEach(onChange)
        .launchIn(bridgeScope)
}

/** Auto-launch at login (SMAppService on the Swift side). */
fun observeLaunchAtLogin(onChange: (Boolean) -> Unit) {
    store.launchAtLogin
        .onEach(onChange)
        .launchIn(bridgeScope)
}

/** Switcher / inspector hide off-current-space windows when this is on. */
fun observeCurrentSpaceOnly(onChange: (Boolean) -> Unit) {
    store.currentSpaceOnly
        .onEach(onChange)
        .launchIn(bridgeScope)
}

fun AttachMainComposeView(
    window: NSWindow,
): ComposeNSViewDelegate = ComposeNSViewDelegate(
    window = window,
    content = {
        val world by store.state.collectAsState()
        val axTrusted by store.axTrusted.collectAsState()
        val activeAppPid by store.activeAppPid.collectAsState()
        val activeWindowId by store.activeWindowId.collectAsState()
        val filters by store.filters.collectAsState()
        val switcherSettings by store.switcherSettings.collectAsState()
        val inspectorVisible by store.inspectorVisible.collectAsState()
        val showMenubarIcon by store.showMenubarIcon.collectAsState()
        val launchAtLogin by store.launchAtLogin.collectAsState()
        val currentSpaceOnly by store.currentSpaceOnly.collectAsState()
        val visibleSpaceIds by store.visibleSpaceIds.collectAsState()
        val windowFrame by store.windowFrame.collectAsState()
        // Sidebar width is stored alongside the rest of the window frame —
        // when the inspector is hidden it's the whole window's width, when
        // visible it's the left pane up to the draggable separator.
        val sidebarWidth = windowFrame?.width ?: 320.0
        App(
            world = world,
            axTrusted = axTrusted,
            activeAppPid = activeAppPid,
            activeWindowId = activeWindowId,
            filters = filters,
            onFiltersChange = { store.setFilters(it) },
            switcherSettings = switcherSettings,
            onSwitcherSettingsChange = { store.setSwitcherSettings(it) },
            inspectorVisible = inspectorVisible,
            onInspectorVisibleChange = { store.setInspectorVisible(it) },
            showMenubarIcon = showMenubarIcon,
            onShowMenubarIconChange = { store.setShowMenubarIcon(it) },
            launchAtLogin = launchAtLogin,
            onLaunchAtLoginChange = { store.setLaunchAtLogin(it) },
            sidebarWidth = sidebarWidth,
            onSidebarWidthChange = { store.saveSidebarWidth(it) },
            onInspectorWidthChange = { store.saveInspectorWidth(it) },
            currentSpaceOnly = currentSpaceOnly,
            onCurrentSpaceOnlyChange = { store.setCurrentSpaceOnly(it) },
            visibleSpaceIds = visibleSpaceIds,
            onGrantAxClick = {
                val granted = requestAxPermission()
                store.setAxTrusted(granted)
            },
        )
    },
)

/**
 * Compose content host for the switcher overlay panel (`NSPanel` provided by Swift).
 * Renders [SwitcherOverlay] when a session is active and visible; otherwise empty.
 */
fun AttachSwitcherOverlay(window: NSWindow): ComposeNSViewDelegate = ComposeNSViewDelegate(
    window = window,
    content = {
        val ui by switcherController.ui.collectAsState()
        val icons by store.iconsByPid.collectAsState()
        val current = ui
        if (current != null && current.visible) {
            SwitcherOverlay(
                ui = current,
                iconsByPid = icons,
                onNavigate = { switcherController.onNavigate(it) },
                onEsc = { switcherController.onEsc() },
                onShortcut = { switcherController.onShortcut(it) },
                onPointAt = { appIndex, windowIndex ->
                    switcherController.onPointAt(appIndex, windowIndex)
                },
                onCommit = { switcherController.onCommit() },
            )
        }
    },
)
