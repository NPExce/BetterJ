package pl.adrros.bettercodereview.review.files

import com.intellij.collaboration.util.RefComparisonChange
import com.intellij.ide.DataManager
import com.intellij.openapi.components.service
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.ui.ExperimentalUI
import com.intellij.ui.JBColor
import com.intellij.ui.paint.RectanglePainter2D
import com.intellij.ui.tree.ui.DefaultTreeUI
import com.intellij.util.ui.JBUI
import com.intellij.openapi.diagnostic.logger
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
import javax.swing.Timer
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

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
    private const val BAR_WIDTH = 4
    private const val TINT_ALPHA_MIN = 90
    private const val TINT_ALPHA_MAX = 160
    private const val PULSE_PERIOD_MS = 1600
    private const val PULSE_TICK_MS = 40
    private const val FOLDER_BAR_ALPHA = 128

    /** Saturated purple, overridable by themes; the tint is drawn with alpha, so text stays readable underneath. */
    val HIGHLIGHT: Color = JBColor.namedColor("BetterCodeReview.CurrentFile.background", JBColor(Color(0x7C, 0x3A, 0xED), Color(0xA7, 0x8B, 0xFA)))

    /** Amber for pending comments: distinct from the purple mark and from the platform's blue selection. */
    val PENDING: Color = JBColor.namedColor("BetterCodeReview.PendingComments.foreground", JBColor(Color(0xE0, 0x8A, 0x00), Color(0xFB, 0xBF, 0x24)))

    val DISMISSED_DASH: Color get() = RowSlots.DASH

    fun isStateSlot(component: Component): Boolean = RowSlots.isStateSlot(component)

    val DISMISSED_DASH_ON_CURRENT: Color get() = RowSlots.DASH_ON_CURRENT

    /** Dark digits read on amber in both themes (white would fall below 3:1). */
    val PENDING_TEXT: Color = JBColor.namedColor("BetterCodeReview.PendingComments.text", JBColor(Color(0x1F, 0x1F, 0x1F), Color(0x1F, 0x1F, 0x1F)))

    /** The pull request behind the tree, as a key for the reviewer's own flags; headless tests replace it. */
    var pullRequestKeyOf: (JTree) -> String? = { tree ->
        DataManager.getInstance().getDataContext(tree).getData(org.jetbrains.plugins.github.pullrequest.action.GHPRActionKeys.PULL_REQUEST_ID)?.let { "pr-${it.number}" }
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
            ViewedStateCycle.install(tree, overlay)
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
        overlay.pullRequestKey = pullRequestKeyOf(tree) ?: source.repositoryRootPath
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
                (tree as? ChangesTree)?.let(ViewedStateCycle::uninstall)
                it.detachFlags()
                tree.removeComponentListener(it.resizeListener)
                it.currentFile.untrack(tree)
            }
            tree.remove(overlay)
        }
        synchronized(installed) { installed.remove(tree) }
        tree.repaint()
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

        private val flagsListener: () -> Unit = { repaint() }
        private var flagsAttached = false
        private val flags: ReviewFlags by lazy {
            flagsAttached = true
            ((tree as? ChangesTree)?.project ?: currentFile.project).service<ReviewFlags>().also { it.addListener(flagsListener) }
        }

        /** GitHub's viewed state per repository-relative path; a transition into "viewed" takes the reviewer's flag off. */
        var viewedStates: Map<String, GHPullRequestFileViewedState> = emptyMap()
            set(value) {
                val before = field
                field = value
                if (pullRequestKey != null) {
                    for ((path, state) in value) {
                        if (state == GHPullRequestFileViewedState.VIEWED && before[path] != state && isFlagged(path)) setFlag(path, false)
                    }
                }
                dismissed = repositoryRootPath?.let { PendingMarks.of(it, ReviewDataSource.dismissedPaths(value)) } ?: PendingMarks.NONE
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
            if (flagsAttached) flags.removeListener(flagsListener)
        }

        private var phase = 0
        private var lastMark: Rectangle? = null
        private val pulse = Timer(PULSE_TICK_MS) {
            phase = (phase + PULSE_TICK_MS) % PULSE_PERIOD_MS
            lastMark?.let { repaint(it) } ?: repaint()
        }

        val isPulsing: Boolean get() = pulse.isRunning

        /** The current file's row as of the last paint, for tests. */
        internal val markedRow: Rectangle? get() = lastMark

        init {
            isOpaque = false
            isFocusable = false
            DecorationOwner.mark(this)
            fitTree()
        }

        /** Alpha of the tint at this moment: a sine wave between the two limits. */
        private fun tintAlpha(): Int {
            val wave = (sin(2 * PI * phase / PULSE_PERIOD_MS) + 1) / 2
            return (TINT_ALPHA_MIN + wave * (TINT_ALPHA_MAX - TINT_ALPHA_MIN)).roundToInt()
        }

        override fun removeNotify() {
            pulse.stop()
            super.removeNotify()
        }

        fun stopPulsing() = pulse.stop()

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
                val nodePath = pathOf(TreeUtil.getLastUserObject(path)) ?: continue
                val bounds = tree.getRowBounds(row) ?: continue
                val rowRect = Rectangle(visible.x, bounds.y, visible.width, bounds.height)
                val isCurrent = current != null && !nodePath.isDirectory && nodePath == current
                if (isCurrent) marked = rowRect // remembered even outside the clip, so a partial repaint keeps the pulse alive
                if (clip != null && !clip.intersects(rowRect)) continue
                val pending = pendingCountFor(path, nodePath)
                if (isCurrent) paintMark(g, bounds, visible)
                val leaf = tree.model.isLeaf(path.lastPathComponent)
                if (pending > 0) paintPending(g, bounds, visible, pending, isCurrent, leaf)
                if (leaf && (dismissed.countFor(nodePath) > 0 || relativePath(nodePath)?.let(::isFlagged) == true)) paintDismissedDash(g, path, row, isCurrent)
            }
            lastMark = marked
            if (marked != null && isShowing) pulse.start() else pulse.stop()
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

        private fun visibleRows(visible: Rectangle): IntRange {
            if (tree.rowCount == 0 || visible.isEmpty) return IntRange.EMPTY
            val first = tree.getClosestRowForLocation(0, visible.y).coerceAtLeast(0)
            val last = tree.getClosestRowForLocation(0, visible.y + visible.height - 1).coerceAtLeast(first)
            return first..last
        }

        private fun paintMark(g: Graphics, row: Rectangle, visible: Rectangle) {
            val g2 = g.create() as Graphics2D
            try {
                val newUi = ExperimentalUI.isNewUI()
                val inset = if (newUi) JBUI.scale(DefaultTreeUI.HORIZONTAL_SELECTION_OFFSET) else 0
                val arc = if (newUi) JBUI.CurrentTheme.Tree.ARC.get().toDouble() else 0.0
                g2.color = Color(HIGHLIGHT.red, HIGHLIGHT.green, HIGHLIGHT.blue, tintAlpha())
                RectanglePainter2D.FILL.paint(
                    g2, (visible.x + inset).toDouble(), row.y.toDouble(), (visible.width - 2 * inset).toDouble(), row.height.toDouble(), arc,
                )
                g2.color = HIGHLIGHT
                g2.fillRect(visible.x, row.y, JBUI.scale(BAR_WIDTH), row.height)
            } finally {
                g2.dispose()
            }
        }
    }
}
