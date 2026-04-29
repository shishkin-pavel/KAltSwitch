package com.shish.kaltswitch

/**
 * Best-effort display title for a window.
 *
 * Two-step heuristic:
 * 1. If the AX title is blank, return the app name — useful for menubar
 *    utilities and unfocused windows where AX hasn't populated the title.
 * 2. Otherwise strip every occurrence of the app name from the title and
 *    trim whitespace + common macOS separators (em-dash, en-dash, hyphen,
 *    pipe, middle-dot, "::", colon) off the edges. If what's left is
 *    blank — i.e. the title was "AppName" / "  — AppName  " / " | AppName |
 *    AppName " — fall back to the app name. Otherwise that's the result.
 *
 * Stripping standalone substrings rather than only suffix/prefix forms
 * with a fixed separator covers both halves of "Document — AppName" and
 * "AppName — Document" with a single replace, plus catches degenerate
 * cases like a bare "AppName" or stray trailing app-name decoration.
 */
internal fun effectiveWindowTitle(rawTitle: String, appName: String): String {
    if (rawTitle.isBlank()) return appName
    if (appName.isEmpty()) return rawTitle.trim()
    val withoutApp = rawTitle.replace(appName, "")
    val trimmed = withoutApp.trim { it in TitleSeparatorChars }
    return trimmed.ifBlank { appName }
}

/** Whitespace + common macOS title separators. Kept narrow on purpose:
 *  hyphen `-` is included because some apps use " - " between filename and
 *  app name; if a real title legitimately ends with a hyphen the trim
 *  eats it but the result stays meaningful. */
private val TitleSeparatorChars: Set<Char> = setOf(
    ' ', '\t', '\n',
    '—',  // em-dash U+2014
    '–',  // en-dash U+2013
    '-',
    '|',
    '·',  // middle dot U+00B7
    ':',
)
