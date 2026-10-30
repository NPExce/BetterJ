package pl.adrros.bettercodereview.review.files

import com.intellij.collaboration.util.RefComparisonChange
import com.intellij.ide.DataManager
import com.intellij.openapi.components.service
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.ui.JBColor
import com.intellij.ui.paint.RectanglePainter2D
import com.intellij.util.ui.JBUI
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.ui.render.RenderingUtil
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import javax.swing.JCheckBox
import javax.swing.SwingUtilities
import javax.swing.tree.TreePath
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.launchOnShow
import com.intellij.util.ui.tree.TreeUtil
import kotlinx.coroutines.Job
import org.jetbrains.plugins.github.api.data.pullrequest.GHPullRequestFileViewedState
import org.jetbrains.plugins.github.pullrequest.ui.details.model.GHPRChangeListViewModel
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import git4idea.GitContentRevision
import git4idea.GitRevisionNumber
import pl.adrros.bettercodereview.commit.ChangeLineStats
import pl.adrros.bettercodereview.review.DecorationOwner
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JComponent
import javax.swing.JTree

/**
 * Marks the file open in the editor inside the pull request's change tree, so the reader always knows which
 * changed file they are in and can tick it as viewed. The mark is a transparent child component laid over the
 * tree: Swing paints children last, so it shows on top of selection, hover and file colours, and it never takes
 * a mouse event. The tree, its UI and its renderer stay untouched.
 */
private val LOG = logger<ReviewChangeTrees>()

object ReviewChangeTrees {
    const val INSTALLED = "BetterCodeReview.currentFileHighlight"
    private const val REVIEW_TREE_FACTORY = "com.intellij.collaboration.ui.codereview.changes.CodeReviewChangeListComponentFactory"
    private const val BAR_WIDTH = CurrentFileMark.BAR_WIDTH
    private const val FOLDER_BAR_ALPHA = 128
    private const val MAX_CACHED_CHANGES = 5_000
    private const val FETCH_WAIT_MS = 10_000L

    val HIGHLIGHT: Color get() = CurrentFileMark.HIGHLIGHT

    /** Amber for pending comments: distinct from the purple mark and from the platform's blue selection. */
    val PENDING: Color = JBColor.namedColor("BetterCodeReview.PendingComments.foreground", JBColor(Color(0xE0, 0x8A, 0x00), Color(0xFB, 0xBF, 0x24)))

    val DISMISSED_DASH: Color get() = RowSlots.DASH

    fun isStateSlot(component: Component): Boolean = RowSlots.isStateSlot(component)

    val DISMISSED_DASH_ON_CURRENT: Color get() = RowSlots.DASH_ON_CURRENT

    /** Dark digits read on amber in both themes (white would fall below 3:1). */
    val PENDING_TEXT: Color = JBColor.namedColor("BetterCodeReview.PendingComments.text", JBColor(Color(0x1F, 0x1F, 0x1F), Color(0x1F, 0x1F, 0x1F)))

    /** The pull request's number behind the tree, for the reviewer's own flags; headless tests replace it. */
    var pullRequestNumberOf: (JTree) -> Long? = { tree ->
        DataManager.getInstance().getDataContext(tree).getData(org.jetbrains.plugins.github.pullrequest.action.GHPRActionKeys.PULL_REQUEST_ID)?.number
    }

    /** Finds the change list model above the tree; headless tests, whose data contexts are empty, replace it. */
    var changeListModelOf: (JTree) -> GHPRChangeListViewModel? = { tree ->
        DataManager.getInstance().getDataContext(tree).getData(GHPRChangeListViewModel.DATA_KEY)
    }

    // A WeakHashMap-backed set: its iterator skips entries collected mid-iteration instead of throwing.
    private val installed: MutableSet<JTree> = java.util.Collections.newSetFromMap(java.util.WeakHashMap())

    /** The change tree the collaboration tools build for a code review (GitHub and GitLab alike). */
    fun isReviewChangesTree(component: Component): Boolean =
        component is ChangesTree && component.javaClass.name.startsWith(REVIEW_TREE_FACTORY)

    fun watch(tree: ChangesTree) {
        (tree.getClientProperty(INSTALLED) as? JComponent)?.let { if (DecorationOwner.owns(it)) return }
        install(tree, tree.project.service<CurrentReviewFile>())
    }

    fun install(tree: JTree, currentFile: CurrentReviewFile) {
        (tree.getClientProperty(INSTALLED) as? JComponent)?.let { if (DecorationOwner.owns(it)) uninstall(tree) else tree.remove(it) } // left by a version unloaded without restart
        val overlay = CurrentFileOverlay(tree, currentFile)
        tree.putClientProperty(INSTALLED, overlay)
        tree.add(overlay)
        tree.addComponentListener(overlay.resizeListener)
        synchronized(installed) { installed.add(tree) }
        currentFile.track(tree)
        if (tree is ChangesTree) {
            overlay.marksJob = followReviewData(tree, overlay)
            overlay.handlers = TreeOpensFile.install(tree)
            overlay.cycleInstalled = ViewedStateCycle.install(tree, overlay)
        }
        tree.repaint()
    }

    /**
     * Runs only while the tree is on screen and dies with it, which is also when the change list model is
     * published to the tree's data context. A tree for a single commit shows no PR-wide marks, like the platform.
     */
    private fun followReviewData(tree: ChangesTree, overlay: CurrentFileOverlay): Job =
        tree.launchOnShow("BetterCodeReview.treeMarks") {
            try {
                followReviewDataOnShow(tree, overlay)
            } catch (e: LinkageError) {
                LOG.warn("Tree marks stopped: GitHub plugin API changed", e)
            }
        }

    private suspend fun kotlinx.coroutines.CoroutineScope.followReviewDataOnShow(tree: ChangesTree, overlay: CurrentFileOverlay) {
        // Each distinct outcome is logged at INFO once per tree, so the IDE log tells whether the marks run; repeats at DEBUG.
        fun log(message: String) = if (overlay.loggedOutcomes.add(message)) LOG.info(message) else LOG.debug(message)
        val changeList = changeListModelOf(tree)
        if (changeList == null) {
            log("Tree marks off: no change list model in the tree's data context")
            return
        }
        if (!ReviewDataSource.isOnLatest(changeList)) {
            log("Tree marks off: the tree shows a single commit")
            return
        }
        val source = ReviewDataSource.from(changeList)
        if (source == null) {
            log("Tree marks off: the change list model has no reachable data provider (details at DEBUG level)")
            return
        }
        log("Tree marks following pull request data under ${source.repositoryRootPath}")
        overlay.repositoryRootPath = source.repositoryRootPath
        val number = pullRequestNumberOf(tree)
        if (number == null) log("Viewed checkbox cycle off: no pull request id in the tree's data context") // GitHub's state still gets its dash
        overlay.pullRequestKey = number?.let { "${source.repositoryRootPath}#$it" }
        // Each collector fails on its own: a GitHub plugin change breaking one mark must not take the other down.
        launch {
            collectMarks("pending comments") {
                source.pendingCommentPaths.collect {
                    if (LOG.isDebugEnabled) LOG.debug("Pending comments in ${it.distinct().size} file(s)")
                    overlay.pendingMarks = PendingMarks.of(source.repositoryRootPath, it)
                }
            }
        }
        launch {
            collectMarks("line totals") {
                // The plugin fetches the commits only when a diff opens; the counts read them from git. A fetch that
                // hangs (a credentials prompt, a slow remote) must not hide the total for commits that are already local.
                val fetched = kotlinx.coroutines.withTimeoutOrNull(FETCH_WAIT_MS) { source.ensureRevisionsFetched() } != null
                log(if (fetched) "Line totals: the pull request's commits are local" else "Line totals: fetching the pull request's commits takes over ${FETCH_WAIT_MS / 1000} s, counting anyway")
                overlay.revisionsFetched = true
            }
        }
        launch {
            collectMarks("viewed-then-changed files") {
                source.viewedStateByPath.collect {
                    if (LOG.isDebugEnabled) LOG.debug("Viewed states loaded for ${it.size} file(s), ${ReviewDataSource.dismissedPaths(it).size} viewed-then-changed")
                    overlay.viewedStates = it
                }
            }
        }
    }

    private suspend fun collectMarks(what: String, collect: suspend () -> Unit) {
        try {
            collect()
        } catch (e: kotlinx.coroutines.CancellationException) {
            kotlin.coroutines.coroutineContext.ensureActive() // our own cancellation propagates; a foreign one ends the marks quietly
            LOG.info("Marks for $what stopped: the pull request data was cancelled")
        } catch (e: Throwable) {
            LOG.warn("Marks for $what stopped: GitHub plugin API changed", e)
        }
    }

    fun uninstall(tree: JTree) {
        val overlay = tree.getClientProperty(INSTALLED) as? JComponent
        tree.putClientProperty(INSTALLED, null)
        if (overlay != null) {
            (overlay as? CurrentFileOverlay)?.let {
                it.stopPulsing()
                it.marksJob?.cancel()
                it.handlers?.let { handlers -> (tree as? ChangesTree)?.let { changesTree -> TreeOpensFile.uninstall(changesTree, handlers) } }
                if (it.cycleInstalled) (tree as? ChangesTree)?.let(ViewedStateCycle::uninstall)
                it.detachFlags()
                tree.removeComponentListener(it.resizeListener)
                it.currentFile.untrack(tree)
            }
            tree.remove(overlay)
        }
        synchronized(installed) { installed.remove(tree) }
        tree.repaint()
    }

    fun repaintAll() {
        synchronized(installed) { installed.toList() }.forEach { it.repaint() }
    }

    /** Trees in hidden tabs are not reachable from any window; the registry remembers them for unload. */
    fun uninstallAll() {
        synchronized(installed) { installed.toList() }.forEach { tree -> runCatching { uninstall(tree) } }
    }

    fun paintedRowBounds(tree: JTree, path: TreePath): Rectangle? = RowSlots.paintedRowBounds(tree, path)

    /** The file a tree node stands for: changed files by their path after the change (before it, when deleted), folders by their path. */
    fun pathOf(userObject: Any?): FilePath? = when (userObject) {
        is RefComparisonChange -> userObject.filePathAfter ?: userObject.filePathBefore
        is FilePath -> userObject
        else -> null
    }

    class CurrentFileOverlay(private val tree: JTree, val currentFile: CurrentReviewFile) : JComponent() {
        val resizeListener = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = fitTree()
        }

        /** Set from the EDT by [ReviewTreeMarksService]; repaints the whole tree area since marks can be anywhere. */
        var pendingMarks: PendingMarks = PendingMarks.NONE
            set(value) {
                field = value
                repaint()
            }

        var marksJob: Job? = null
        var handlers: TreeOpensFile.Handlers? = null
        val loggedOutcomes = HashSet<String>()

        /** Files whose "viewed" tick GitHub dismissed after new changes: their slot gets a dash. */
        var dismissed: PendingMarks = PendingMarks.NONE
            set(value) {
                field = value
                repaint()
            }

        var repositoryRootPath: String? = null
        var pullRequestKey: String? = null
        var cycleInstalled = false

        private val flagsListener: () -> Unit = { repaint() }
        private var flagsAttached = false
        private val flags: ReviewFlags by lazy { ((tree as? ChangesTree)?.project ?: currentFile.project).service<ReviewFlags>() }

        /** The flag listener lives only while the overlay is in a hierarchy, so a closed tab does not keep the tree alive. */
        override fun addNotify() {
            super.addNotify()
            if (!flagsAttached) {
                flags.addListener(flagsListener)
                flagsAttached = true
            }
        }

        override fun removeNotify() {
            mark.stop()
            detachFlags()
            super.removeNotify()
        }

        /** What a click just made the file, before GitHub confirms it; no flag is cleared on this path. */
        fun assume(relativePath: String, state: GHPullRequestFileViewedState) {
            viewedStatesField = viewedStatesField + (relativePath to state)
            refreshDismissed()
            repaint()
        }

        private var viewedStatesField: Map<String, GHPullRequestFileViewedState> = emptyMap()

        private fun refreshDismissed() {
            dismissed = repositoryRootPath?.let { PendingMarks.of(it, ReviewDataSource.dismissedPaths(viewedStatesField)) } ?: PendingMarks.NONE
        }

        /** GitHub's viewed state per repository-relative path; a transition into "viewed" takes the reviewer's flag off. */
        var viewedStates: Map<String, GHPullRequestFileViewedState>
            get() = viewedStatesField
            set(value) {
                val before = viewedStatesField
                viewedStatesField = value
                if (pullRequestKey != null) {
                    for ((path, state) in value) {
                        if (state == GHPullRequestFileViewedState.VIEWED && before[path] != state && isFlagged(path)) setFlag(path, false)
                    }
                }
                refreshDismissed()
            }

        fun relativePath(path: FilePath): String? = repositoryRootPath?.let { FileUtil.getRelativePath(it, path.path, '/') }

        fun viewedStateOf(relativePath: String): GHPullRequestFileViewedState? = viewedStates[relativePath]

        fun isFlagged(relativePath: String): Boolean {
            val key = pullRequestKey ?: return false
            return flags.isFlagged(key, relativePath)
        }

        fun setFlag(relativePath: String, on: Boolean) {
            val key = pullRequestKey ?: return
            if (on) flags.set(key, relativePath) else flags.clear(key, relativePath)
            repaint()
        }

        fun detachFlags() {
            if (flagsAttached) {
                flags.removeListener(flagsListener)
                flagsAttached = false
            }
        }

        private val mark = CurrentFileMark(this)

        val isPulsing: Boolean get() = mark.isPulsing

        /** The current file's row as of the last paint, for tests. */
        internal val markedRow: Rectangle? get() = mark.markedRow

        init {
            isOpaque = false
            isFocusable = false
            DecorationOwner.mark(this)
            fitTree()
        }

        fun stopPulsing() = mark.stop()

        private fun fitTree() {
            bounds = Rectangle(0, 0, tree.width, tree.height)
        }

        /** Never a mouse or tooltip target: clicks go to the tree underneath. */
        override fun contains(x: Int, y: Int): Boolean = false

        override fun paintComponent(g: Graphics) {
            val current = currentFile.path
            val visible = tree.visibleRect
            var marked: Rectangle? = null
            val clip = g.clipBounds
            for (row in visibleRows(visible)) {
                val path = tree.getPathForRow(row) ?: continue
                val group = topGroupOf(path)
                if (group != null) {
                    tree.getRowBounds(row)?.takeIf { clip == null || clip.intersects(Rectangle(visible.x, it.y, visible.width, it.height)) }
                        ?.let { paintLineTotal(g, group, row, it) }
                    continue
                }
                val nodePath = pathOf(TreeUtil.getLastUserObject(path)) ?: continue
                val bounds = tree.getRowBounds(row) ?: continue
                val rowRect = Rectangle(visible.x, bounds.y, visible.width, bounds.height)
                val isCurrent = current != null && !nodePath.isDirectory && nodePath == current
                if (isCurrent) marked = rowRect // remembered even outside the clip, so a partial repaint keeps the pulse alive
                if (clip != null && !clip.intersects(rowRect)) continue
                val pending = pendingCountFor(path, nodePath)
                if (isCurrent) mark.paint(g, bounds, visible)
                val leaf = tree.model.isLeaf(path.lastPathComponent)
                if (pending > 0) paintPending(g, bounds, visible, pending, isCurrent, leaf)
                val flagged = ViewedStateCycle.isEnabled && relativePath(nodePath)?.let(::isFlagged) == true
                if (leaf && (dismissed.countFor(nodePath) > 0 || flagged)) paintDismissedDash(g, path, row, isCurrent)
            }
            mark.painted(marked)
        }

        /** Files count their own comments; a collapsed folder sums the files the tree shows beneath it, an expanded one shows nothing. */
        private fun pendingCountFor(path: javax.swing.tree.TreePath, nodePath: FilePath): Int {
            if (pendingMarks.isEmpty) return 0
            val node = path.lastPathComponent
            if (tree.model.isLeaf(node)) return pendingMarks.countFor(nodePath)
            if (tree.isExpanded(path)) return 0
            return when (node) {
                is ChangesBrowserNode<*> -> pendingMarks.sumFor(node.traverseObjectsUnder().filter(RefComparisonChange::class.java).mapNotNull { pathOf(it) })
                else -> pendingMarks.sumUnder(nodePath)
            }
        }

        /**
         * GitHub drops the "viewed" tick of a file changed after it was viewed, and the bundled plugin then shows
         * the same unread dot as for a file never looked at (the checkbox only appears under the mouse). The dash
         * over that slot means "viewed before, read it again": GitHub's dismissal or the reviewer's own flag.
         */
        private fun paintDismissedDash(g: Graphics, path: TreePath, row: Int, isCurrent: Boolean) {
            val slot = RowSlots.stateSlot(tree, path, row) ?: return
            RowSlots.paintDash(g, tree, row, slot, isCurrent)
        }

        private var pillFont: java.awt.Font = JBFont.small().asBold()

        override fun updateUI() {
            super.updateUI()
            pillFont = JBFont.small().asBold()
        }

        /**
         * An amber bar beside the purple one (a lighter one on a collapsed folder) and, on file rows, a round badge
         * with the number of pending comments. The badge sits at a fixed position in the indentation strip and is
         * drawn only when it fits there, so it never covers a folder's expand handle or a file's icon.
         */
        private fun paintPending(g: Graphics, row: Rectangle, visible: Rectangle, count: Int, isCurrent: Boolean, leaf: Boolean) {
            val g2 = g.create() as Graphics2D
            try {
                com.intellij.util.ui.GraphicsUtil.setupAAPainting(g2)
                com.intellij.ide.ui.UISettings.setupAntialiasing(g2)
                val bar = JBUI.scale(BAR_WIDTH)
                g2.color = if (leaf) PENDING else Color(PENDING.red, PENDING.green, PENDING.blue, FOLDER_BAR_ALPHA)
                g2.fillRect(visible.x + if (isCurrent) bar else 0, row.y, bar, row.height)
                if (!leaf) return

                g2.font = pillFont
                val metrics = g2.fontMetrics
                val text = count.toString()
                val pillHeight = (row.height - JBUI.scale(6)).coerceIn(minOf(metrics.height, row.height - 2), row.height - 2)
                val pillWidth = maxOf(metrics.stringWidth(text) + JBUI.scale(8), pillHeight)
                val pillX = visible.x + 2 * bar + JBUI.scale(3)
                if (pillX + pillWidth + JBUI.scale(2) > row.x) return // no room left of the file icon
                val pillY = row.y + (row.height - pillHeight) / 2
                g2.color = PENDING
                RectanglePainter2D.FILL.paint(g2, pillX.toDouble(), pillY.toDouble(), pillWidth.toDouble(), pillHeight.toDouble(), pillHeight.toDouble())
                g2.color = PENDING_TEXT
                val textX = pillX + (pillWidth - metrics.stringWidth(text)) / 2
                val textY = pillY + (pillHeight + metrics.ascent - metrics.descent) / 2
                g2.drawString(text, textX, textY)
            } finally {
                g2.dispose()
            }
        }

        /**
         * The rows that carry the line total: the tree's top level of grouping (a repository, a module or a folder),
         * which already shows the file count. Files listed flat at the top get none.
         */
        private fun topGroupOf(path: TreePath): ChangesBrowserNode<*>? {
            val node = path.lastPathComponent as? ChangesBrowserNode<*> ?: return null
            if (path.pathCount != (if (tree.isRootVisible) 1 else 2) || tree.model.isLeaf(node)) return null
            return node
        }

        /** Set once the pull request's commits are in the local repository; until then no line total is counted. */
        var revisionsFetched = false
            set(value) {
                field = value
                repaint()
            }

        private val vcsChanges = HashMap<RefComparisonChange, Change>()

        /** Lines added and removed in the pull request's files under [node]; null until all of them are counted. */
        internal fun lineTotalOf(node: ChangesBrowserNode<*>): ChangeLineStats.Lines? {
            if (!revisionsFetched) return null
            if (vcsChanges.size > MAX_CACHED_CHANGES) vcsChanges.clear()
            val changes = node.traverseObjectsUnder().filter(RefComparisonChange::class.java).map { vcsChanges.getOrPut(it) { it.toVcsChange() } }.toList()
            if (loggedOutcomes.add("line total of ${changes.size}")) LOG.info("Line totals: the top row lists ${changes.size} pull request file(s)")
            if (changes.isEmpty()) return null
            return currentFile.project.service<ChangeLineStats>().totalFor(changes, committed = true)
        }

        private fun RefComparisonChange.toVcsChange() = Change(
            filePathBefore?.let { gitRevision(it, revisionNumberBefore) },
            filePathAfter?.let { gitRevision(it, revisionNumberAfter) },
        )

        private fun gitRevision(path: FilePath, number: VcsRevisionNumber) =
            GitContentRevision.createRevision(path, number as? GitRevisionNumber ?: GitRevisionNumber(number.asString()), currentFile.project)

        /** "+added −removed" right after the top row's text, which ends with the file count (and the branch or the module's path). */
        private fun paintLineTotal(g: Graphics, node: ChangesBrowserNode<*>, row: Int, bounds: Rectangle) {
            val lines = lineTotalOf(node) ?: return
            if (lines.added == 0 && lines.deleted == 0) return
            val g2 = g.create() as Graphics2D
            try {
                com.intellij.ide.ui.UISettings.setupAntialiasing(g2)
                g2.font = tree.font
                val metrics = g2.fontMetrics
                val y = bounds.y + (bounds.height - metrics.height) / 2 + metrics.ascent
                // On the focused selection the row's own foreground keeps the text readable.
                val onSelection = tree.isRowSelected(row) && tree.hasFocus()
                var x = bounds.x + bounds.width + metrics.stringWidth("  ")
                for ((text, color) in listOf("+${lines.added}" to ChangeLineStats.ADDED, " −${lines.deleted}" to ChangeLineStats.DELETED)) {
                    g2.color = if (onSelection) UIUtil.getTreeSelectionForeground(true) else color
                    g2.drawString(text, x, y)
                    x += metrics.stringWidth(text)
                }
            } finally {
                g2.dispose()
            }
        }

        private fun visibleRows(visible: Rectangle): IntRange {
            if (tree.rowCount == 0 || visible.isEmpty) return IntRange.EMPTY
            val first = tree.getClosestRowForLocation(0, visible.y).coerceAtLeast(0)
            val last = tree.getClosestRowForLocation(0, visible.y + visible.height - 1).coerceAtLeast(first)
            return first..last
        }
    }
}
