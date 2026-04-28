package com.shish.kaltswitch

import com.shish.kaltswitch.viewcontroller.ComposeNSViewDelegate
import platform.AppKit.NSWindow

fun AttachMainComposeView(
    window: NSWindow,
): ComposeNSViewDelegate = ComposeNSViewDelegate(
    window = window,
    content = { App() },
)
