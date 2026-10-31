package pl.adrros.bettercodereview.commit

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListDecorator
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.changes.LocalChangeList
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import kotlinx.coroutines.CoroutineScope
import pl.adrros.bettercodereview.review.files.ReviewChangeTrees
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Color
import java.util.concurrent.ConcurrentHashMap
import pl.adrros.bettercodereview.settings.Feature

private val LOG = logger<ChangeLineStats>()

/**
 * Lines added and removed per change, counted like the diff viewer counts them (a modified line is one of each):
 * local changes with unsaved edits included, and the committed changes of a pull request. Counted in the background
 * and cached by revision and modification stamp; until every change of a list is counted the list shows no total.
 */
@Service(Service.Level.PROJECT)
class ChangeLineStats(private val project: Project, private val scope: CoroutineScope) {
    data class Lines(val added: Int, val deleted: Int) {
        operator fun plus(other: Lines) = Lines(added + other.added, deleted + other.deleted)
    }

    /** What makes a change's count stale: its paths, both revisions and, for a local change, the current content's stamp. */
    data class Key(val before: String?, val beforeRevision: String?, val after: String?, val afterRevision: String?, val afterStamp: Long, val committed: Boolean) {
        /** A local change has one entry, overwritten as it is edited; a committed change never changes, so it is an entry of its own. */
        val change: Any get() = if (committed) this else (before to after)
    }

    private class Entry(val key: Key, val lines: Lines?, val failedAt: Long = 0)

    private val latest = ConcurrentHashMap<Any, Entry>()
    private val counting = ConcurrentHashMap.newKeySet<Any>()
    private val reported = ConcurrentHashMap.newKeySet<Key>()

    /** Replaceable in tests; runs on a background thread. The flag tells a committed change from a local one. */
    var count: suspend (Change, Boolean) -> Lines = ::countChange

    /** Called whenever counts arrive; repaints the Commit and pull request trees. */
    var onCounted: () -> Unit = {
        CommitInclusionMarks.repaintAll()
        ReviewChangeTrees.repaintAll()
    }

    /** Replaceable in tests. */
    var now: () -> Long = System::currentTimeMillis

    /**
     * The sum over [changes], or null until each of them has been counted once. A change edited since keeps its last
     * count until the new one arrives, so the total does not blink while typing. One count per change runs at a time.
     * [committed] changes compare two revisions, so editing the file locally does not recount them.
     */
    fun totalFor(changes: Collection<Change>, committed: Boolean = false): Lines? {
        var total = Lines(0, 0)
        var complete = true
        val toCount = ArrayList<Pair<Key, Change>>()
        for (change in changes) {
            val key = keyOf(change, committed)
            val entry = latest[key.change]
            entry?.lines?.let { total += it } ?: run { complete = false }
            val stale = entry == null || entry.key != key || (entry.failedAt > 0 && now() - entry.failedAt > RETRY_MS)
            if (stale && counting.add(key.change)) toCount.add(key to change)
        }
        if (toCount.isNotEmpty()) launchCount(toCount)
        return if (complete) total else null
    }

    private fun launchCount(toCount: List<Pair<Key, Change>>) {
        scope.launch(Dispatchers.IO) {
            if (latest.size > MAX_CACHED) latest.clear()
            // Committed changes are counted once per pull request, so their progress goes to the IDE log at INFO.
            val committed = toCount.count { it.first.committed }
            if (committed > 0) LOG.info("Counting the lines of $committed pull request file(s)")
            var failed = 0
            for ((key, change) in toCount) {
                latest[key.change] = try {
                    Entry(key, count(change, key.committed))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Retried after a pause rather than remembered as zero: a VCS hiccup must not stick.
                    if (key.committed) failed++
                    if (key.committed && reported.add(key)) LOG.info("Could not count the lines of ${key.after ?: key.before} (${key.beforeRevision}..${key.afterRevision})", e)
                    else LOG.debug("Could not count the lines of $change", e)
                    Entry(key, latest[key.change]?.lines, failedAt = now())
                } finally {
                    counting.remove(key.change)
                }
            }
            if (committed > 0) LOG.info("Counted the lines of $committed pull request file(s), $failed failed")
            withContext(Dispatchers.EDT) { onCounted() }
        }
    }

    private fun keyOf(change: Change, committed: Boolean): Key {
        val after = change.afterRevision
        val file = if (committed) null else after?.file?.virtualFile
        val stamp = file?.let { FileDocumentManager.getInstance().getCachedDocument(it)?.modificationStamp ?: it.modificationStamp } ?: -1
        return Key(
            change.beforeRevision?.file?.path,
            change.beforeRevision?.revisionNumber?.asString(),
            after?.file?.path,
            after?.revisionNumber?.asString(),
            stamp,
            committed,
        )
    }

    private suspend fun countChange(change: Change, committed: Boolean): Lines {
        if (isBinary(change.beforeRevision) || isBinary(change.afterRevision)) return Lines(0, 0)
        val before = change.beforeRevision?.content.orEmpty()
        // Only the working copy needs the read lock (unsaved documents); a committed revision is read from git, which must not hold it.
        val after = change.afterRevision?.let { revision -> if (committed) revision.content else readAction { revision.content } }.orEmpty()
        if (before.length > MAX_CHARS || after.length > MAX_CHARS) return Lines(0, 0)
        return countLines(before, after)
    }

    private fun isBinary(revision: ContentRevision?): Boolean = revision?.file?.fileType?.isBinary == true

    companion object {
        private const val MAX_CACHED = 5_000
        private const val RETRY_MS = 30_000L
        private const val MAX_CHARS = 2_000_000

        val ADDED: Color = JBColor.namedColor("BetterCodeReview.LineStats.added", JBColor(Color(0x1A, 0x7F, 0x37), Color(0x3F, 0xB9, 0x50)))
        val DELETED: Color = JBColor.namedColor("BetterCodeReview.LineStats.deleted", JBColor(Color(0xCF, 0x22, 0x2E), Color(0xF8, 0x51, 0x49)))

        fun countLines(before: CharSequence, after: CharSequence): Lines {
            // The comparison sees empty text as one empty line; a new or emptied file counts its lines like git does.
            if (before.isEmpty()) return Lines(lineCount(after), 0)
            if (after.isEmpty()) return Lines(0, lineCount(before))
            val fragments = ComparisonManager.getInstance().compareLines(before, after, ComparisonPolicy.DEFAULT, EmptyProgressIndicator())
            return Lines(fragments.sumOf { it.endLine2 - it.startLine2 }, fragments.sumOf { it.endLine1 - it.startLine1 })
        }

        private fun lineCount(text: CharSequence): Int =
            if (text.isEmpty()) 0 else text.count { it == '\n' } + if (text.last() == '\n') 0 else 1
    }

    /** Appends "+added −removed" after the file count of a change list in the Commit tool window. */
    class Decorator(private val project: Project) : ChangeListDecorator {
        override fun decorateChangeList(changeList: LocalChangeList, renderer: ColoredTreeCellRenderer, selected: Boolean, expanded: Boolean, hasFocus: Boolean) {
            val changes = changeList.changes
            if (changes.isEmpty() || !Feature.LINE_COUNTS.isOn) return
            val lines = project.service<ChangeLineStats>().totalFor(changes) ?: return
            if (lines.added == 0 && lines.deleted == 0) return
            // On the focused selection the row's own foreground keeps the text readable.
            val onSelection = selected && hasFocus
            renderer.append("  +${lines.added}", if (onSelection) SimpleTextAttributes.REGULAR_ATTRIBUTES else SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, ADDED))
            renderer.append(" −${lines.deleted}", if (onSelection) SimpleTextAttributes.REGULAR_ATTRIBUTES else SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, DELETED))
        }
    }
}
