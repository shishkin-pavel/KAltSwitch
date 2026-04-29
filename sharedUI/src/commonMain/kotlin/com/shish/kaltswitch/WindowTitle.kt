package com.shish.kaltswitch

/**
 * Best-effort display title for a window.
 *
 * - Blank / null / whitespace-only AX titles fall back to the app name —
 *   useful for menubar utilities and unfocused windows where AX hasn't
 *   populated the title yet.
 * - Otherwise we try to strip the app name plus a common separator from
 *   the title (e.g. "Document.txt — TextEdit" → "Document.txt"). If
 *   stripping leaves nothing meaningful (or doesn't apply) we keep the
 *   raw title.
 *
 * Pure utility, exposed as `internal` so the overlay and inspector can
 * agree on the same logic.
 */
internal fun effectiveWindowTitle(rawTitle: String, appName: String): String {
    val title = rawTitle.trim()
    if (title.isBlank()) return appName
    if (title.equals(appName, ignoreCase = false)) return appName

    // Common macOS title-format separators between filename and app name.
    // Order matters: longer/specific first so we don't strip a substring of
    // a longer valid separator.
    val separators = listOf(" — ", " – ", " - ", " | ", " · ", " :: ", ": ")
    for (sep in separators) {
        // "Document.txt — AppName"
        val suffix = "$sep$appName"
        if (title.endsWith(suffix)) {
            val stripped = title.removeSuffix(suffix).trim()
            if (stripped.isNotBlank()) return stripped
        }
        // "AppName — Document.txt"
        val prefix = "$appName$sep"
        if (title.startsWith(prefix)) {
            val stripped = title.removePrefix(prefix).trim()
            if (stripped.isNotBlank()) return stripped
        }
    }
    return title
}
