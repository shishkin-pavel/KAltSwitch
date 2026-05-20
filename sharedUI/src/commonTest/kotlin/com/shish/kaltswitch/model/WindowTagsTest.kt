package com.shish.kaltswitch.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class WindowTagsTest {

    private fun snapshotOf(vararg appsAndWindowIds: Pair<String, List<Long>>): SwitcherSnapshot {
        val entries = appsAndWindowIds.mapIndexed { i, (name, ids) ->
            val app = App(pid = i + 1, bundleId = null, name = name)
            val windows = ids.map { Window(id = it, pid = app.pid, title = "$name#$it") }
            AppEntry(app, windows)
        }
        val (withW, withoutW) = entries.partition { it.hasWindows }
        return SwitcherSnapshot(withW, withoutW)
    }

    @Test
    fun assign_addsBindingAndReverseLookup() {
        val tags = WindowTags.Empty.assign(digit = 1, pid = 7, windowId = 42L)
        assertEquals(7 to 42L, tags.windowFor(1))
        assertEquals(1, tags.digitFor(pid = 7, windowId = 42L))
        assertNull(tags.digitFor(pid = 7, windowId = 99L))
    }

    @Test
    fun assign_sameDigitSameWindow_unbinds() {
        val tags = WindowTags.Empty
            .assign(digit = 1, pid = 7, windowId = 42L)
            .assign(digit = 1, pid = 7, windowId = 42L)
        assertNull(tags.windowFor(1))
        assertNull(tags.digitFor(pid = 7, windowId = 42L))
        assertEquals(WindowTags.Empty, tags)
    }

    @Test
    fun assign_sameDigitDifferentWindow_transfers() {
        val tags = WindowTags.Empty
            .assign(digit = 1, pid = 7, windowId = 42L)
            .assign(digit = 1, pid = 7, windowId = 43L)
        assertEquals(7 to 43L, tags.windowFor(1))
        assertNull(tags.digitFor(pid = 7, windowId = 42L))
        assertEquals(1, tags.digitFor(pid = 7, windowId = 43L))
    }

    @Test
    fun assign_differentDigitSameWindow_movesDigit() {
        val tags = WindowTags.Empty
            .assign(digit = 1, pid = 7, windowId = 42L)
            .assign(digit = 2, pid = 7, windowId = 42L)
        assertNull(tags.windowFor(1))
        assertEquals(7 to 42L, tags.windowFor(2))
        assertEquals(2, tags.digitFor(pid = 7, windowId = 42L))
    }

    @Test
    fun assign_rejectsOutOfRangeDigit() {
        assertFailsWith<IllegalArgumentException> {
            WindowTags.Empty.assign(digit = 0, pid = 1, windowId = 1L)
        }
        assertFailsWith<IllegalArgumentException> {
            WindowTags.Empty.assign(digit = 10, pid = 1, windowId = 1L)
        }
    }

    @Test
    fun prunedAgainst_dropsDeadWindows_preservesLive() {
        val snap = snapshotOf("A" to listOf(10L, 11L), "B" to listOf(20L))
        val tags = WindowTags.Empty
            .assign(digit = 1, pid = 1, windowId = 10L)  // lives in A
            .assign(digit = 2, pid = 1, windowId = 99L)  // dead — A doesn't have this
            .assign(digit = 3, pid = 2, windowId = 20L)  // lives in B
            .assign(digit = 4, pid = 9, windowId = 1L)   // dead — no such pid

        val pruned = tags.prunedAgainst(snap)
        assertEquals(1 to 10L, pruned.windowFor(1))
        assertNull(pruned.windowFor(2))
        assertEquals(2 to 20L, pruned.windowFor(3))
        assertNull(pruned.windowFor(4))
    }

    @Test
    fun prunedAgainst_noOp_returnsSameInstance() {
        val snap = snapshotOf("A" to listOf(10L))
        val tags = WindowTags.Empty.assign(digit = 1, pid = 1, windowId = 10L)
        assertSame(tags, tags.prunedAgainst(snap))
    }

    @Test
    fun prunedAgainst_empty_returnsSameInstance() {
        val snap = snapshotOf("A" to listOf(10L))
        assertSame(WindowTags.Empty, WindowTags.Empty.prunedAgainst(snap))
    }

    @Test
    fun prunedAgainst_findsTaggedChildWindows() {
        // A pinned child lives in navigableWindows but not in the top-level
        // `windows` list — pruning must consult the DFS-flattened list, not
        // only the top-level set, or pinned-child tags would die spuriously.
        val app = App(pid = 1, bundleId = null, name = "FF")
        val child = Window(id = 200L, pid = 1, title = "PiP")
        val root = Window(id = 100L, pid = 1, title = "main", children = listOf(child))
        val entry = AppEntry(app = app, windows = listOf(root))
        val snap = SwitcherSnapshot(withWindows = listOf(entry), windowless = emptyList())

        val tags = WindowTags.Empty.assign(digit = 1, pid = 1, windowId = 200L)
        assertSame(tags, tags.prunedAgainst(snap))
    }
}
