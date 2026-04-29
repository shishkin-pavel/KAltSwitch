package com.shish.kaltswitch.log

/**
 * Single point of entry for diagnostic event logging from common code.
 *
 * The implementation is platform-specific: on macOS it prepends a wall-clock
 * timestamp at the moment the call is made and writes to stdout (which the
 * Swift host redirects to ~/Library/Logs/KAltSwitch.log); other platforms
 * (notably commonTest on macosArm64) get the same behaviour. Capturing
 * time *here* — not at the event source — keeps callers free of clock
 * plumbing.
 *
 * Format expected:
 *   `2026-04-29 17:52:03.991 [ctl] message`
 */
expect fun log(message: String)
