package com.shish.kaltswitch

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.shish.kaltswitch.native.WorkspaceWatcher
import com.shish.kaltswitch.store.WorldStore
import com.shish.kaltswitch.viewcontroller.ComposeNSViewDelegate
import platform.AppKit.NSWindow

private val store = WorldStore()
private val workspaceWatcher = WorkspaceWatcher(store).also { it.start() }

fun AttachMainComposeView(
    window: NSWindow,
): ComposeNSViewDelegate = ComposeNSViewDelegate(
    window = window,
    content = {
        val world by store.state.collectAsState()
        App(world)
    },
)
