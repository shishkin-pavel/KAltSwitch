package com.shish.kaltswitch

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.shish.kaltswitch.native.requestAxPermission
import com.shish.kaltswitch.store.WorldStore
import com.shish.kaltswitch.viewcontroller.ComposeNSViewDelegate
import platform.AppKit.NSWindow

/** Singleton store. Swift's `AppRegistry` mutates it; the Compose UI observes it. */
val store = WorldStore()

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
