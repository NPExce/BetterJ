package pl.adrros.betterpullrequests.review.files

import com.intellij.collaboration.util.RefComparisonChange
import com.intellij.openapi.components.service
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.ui.ExperimentalUI
import com.intellij.ui.JBColor
import com.intellij.ui.paint.RectanglePainter2D
import com.intellij.ui.tree.ui.DefaultTreeUI
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import pl.adrros.betterpullrequests.review.DecorationOwner
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
object ReviewChangeTrees {
    const val INSTALLED = "BetterPullRequests.currentFileHighlight"
    private const val REVIEW_TREE_FACTORY = "com.intellij.collaboration.ui.codereview.changes.CodeReviewChangeListComponentFactory"
    private const val BAR_WIDTH = 4
    private const val TINT_ALPHA_MIN = 90
    private const val TINT_ALPHA_MAX = 160
    private const val PULSE_PERIOD_MS = 1600
    private const val PULSE_TICK_MS = 40

    /** Saturated purple, overridable by themes; the tint is drawn with alpha, so text stays readable underneath. */
    val HIGHLIGHT: Color = JBColor.namedColor("BetterPullRequests.CurrentFile.background", JBColor(Color(0x7C, 0x3A, 0xED), Color(0xA7, 0x8B, 0xFA)))

    private val installed = ContainerUtil.createWeakSet<JTree>()

    /** The change tree the collaboration tools build for a code review (GitHub and GitLab alike). */
    fun isReviewChangesTree(component: Component): Boolean =
        component is ChangesTree && component.javaClass.name.startsWith(REVIEW_TREE_FACTORY)

    fun watch(tree: ChangesTree) {
        (tree.getClientProperty(INSTALLED) as? JComponent)?.let { if (DecorationOwner.owns(it)) return }
        install(tree, tree.project.service<CurrentReviewFile>())
    }

    fun install(tree: JTree, currentFile: CurrentReviewFile) {
        (tree.getClientProperty(INSTALLED) as? JComponent)?.let { tree.remove(it) } // left by a version unloaded without restart
        val overlay = CurrentFileOverlay(tree, currentFile)
        tree.putClientProperty(INSTALLED, overlay)
        tree.add(overlay)
        tree.addComponentListener(overlay.resizeListener)
        synchronized(installed) { installed.add(tree) }
        currentFile.track(tree)
        tree.repaint()
    }

    fun uninstall(tree: JTree) {
        val overlay = tree.getClientProperty(INSTALLED) as? JComponent
        tree.putClientProperty(INSTALLED, null)
        if (overlay != null) {
            (overlay as? CurrentFileOverlay)?.let {
                it.stopPulsing()
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

    /** The file a change-tree node stands for; directory nodes carry a directory path and never match a file. */
    fun pathOf(userObject: Any?): FilePath? = when (userObject) {
        is RefComparisonChange -> userObject.filePathAfter ?: userObject.filePathBefore
        is FilePath -> userObject
        else -> null
    }

    class CurrentFileOverlay(private val tree: JTree, val currentFile: CurrentReviewFile) : JComponent() {
        val resizeListener = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = fitTree()
        }

        private var phase = 0
        private var lastMark: Rectangle? = null
        private val pulse = Timer(PULSE_TICK_MS) {
            phase = (phase + PULSE_TICK_MS) % PULSE_PERIOD_MS
            lastMark?.let { repaint(it) } ?: repaint()
        }

        val isPulsing: Boolean get() = pulse.isRunning

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
            if (current != null) {
                for (row in visibleRows(visible)) {
                    val path = tree.getPathForRow(row) ?: continue
                    if (pathOf(TreeUtil.getLastUserObject(path)) != current) continue
                    val bounds = tree.getRowBounds(row) ?: continue
                    paintMark(g, bounds, visible)
                    marked = Rectangle(visible.x, bounds.y, visible.width, bounds.height)
                }
            }
            lastMark = marked
            if (marked != null && isShowing) pulse.start() else pulse.stop()
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
