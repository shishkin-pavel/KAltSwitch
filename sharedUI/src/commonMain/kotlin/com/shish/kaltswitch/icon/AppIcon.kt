package com.shish.kaltswitch.icon

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

/** Decode raw PNG bytes into an [ImageBitmap], cached per (pid, byte-array-identity). */
@Composable
fun rememberAppIcon(pid: Int, bytes: ByteArray?): ImageBitmap? {
    if (bytes == null) return null
    return remember(pid, bytes) {
        runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
    }
}
