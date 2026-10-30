package pl.adrros.bettercodereview.commit

import com.intellij.openapi.components.service
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.atomic.AtomicInteger

class ChangeLineStatsTest : BasePlatformTestCase() {
    private fun revision(path: String) = object : ContentRevision {
        override fun getContent(): String = ""
        override fun getFile() = LocalFilePath(path, false)
        override fun getRevisionNumber(): VcsRevisionNumber = VcsRevisionNumber.NULL
    }

    fun `test lines are counted like the diff viewer counts them`() {
        assertEquals(ChangeLineStats.Lines(0, 0), ChangeLineStats.countLines("a\nb\n", "a\nb\n"))
        assertEquals("a changed line is one of each", ChangeLineStats.Lines(1, 1), ChangeLineStats.countLines("a\nb\nc\n", "a\nB\nc\n"))
        assertEquals(ChangeLineStats.Lines(2, 0), ChangeLineStats.countLines("a\n", "a\nb\nc\n"))
        assertEquals("a new file", ChangeLineStats.Lines(3, 0), ChangeLineStats.countLines("", "x\ny\nz"))
        assertEquals("a deleted file", ChangeLineStats.Lines(0, 2), ChangeLineStats.countLines("x\ny\n", ""))
    }

    fun `test the total waits until every change is counted and is cached afterwards`() {
        val stats = project.service<ChangeLineStats>()
        val defaultCount = stats.count
        val defaultOnCounted = stats.onCounted
        val calls = AtomicInteger()
        var repaints = 0
        try {
            stats.count = { change, _ -> calls.incrementAndGet(); if (change.afterRevision?.file?.name == "A.java") ChangeLineStats.Lines(10, 2) else ChangeLineStats.Lines(1, 5) }
            stats.onCounted = { repaints++ }
            val changes = listOf(Change(null, revision("/nowhere/A.java")), Change(revision("/nowhere/B.java"), revision("/nowhere/B.java")))
            assertNull("not counted yet", stats.totalFor(changes))
            PlatformTestUtil.waitWithEventsDispatching("counting", { repaints > 0 }, 10)
            assertEquals(ChangeLineStats.Lines(11, 7), stats.totalFor(changes))
            assertEquals(ChangeLineStats.Lines(11, 7), stats.totalFor(changes))
            assertEquals("each change counted once", 2, calls.get())
        } finally {
            stats.count = defaultCount
            stats.onCounted = defaultOnCounted
        }
    }

    private fun revisionAt(path: String, number: String) = object : ContentRevision {
        override fun getContent(): String = ""
        override fun getFile() = LocalFilePath(path, false)
        override fun getRevisionNumber(): VcsRevisionNumber = object : VcsRevisionNumber {
            override fun asString(): String = number
            override fun compareTo(other: VcsRevisionNumber?): Int = 0
        }
    }

    fun `test a recounted change keeps its last count until the new one arrives, and a failure is retried later`() {
        val stats = project.service<ChangeLineStats>()
        val defaults = Triple(stats.count, stats.onCounted, stats.now)
        val after = revision("/nowhere/Edited.java")
        var result: () -> ChangeLineStats.Lines = { ChangeLineStats.Lines(1, 1) }
        var repaints = 0
        var clock = 1_000_000L
        try {
            stats.count = { _, _ -> result() }
            stats.onCounted = { repaints++ }
            stats.now = { clock }
            val first = Change(revisionAt("/nowhere/Edited.java", "r1"), after)
            assertNull(stats.totalFor(listOf(first)))
            PlatformTestUtil.waitWithEventsDispatching("first count", { repaints == 1 }, 10)
            assertEquals(ChangeLineStats.Lines(1, 1), stats.totalFor(listOf(first)))

            // The same file against a new base revision: the old count shows while the recount fails.
            result = { throw IllegalStateException("git hiccup") }
            val second = Change(revisionAt("/nowhere/Edited.java", "r2"), after)
            assertEquals(ChangeLineStats.Lines(1, 1), stats.totalFor(listOf(second)))
            PlatformTestUtil.waitWithEventsDispatching("failed count", { repaints == 2 }, 10)
            assertEquals("the failure keeps the last count", ChangeLineStats.Lines(1, 1), stats.totalFor(listOf(second)))
            assertEquals("no retry within the pause", 2, repaints)

            clock += 60_000
            result = { ChangeLineStats.Lines(4, 0) }
            stats.totalFor(listOf(second))
            PlatformTestUtil.waitWithEventsDispatching("retried count", { repaints == 3 }, 10)
            assertEquals(ChangeLineStats.Lines(4, 0), stats.totalFor(listOf(second)))
        } finally {
            stats.count = defaults.first
            stats.onCounted = defaults.second
            stats.now = defaults.third
        }
    }

    fun `test a committed change is counted apart from the local change of the same file`() {
        val stats = project.service<ChangeLineStats>()
        val defaults = Pair(stats.count, stats.onCounted)
        val calls = AtomicInteger()
        var repaints = 0
        try {
            stats.count = { _, committed -> assertEquals("only the first count is local", calls.get() >= 1, committed); ChangeLineStats.Lines(calls.incrementAndGet(), 0) }
            stats.onCounted = { repaints++ }
            val local = Change(revisionAt("/nowhere/Shared.java", "r1"), revision("/nowhere/Shared.java"))
            val committed = Change(revisionAt("/nowhere/Shared.java", "r1"), revisionAt("/nowhere/Shared.java", "r2"))
            stats.totalFor(listOf(local))
            PlatformTestUtil.waitWithEventsDispatching("local count", { repaints == 1 }, 10)
            assertNull("the local count is not the committed one", stats.totalFor(listOf(committed), committed = true))
            PlatformTestUtil.waitWithEventsDispatching("committed count", { repaints == 2 }, 10)
            assertEquals(ChangeLineStats.Lines(1, 0), stats.totalFor(listOf(local)))
            assertEquals(ChangeLineStats.Lines(2, 0), stats.totalFor(listOf(committed), committed = true))

            val otherCommit = Change(revisionAt("/nowhere/Shared.java", "r2"), revisionAt("/nowhere/Shared.java", "r3"))
            assertNull("another commit's change shows no stale count", stats.totalFor(listOf(otherCommit), committed = true))
        } finally {
            stats.count = defaults.first
            stats.onCounted = defaults.second
        }
    }

    fun `test the change list shows the total after the file count`() {
        val stats = project.service<ChangeLineStats>()
        val defaults = Pair(stats.count, stats.onCounted)
        var repaints = 0
        try {
            stats.count = { _, _ -> ChangeLineStats.Lines(120, 34) }
            stats.onCounted = { repaints++ }
            val list = com.intellij.openapi.vcs.changes.LocalChangeListImpl.Builder(project, "Changes")
                .setChanges(listOf(Change(null, revision("/nowhere/Listed.java")))).build()
            val renderer = object : com.intellij.ui.ColoredTreeCellRenderer() {
                override fun customizeCellRenderer(tree: javax.swing.JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {}
            }
            val decorator = ChangeLineStats.Decorator(project)
            decorator.decorateChangeList(list, renderer, false, false, false)
            assertEquals("nothing until counted", "", renderer.toString())
            PlatformTestUtil.waitWithEventsDispatching("count", { repaints > 0 }, 10)
            decorator.decorateChangeList(list, renderer, false, false, false)
            assertEquals("  +120 \u221234", renderer.toString())
        } finally {
            stats.count = defaults.first
            stats.onCounted = defaults.second
        }
    }
}
