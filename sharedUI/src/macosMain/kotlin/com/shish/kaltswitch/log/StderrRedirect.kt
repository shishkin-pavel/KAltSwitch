@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.shish.kaltswitch.log

import platform.AppKit.NSWorkspace
import platform.Foundation.NSBundle
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSFileManager
import platform.Foundation.NSISO8601DateFormatter
import platform.Foundation.NSLibraryDirectory
import platform.Foundation.NSLocale
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSTimeZone
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.timeZoneWithName
import platform.posix.fflush
import platform.posix.freopen
import platform.posix.setbuf
import platform.posix.stderr
import platform.posix.stdout

/** Filename prefix shared by every session log so [pruneOldSessionLogs] can
 *  spot ours and leave the user's other files in `~/Library/Logs/` alone. */
private const val LOG_FILE_PREFIX = "KAltSwitch-"
private const val LOG_FILE_SUFFIX = ".log"

/** Keep this many most-recent session files; older ones are deleted at
 *  startup so the directory doesn't grow unboundedly. */
private const val SESSION_RETENTION_COUNT = 20

/**
 * `~/Library/Logs/KAltSwitch/` — the per-session log directory. Public so
 * the "Open logs folder" button in Settings can resolve the same path.
 */
fun logsDirectoryUrl(): NSURL? {
    val fm = NSFileManager.defaultManager
    @Suppress("UNCHECKED_CAST")
    val urls = fm.URLsForDirectory(
        directory = NSLibraryDirectory,
        inDomains = NSUserDomainMask,
    ) as List<NSURL>
    val library = urls.firstOrNull() ?: return null
    val logsRoot = library.URLByAppendingPathComponent("Logs", isDirectory = true) ?: return null
    return logsRoot.URLByAppendingPathComponent("KAltSwitch", isDirectory = true)
}

/**
 * Reveal `~/Library/Logs/KAltSwitch/` in Finder. No-op if the directory
 * doesn't exist yet (it's created on first launch via
 * [redirectStderrToLogFile]).
 */
fun openLogsDirectory() {
    val url = logsDirectoryUrl() ?: return
    NSWorkspace.sharedWorkspace.openURL(url)
}

/**
 * Redirect stdout + stderr to a **fresh** per-session file under
 * `~/Library/Logs/KAltSwitch/`, naming it by launch timestamp so a
 * lexicographic sort is also a chronological sort. After the redirect,
 * prune older session files down to [SESSION_RETENTION_COUNT] entries
 * (macOS does not auto-clean `~/Library/Logs/`).
 *
 * One-file-per-session means slicing the latest run is just opening the
 * newest file — no more `awk '/^=== SESSION START/{buf=""}...'` recipe.
 */
fun redirectStderrToLogFile() {
    val dir = logsDirectoryUrl() ?: return
    val fm = NSFileManager.defaultManager
    fm.createDirectoryAtURL(dir, withIntermediateDirectories = true, attributes = null, error = null)

    pruneOldSessionLogs(dir)

    val filename = "$LOG_FILE_PREFIX${sessionFilenameTimestamp()}$LOG_FILE_SUFFIX"
    val logURL = dir.URLByAppendingPathComponent(filename) ?: return
    val logPath = logURL.path ?: return

    // "a" = append. A fresh session file doesn't exist yet so append also
    // acts as create; sticking with "a" is defensive in case the same
    // filename is re-used within one second of the previous launch.
    freopen(logPath, "a", stderr)
    freopen(logPath, "a", stdout)
    setbuf(stderr, null)
    setbuf(stdout, null)

    val info = NSBundle.mainBundle.infoDictionary
    val bundleVersion = info?.get("CFBundleVersion") as? String ?: "unknown"
    val bundleShort = info?.get("CFBundleShortVersionString") as? String ?: "unknown"
    val pid = NSProcessInfo.processInfo.processIdentifier
    val date = NSISO8601DateFormatter().stringFromDate(NSDate())
    println("=== SESSION START at $date pid=$pid version=$bundleShort build=$bundleVersion log=$logPath")
    fflush(stdout)
}

/** UTC ISO-8601-with-dashes (no colons → safe in macOS filenames and
 *  still lexicographic-sort-equivalent to chronological order). */
private fun sessionFilenameTimestamp(): String {
    val utc = NSTimeZone.timeZoneWithName("UTC") ?: NSTimeZone.timeZoneWithName("GMT")
    val fmt = NSDateFormatter().apply {
        dateFormat = "yyyy-MM-dd'T'HH-mm-ss'Z'"
        locale = NSLocale("en_US_POSIX")
        if (utc != null) timeZone = utc
    }
    return fmt.stringFromDate(NSDate())
}

/**
 * Delete session-log files beyond [SESSION_RETENTION_COUNT] most recent.
 * Filename-based ordering — the timestamp embedded in the filename is
 * lexicographically sortable, so we don't pay for stat() calls.
 */
private fun pruneOldSessionLogs(dir: NSURL) {
    val fm = NSFileManager.defaultManager
    @Suppress("UNCHECKED_CAST")
    val contents = fm.contentsOfDirectoryAtURL(
        url = dir,
        includingPropertiesForKeys = null,
        options = 0u,
        error = null,
    ) as? List<NSURL> ?: return
    val sessionFiles = contents.filter { url ->
        val name = url.lastPathComponent ?: return@filter false
        name.startsWith(LOG_FILE_PREFIX) && name.endsWith(LOG_FILE_SUFFIX)
    }
    if (sessionFiles.size <= SESSION_RETENTION_COUNT) return
    val sorted = sessionFiles.sortedByDescending { it.lastPathComponent ?: "" }
    sorted.drop(SESSION_RETENTION_COUNT).forEach { url ->
        fm.removeItemAtURL(url, error = null)
    }
}
