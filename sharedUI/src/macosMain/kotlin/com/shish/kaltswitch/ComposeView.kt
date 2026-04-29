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
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

/** Singleton store. Swift's `AppRegistry` mutates it; the Compose UI observes it. */
val store = WorldStore().also { initStore ->
    // Load persisted config on first access so the UI starts with the user's
    // filters and the inspector window restores its last frame.
    ConfigStore.load()?.let { cfg ->
        initStore.setFilters(cfg.filters)
        initStore.setInspectorFrame(cfg.inspectorFrame)
    }
}

/**
 * Persists config changes to disk. Combine emits whenever either of the underlying
 * StateFlows changes; `drop(1)` skips the initial combined emission so we don't
 * immediately overwrite the file we just loaded.
 */
private val configScope = CoroutineScope(Dispatchers.Main).also { scope ->
    kotlinx.coroutines.flow.combine(store.filters, store.inspectorFrame) { filters, frame ->
        AppConfig(filters = filters, inspectorFrame = frame)
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
    clock = { (NSDate().timeIntervalSince1970 * 1000.0).toLong() },
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
        App(
            world = world,
            axTrusted = axTrusted,
            activeAppPid = activeAppPid,
            activeWindowId = activeWindowId,
            filters = filters,
            onFiltersChange = { store.setFilters(it) },
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
            )
        }
    },
)
