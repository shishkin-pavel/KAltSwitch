@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.shish.kaltswitch.config

import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.serialization.encodeToString
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSLog
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToURL

/**
 * Reads and writes [AppConfig] to `~/Library/Application Support/KAltSwitch/config.json`.
 * Pure I/O — call sites are responsible for debouncing.
 */
object ConfigStore {

    fun load(): AppConfig? {
        val url = configFileURL() ?: return null
        val data = NSData.dataWithContentsOfURL(url) ?: return null
        // The cast is the documented NSString→Kotlin String bridge in K/N, even
        // though the compiler flags it as "no cast needed" — without the cast
        // `decodeFromString` gets an `NSString` that doesn't satisfy `String`.
        @Suppress("USELESS_CAST")
        val text = NSString.create(data = data, encoding = NSUTF8StringEncoding) as String? ?: return null
        return try {
            configJson.decodeFromString(AppConfig.serializer(), text)
        } catch (t: Throwable) {
            NSLog("KAltSwitch: failed to parse config: ${t.message}")
            null
        }
    }

    fun save(config: AppConfig) {
        val url = configFileURL() ?: return
        ensureParentDirectoryExists(url)
        val text = configJson.encodeToString(config)
        val nsString = NSString.create(string = text)
        val data = nsString.dataUsingEncoding(NSUTF8StringEncoding) ?: return
        if (!data.writeToURL(url, atomically = true)) {
            NSLog("KAltSwitch: failed to write config to %@", url.absoluteString ?: "?")
        }
    }

    /** `~/Library/Application Support/KAltSwitch/config.json` as an NSURL, or null on failure. */
    private fun configFileURL(): NSURL? {
        val fm = NSFileManager.defaultManager
        @Suppress("UNCHECKED_CAST")
        val urls = fm.URLsForDirectory(
            directory = NSApplicationSupportDirectory,
            inDomains = NSUserDomainMask,
        ) as List<NSURL>
        val support = urls.firstOrNull() ?: return null
        // Plain nullable chain — `load()` and `save()` both already treat a
        // null URL as "skip silently". Force-unwrapping here would crash on
        // the rare Foundation hiccup, which is not worth dying for.
        return support
            .URLByAppendingPathComponent("KAltSwitch")
            ?.URLByAppendingPathComponent("config.json")
    }

    private fun ensureParentDirectoryExists(fileURL: NSURL) {
        val parent = fileURL.URLByDeletingLastPathComponent ?: return
        val fm = NSFileManager.defaultManager
        if (fm.fileExistsAtPath(parent.path ?: return)) return
        memScoped {
            val errVar = alloc<ObjCObjectVar<NSError?>>()
            fm.createDirectoryAtURL(
                url = parent,
                withIntermediateDirectories = true,
                attributes = null,
                error = errVar.ptr,
            )
            errVar.value?.let { NSLog("KAltSwitch: mkdir failed: %@", it.localizedDescription) }
        }
    }
}
