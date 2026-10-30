package pl.adrros.bettercodereview.commit

import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.impl.ProjectViewTree
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.LafIconLookup
import com.intellij.util.ui.tree.TreeUtil
import pl.adrros.bettercodereview.review.DecorationOwner
import pl.adrros.bettercodereview.review.files.CheckboxClicks
import pl.adrros.bettercodereview.review.files.RowSlots
import java.awt.Graphics
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.util.Collections
import java.util.WeakHashMap
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.plaf.basic.BasicTreeUI
import javax.swing.tree.TreePath

/**
 * The Commit panel's checkboxes, repeated in the project tree in front of every changed file's icon, where a folder
 * has its expand arrow (one column further left on a file the tree can expand, such as a file shown with its
 * members). They show and change the same inclusion, with the same cycle and the same dash for a file that moved
 * on since it was ticked by hand. The Commit panel stays the owner: without its checkboxes none show.
 */
object ProjectTreeTicks {
    const val INSTALLED = "BetterCodeReview.projectTreeTicks"
    private const val BOX = 16

    private val installed: MutableSet<JTree> = Collections.newSetFromMap(WeakHashMap())

    /** Replaceable in tests: the Commit panel deciding the checkboxes of a project's files. */
    var commitOf: (Project) -> CommitInclusionMarks.InclusionOverlay? = CommitInclusionMarks::overlayFor

    fun watch(tree: ProjectViewTree) {
        (tree.getClientProperty(INSTALLED) as? JComponent)?.let { if (DecorationOwner.owns(it)) return }
        install(tree)
    }

    fun install(tree: JTree): TicksOverlay {
        (tree.getClientProperty(INSTALLED) as? JComponent)?.let { if (DecorationOwner.owns(it)) uninstall(tree) else tree.remove(it) }
        val overlay = TicksOverlay(tree)
        tree.putClientProperty(INSTALLED, overlay)
        tree.add(overlay)
        tree.addComponentListener(overlay.resizeListener)
        synchronized(installed) { installed.add(tree) }
        CheckboxClicks.register(tree) { point -> overlay.decide(point) }
        tree.repaint()
        return overlay
    }

    fun uninstall(tree: JTree) {
        val overlay = tree.getClientProperty(INSTALLED) as? TicksOverlay
        tree.putClientProperty(INSTALLED, null)
        CheckboxClicks.unregister(tree)
        if (overlay != null) {
            tree.removeComponentListener(overlay.resizeListener)
            tree.remove(overlay)
        }
        synchronized(installed) { installed.remove(tree) }
        tree.repaint()
    }

    fun uninstallAll() {
        synchronized(installed) { installed.toList() }.forEach { tree -> runCatching { uninstall(tree) } }
    }

    fun repaintAll() {
        synchronized(installed) { installed.toList() }.forEach { if (it.isShowing) it.repaint() }
    }

    private fun iconOf(tick: CommitInclusionMarks.Tick): Icon = when (tick) {
        CommitInclusionMarks.Tick.NONE -> LafIconLookup.getIcon("checkBox", false, false, true)
        CommitInclusionMarks.Tick.TICKED, CommitInclusionMarks.Tick.MARKED -> LafIconLookup.getIcon("checkBox", true, false, true)
        CommitInclusionMarks.Tick.PARTIAL -> LafIconLookup.getIcon("checkBoxIndeterminate", true, false, true)
    }

    class TicksOverlay(private val tree: JTree) : JComponent() {
        val resizeListener = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = fitTree()
        }

        init {
            isOpaque = false
            isFocusable = false
            DecorationOwner.mark(this)
            fitTree()
        }

        private fun fitTree() {
            bounds = Rectangle(0, 0, tree.width, tree.height)
        }

        override fun contains(x: Int, y: Int): Boolean = false

        /**
         * The file a row stands for, when the row is the file's outermost node: a file or class under a folder or
         * package, not a class or member shown inside a file (whose parent stands for the same file).
         */
        internal fun fileOf(path: TreePath): Pair<Project, VirtualFile>? {
            val node = TreeUtil.getLastUserObject(path) as? ProjectViewNode<*> ?: return null
            val file = node.virtualFile?.takeUnless { it.isDirectory } ?: return null
            val parent = path.parentPath?.let { TreeUtil.getLastUserObject(it) } as? ProjectViewNode<*> ?: return null
            if (parent.virtualFile == file || !parent.contains(file)) return null
            return (node.project ?: return null) to file
        }

        /** The checkbox's box in tree coordinates, centred on the expand arrow's axis; a node with an arrow of its own gets the column before it. */
        internal fun boxOf(row: Int, path: TreePath): Rectangle? {
            val bounds = tree.getRowBounds(row) ?: return null
            val ui = tree.ui as? BasicTreeUI
            val right = ui?.rightChildIndent ?: JBUI.scale(11)
            val levels = if (tree.model.isLeaf(path.lastPathComponent)) 0 else 1
            val centre = bounds.x - right - levels * (right + (ui?.leftChildIndent ?: JBUI.scale(7)))
            val side = JBUI.scale(BOX)
            if (centre - side / 2 < 0) return null
            return Rectangle(centre - side / 2, bounds.y + (bounds.height - side) / 2, side, side)
        }

        /** Where a press counts as a click on the checkbox: the row's full height, from just before the box up to the icon (or up to the arrow of an expandable file). */
        internal fun hitOf(row: Int, path: TreePath): Rectangle? {
            val box = boxOf(row, path) ?: return null
            val bounds = tree.getRowBounds(row) ?: return null
            val left = (box.x - JBUI.scale(3)).coerceAtLeast(0)
            val right = if (tree.model.isLeaf(path.lastPathComponent)) bounds.x else box.x + box.width + JBUI.scale(1)
            return Rectangle(left, bounds.y, right - left, bounds.height)
        }

        override fun paintComponent(g: Graphics) {
            val visible = tree.visibleRect
            if (tree.rowCount == 0 || visible.isEmpty) return
            val clip = g.clipBounds
            // One Commit panel per project, synced once per paint.
            val commits = HashMap<Project, CommitInclusionMarks.InclusionOverlay?>()
            val first = tree.getClosestRowForLocation(visible.x, visible.y).coerceAtLeast(0)
            val last = tree.getClosestRowForLocation(visible.x, visible.y + visible.height - 1)
            for (row in first..last) {
                val path = tree.getPathForRow(row) ?: continue
                val box = boxOf(row, path) ?: continue
                if (clip != null && !clip.intersects(box)) continue
                val (project, file) = fileOf(path) ?: continue
                val commit = commits.getOrPut(project) { commitOf(project)?.also { it.syncInclusion() } } ?: continue
                val tick = commit.tickOf(file) ?: continue
                val icon = iconOf(tick)
                icon.paintIcon(this, g, box.x + (box.width - icon.iconWidth) / 2, box.y + (box.height - icon.iconHeight) / 2)
                if (tick == CommitInclusionMarks.Tick.MARKED) RowSlots.paintDash(g, tree, row, RowSlots.Slot(box, true, true), false)
            }
        }

        /** A press on a checkbox is ours alone; the row still gets selected by the press, as in the Commit panel. */
        fun decide(point: Point): CheckboxClicks.Decision? {
            val row = tree.getClosestRowForLocation(point.x, point.y).takeIf { it >= 0 } ?: return null
            val path = tree.getPathForRow(row) ?: return null
            if (hitOf(row, path)?.contains(point) != true) return null
            val (project, file) = fileOf(path) ?: return null
            val commit = commitOf(project) ?: return null
            commit.syncInclusion()
            if (commit.tickOf(file) == null) return null
            return CheckboxClicks.Decision(consume = true) { commit.clickFromProjectTree(file) }
        }
    }
}
