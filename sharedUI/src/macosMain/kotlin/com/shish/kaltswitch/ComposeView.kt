package com.shish.kaltswitch

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

/** Singleton store. Swift's `AppRegistry` mutates it; the Compose UI observes it.
 *  Seeded once from `~/Library/Application Support/KAltSwitch/config.json`; if
 *  the file is missing every StateFlow keeps its declared default. */
val store = WorldStore().apply {
    ConfigStore.load()?.let(::applyConfig)
}

/**
 * Persists config changes to disk. `WorldStore.configFlow()` re-emits whenever
 * any persisted field changes; `drop(1)` skips the initial combined emission
 * so we don't immediately overwrite the file we just loaded above. The extra
 * `distinctUntilChanged` guards against native observers that re-fire with
 * identical state — e.g. `applyShowMenubarIcon` getting the seed value back
 * through the StateFlow seed → callback → `setShowMenubarIcon` round-trip
 * that boils down to the same `AppConfig`.
 */
private val configScope = CoroutineScope(Dispatchers.Main).also { scope ->
    store.configFlow()
        .drop(1)
        .distinctUntilChanged()
        .onEach(ConfigStore::save)
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

// ───────────────────────── Swift-facing observers ─────────────────────────

fun observeSwitcherSession(onChange: (Boolean) -> Unit) {
    switcherController.ui
        .map { it != null }
        .distinctUntilChanged()
        .onEach(onChange)
        .launchIn(bridgeScope)
}

fun observeSwitcherVisibility(onChange: (Boolean) -> Unit) {
    switcherController.ui
        .map { it?.visible == true }
        .distinctUntilChanged()
        .onEach(onChange)
        .launchIn(bridgeScope)
}

fun observeShowMenubarIcon(onChange: (Boolean) -> Unit) {
    store.showMenubarIcon
        .onEach(onChange)
        .launchIn(bridgeScope)
}

fun observeLaunchAtLogin(onChange: (Boolean) -> Unit) {
    store.launchAtLogin
        .onEach(onChange)
        .launchIn(bridgeScope)
}

/** Pushes the switcher's "open on which screen" preference to Swift as the
 *  enum's [Enum.name] ("MouseScreen" / "ActiveWindowScreen" / "MainScreen").
 *  Swift caches the latest value and consults it inside `captureSessionScreen`.
 *  String over the bridge keeps this insulated from K/N enum-binding gotchas. */
fun observeSwitcherWindowPlacement(onChange: (String) -> Unit) {
    store.switcherSettings
        .map { it.windowPlacement.name }
        .distinctUntilChanged()
        .onEach(onChange)
        .launchIn(bridgeScope)
}

fun observeSwitcherPanelSize(onChange: (Double, Double) -> Unit, onCleared: () -> Unit) {
    store.switcherPanelSize
        .onEach { size ->
            if (size == null) onCleared() else onChange(size.first, size.second)
        }
        .launchIn(bridgeScope)
}

// ─────────────────────────── Theme provider ───────────────────────────

/**
 * Wraps Compose content in: AppPalette (our hand-tuned light/dark colours),
 * MaterialTheme (so any leftover Material widgets follow the same theme),
 * and the user's accent override.
 */
@Composable
private fun AppShell(content: @Composable () -> Unit) {
    val isDark by store.isDarkMode.collectAsState()
    val accentColor by store.accentColor.collectAsState()
    val systemAccentRgb by store.systemAccentRgb.collectAsState()
    val accent = resolveAccent(accentColor, systemAccentRgb)
    val colorScheme = if (isDark) darkColorScheme() else lightColorScheme()
    ProvideAppPalette(isDark) {
        ProvideAccent(accent) {
            MaterialTheme(colorScheme = colorScheme, content = content)
        }
    }
}

// ─────────────────────────── Window-level entries ───────────────────────────

/**
 * Compose host for the **Settings** window. Renders [SettingsContent] —
 * General + Rules tabs with native-mimicking controls.
 */
fun AttachSettingsView(window: NSWindow): ComposeNSViewDelegate = ComposeNSViewDelegate(
    window = window,
    content = {
        AppShell {
            val switcherSettings by store.switcherSettings.collectAsState()
            val showMenubarIcon by store.showMenubarIcon.collectAsState()
            val launchAtLogin by store.launchAtLogin.collectAsState()
            val currentSpaceOnly by store.currentSpaceOnly.collectAsState()
            val accentColor by store.accentColor.collectAsState()
            val filters by store.filters.collectAsState()
            val badgeRules by store.badgeRules.collectAsState()
            SettingsContent(
                switcherSettings = switcherSettings,
                onSwitcherSettingsChange = { store.setSwitcherSettings(it) },
                showMenubarIcon = showMenubarIcon,
                onShowMenubarIconChange = { store.setShowMenubarIcon(it) },
                launchAtLogin = launchAtLogin,
                onLaunchAtLoginChange = { store.setLaunchAtLogin(it) },
                currentSpaceOnly = currentSpaceOnly,
                onCurrentSpaceOnlyChange = { store.setCurrentSpaceOnly(it) },
                accentColor = accentColor,
                onAccentColorChange = { store.setAccentColor(it) },
                filters = filters,
                onFiltersChange = { store.setFilters(it) },
                badgeRules = badgeRules,
                onBadgeRulesChange = { store.setBadgeRules(it) },
            )
        }
    },
)

/**
 * Compose host for the **Inspector** window. Renders [InspectorContent] —
 * the live snapshot of apps and windows after filtering.
 */
fun AttachInspectorView(window: NSWindow): ComposeNSViewDelegate = ComposeNSViewDelegate(
    window = window,
    content = {
        AppShell {
            val world by store.state.collectAsState()
            val axTrusted by store.axTrusted.collectAsState()
            val activeAppPid by store.activeAppPid.collectAsState()
            val activeWindowId by store.activeWindowId.collectAsState()
            val filters by store.filters.collectAsState()
            val currentSpaceOnly by store.currentSpaceOnly.collectAsState()
            val visibleSpaceIds by store.visibleSpaceIds.collectAsState()
            InspectorContent(
                world = world,
                axTrusted = axTrusted,
                activeAppPid = activeAppPid,
                activeWindowId = activeWindowId,
                filters = filters,
                currentSpaceOnly = currentSpaceOnly,
                visibleSpaceIds = visibleSpaceIds,
                onGrantAxClick = {
                    val granted = requestAxPermission()
                    store.setAxTrusted(granted)
                },
            )
        }
    },
)

/**
 * Compose host for the switcher overlay panel (`NSPanel` provided by Swift).
 * Renders [SwitcherOverlay] when a session is active and visible; otherwise empty.
 *
 * Stays on its own dark cinematic palette regardless of system appearance —
 * the overlay is composited over the active screen contents and a light
 * theme would clash. Only the accent is shared with the rest of the app.
 */
fun AttachSwitcherOverlay(window: NSWindow): ComposeNSViewDelegate = ComposeNSViewDelegate(
    window = window,
    content = {
        val ui by switcherController.ui.collectAsState()
        val icons by store.iconsByPid.collectAsState()
        val accentColor by store.accentColor.collectAsState()
        val systemAccentRgb by store.systemAccentRgb.collectAsState()
        val switcherSettings by store.switcherSettings.collectAsState()
        val axTrusted by store.axTrusted.collectAsState()
        val badgeRules by store.badgeRules.collectAsState()
        val current = ui
        if (current != null) {
            ProvideAccent(resolveAccent(accentColor, systemAccentRgb)) {
                SwitcherOverlay(
                    ui = current,
                    iconsByPid = icons,
                    switcherSettings = switcherSettings,
                    axTrusted = axTrusted,
                    badgeRules = badgeRules,
                    onNavigate = { switcherController.onNavigate(it) },
                    onEsc = { switcherController.onEsc() },
                    onShortcut = { switcherController.onShortcut(it) },
                    onPointAt = { appIndex, windowIndex ->
                        switcherController.onPointAt(appIndex, windowIndex)
                    },
                    onPointerMoved = { switcherController.onPointerMoved() },
                    onPanelSize = { w, h ->
                        store.setSwitcherPanelSize(w.toDouble(), h.toDouble())
                    },
                    onCommit = { switcherController.onCommit() },
                    onGrantAxClick = {
                        val granted = requestAxPermission()
                        store.setAxTrusted(granted)
                    },
                )
            }
        }
    },
)
