package com.shish.kaltswitch.model

/**
 * Per-session digit "bookmarks" the user assigns to individual windows from
 * inside the switcher (cmd+ctrl+digit). A later plain-digit press (cmd+digit
 * while the switcher is open) moves the cursor straight to the tagged window.
 *
 * Identity is the window's `(pid, windowId)` pair, not its on-screen index —
 * a snapshot refresh that reorders / inserts / removes other windows must not
 * disturb the tag. Tags persist for the *process lifetime*: across switcher
 * sessions, but not to disk (WindowIds aren't stable across process restarts,
 * and writing them to disk would just leak stale state on next launch).
 *
 * Digits are restricted to `1..9` — matches every other "numeric bookmark"
 * surface the user already knows (browser tab keys, Sublime bookmarks). Zero
 * is excluded deliberately so the digit row reads top-to-bottom on a number
 * pad without a discontinuity.
 *
 * Invariants enforced by the constructors:
 *   - Each digit maps to at most one window (a re-assign on a different
 *     window transfers the digit — see [assign]).
 *   - Each window holds at most one digit (assigning a second digit to a
 *     window already tagged moves the digit; the old digit is freed).
 */
data class WindowTags(
    /** digit (1..9) → (pid, windowId) the user bound to that digit. */
    val byDigit: Map<Int, Pair<Pid, WindowId>> = emptyMap(),
) {
    /** Reverse lookup. Built lazily so the common case (no tags) costs nothing. */
    val byWindow: Map<Pair<Pid, WindowId>, Int> by lazy {
        if (byDigit.isEmpty()) emptyMap()
        else byDigit.entries.associate { (d, key) -> key to d }
    }

    fun digitFor(pid: Pid, windowId: WindowId): Int? =
        if (byDigit.isEmpty()) null else byWindow[pid to windowId]

    fun windowFor(digit: Int): Pair<Pid, WindowId>? = byDigit[digit]

    /**
     * Apply `digit` to `(pid, windowId)`. Three cases, in order:
     *   1. The digit already points at exactly this window → unbind (toggle off).
     *   2. The window already has a *different* digit → reassign: the new digit
     *      wins, the window's old digit is freed.
     *   3. Otherwise → bind digit to the window, evicting whoever else held it.
     */
    fun assign(digit: Int, pid: Pid, windowId: WindowId): WindowTags {
        require(digit in DIGIT_RANGE) { "digit out of range: $digit" }
        val key = pid to windowId
        val existingForDigit = byDigit[digit]
        if (existingForDigit == key) {
            // Toggle off — same digit on the same window unbinds.
            return copy(byDigit = byDigit - digit)
        }
        val previousDigitForWindow = digitFor(pid, windowId)
        val next = HashMap(byDigit)
        if (previousDigitForWindow != null) next.remove(previousDigitForWindow)
        // Whoever else held this digit (possibly nobody) is evicted by the put.
        next[digit] = key
        return copy(byDigit = next)
    }

    /**
     * Drop tags that no longer refer to a real window in [snapshot]. Returns
     * `this` unchanged when nothing needs pruning so a no-op refresh doesn't
     * fan out a StateFlow emission.
     */
    fun prunedAgainst(snapshot: SwitcherSnapshot): WindowTags {
        if (byDigit.isEmpty()) return this
        val live = buildSet {
            for (entry in snapshot.all) {
                val pid = entry.app.pid
                for (w in entry.navigableWindows) add(pid to w.id)
            }
        }
        val kept = byDigit.filterValues { it in live }
        if (kept.size == byDigit.size) return this
        return copy(byDigit = kept)
    }

    companion object {
        val DIGIT_RANGE = 1..9
        val Empty = WindowTags()
    }
}
