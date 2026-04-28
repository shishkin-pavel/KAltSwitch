package com.shish.kaltswitch

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.shish.kaltswitch.config.AppConfig
import com.shish.kaltswitch.config.ConfigStore
import com.shish.kaltswitch.native.requestAxPermission
import com.shish.kaltswitch.store.WorldStore
import com.shish.kaltswitch.viewcontroller.ComposeNSViewDelegate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import platform.AppKit.NSWindow

/** Singleton store. Swift's `AppRegistry` mutates it; the Compose UI observes it. */
val store = WorldStore().also { initStore ->
    // Load persisted config on first access so the UI starts with the user's filters.
    ConfigStore.load()?.let { cfg -> initStore.setFilters(cfg.filters) }
}

/**
 * Persists filter changes to disk. Skips the first emission (initial value) so we
 * don't immediately overwrite the file we just loaded; subsequent edits flow to disk.
 */
private val configScope = CoroutineScope(Dispatchers.Main).also { scope ->
    store.filters
        .drop(1)
        .onEach { ConfigStore.save(AppConfig(filters = it)) }
        .launchIn(scope)
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
