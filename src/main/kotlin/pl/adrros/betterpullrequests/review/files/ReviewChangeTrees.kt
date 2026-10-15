package pl.adrros.betterpullrequests.review.files

import com.intellij.collaboration.ui.codereview.changes.CodeReviewChangeListComponentFactory
import com.intellij.collaboration.util.RefComparisonChange
import com.intellij.ide.DataManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
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
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.plaf.TreeUI

/**
 * Marks the file open in the editor inside the pull request's change tree, so the reader always knows which
 * changed file they are in and can tick it as viewed. The platform paints the tree's rows itself (selection,
 * hover, file colours); a thin UI subclass adds a translucent tint and a bar at the left edge on top of them,
 * so the mark stays visible whatever else the row shows. The renderer is untouched.
 */
object ReviewChangeTrees {
    const val INSTALLED = "BetterPullRequests.currentFileHighlight"
    private const val REVIEW_TREE_FACTORY = "com.intellij.collaboration.ui.codereview.changes.CodeReviewChangeListComponentFactory"
    private const val BAR_WIDTH = 3
    private const val TINT_ALPHA = 70

    /** Purple, overridable by themes; the tint is drawn with alpha, so it reads over selection and file colours too. */
    val HIGHLIGHT: Color = JBColor.namedColor("BetterPullRequests.CurrentFile.background", JBColor(Color(0x8B, 0x5C, 0xF6), Color(0xA7, 0x8B, 0xFA)))

    private val installed = ContainerUtil.createWeakSet<ChangesTree>()

    /**
     * The change tree the collaboration tools build for a code review (GitHub and GitLab alike): recognised by
     * the data key it publishes, with the factory's class name as a fallback.
     */
    fun isReviewChangesTree(component: Component): Boolean {
        if (component !is ChangesTree) return false
        if (component.javaClass.name.startsWith(REVIEW_TREE_FACTORY)) return true
        return runCatching { DataManager.getInstance().getDataContext(component).getData(CodeReviewChangeListComponentFactory.SELECTED_CHANGES) }.getOrNull() != null
    }

    fun watch(tree: ChangesTree) {
        (tree.getClientProperty(INSTALLED) as? JComponent)?.let { if (DecorationOwner.owns(it)) return }
        install(tree)
    }

    fun install(tree: ChangesTree) {
        val installation = Installation(tree)
        DecorationOwner.mark(installation)
        tree.putClientProperty(INSTALLED, installation)
        synchronized(installed) { installed.add(tree) }
        tree.project.service<CurrentReviewFile>().track(tree)
        applyUi(tree)
        tree.addPropertyChangeListener("UI", installation.listener)
    }

    fun uninstall(tree: ChangesTree) {
        (tree.getClientProperty(INSTALLED) as? Installation)?.let { tree.removePropertyChangeListener("UI", it.listener) }
        tree.putClientProperty(INSTALLED, null)
        synchronized(installed) { installed.remove(tree) }
        if (!tree.project.isDisposed) tree.project.service<CurrentReviewFile>().untrack(tree)
        if (tree.ui is HighlightingTreeUI) tree.updateUI()
    }

    /** Trees in hidden tabs are not reachable from any window; the registry remembers them for unload. */
    fun uninstallAll() {
        synchronized(installed) { installed.toList() }.forEach { tree -> runCatching { uninstall(tree) } }
    }

    private fun applyUi(tree: ChangesTree) {
        if (tree.ui is HighlightingTreeUI) return
        tree.setUI(HighlightingTreeUI(tree.project))
    }

    /** The file a change-tree node stands for; directory nodes carry a directory path and never match a file. */
    fun pathOf(userObject: Any?): FilePath? = when (userObject) {
        is RefComparisonChange -> userObject.filePathAfter ?: userObject.filePathBefore
        is FilePath -> userObject
        else -> null
    }

    /**
     * What one plugin version attached to a tree. The listener re-applies the highlighting UI after a theme
     * change, but only while this installation is still the tree's current one: a later plugin version that
     * replaced it must not be fought.
     */
    class Installation(private val tree: ChangesTree) : JComponent() {
        val listener: PropertyChangeListener = PropertyChangeListener { event ->
            if (tree.getClientProperty(INSTALLED) !== this) {
                tree.removePropertyChangeListener("UI", this.listener)
                return@PropertyChangeListener
            }
            val newUi = event.newValue as? TreeUI ?: return@PropertyChangeListener
            if (newUi is HighlightingTreeUI) return@PropertyChangeListener
            SwingUtilities.invokeLater { if (tree.getClientProperty(INSTALLED) === this) applyUi(tree) }
        }
    }

    class HighlightingTreeUI(project: Project) : DefaultTreeUI() {
        private val currentFile: CurrentReviewFile? = project.takeUnless { it.isDisposed }?.service<CurrentReviewFile>()

        override fun paint(g: Graphics, c: JComponent) {
            super.paint(g, c)
            paintCurrentFile(g, c as JTree)
        }

        private fun paintCurrentFile(g: Graphics, tree: JTree) {
            val current = currentFile?.path ?: return
            val clip = g.clipBounds ?: tree.visibleRect
            val first = tree.getClosestRowForLocation(0, clip.y).coerceAtLeast(0)
            val last = tree.getClosestRowForLocation(0, clip.y + clip.height - 1).coerceAtLeast(first)
            for (row in first..last) {
                val path = tree.getPathForRow(row) ?: continue
                if (pathOf(TreeUtil.getLastUserObject(path)) != current) continue
                val bounds = tree.getRowBounds(row) ?: continue
                val g2 = g.create() as Graphics2D
                try {
                    val tint = Color(HIGHLIGHT.red, HIGHLIGHT.green, HIGHLIGHT.blue, TINT_ALPHA)
                    val inset = if (ExperimentalUI.isNewUI()) JBUI.scale(SELECTION_INSET) else 0
                    val arc = if (ExperimentalUI.isNewUI()) JBUI.CurrentTheme.Tree.ARC.get().toDouble() else 0.0
                    g2.color = tint
                    RectanglePainter2D.FILL.paint(g2, inset.toDouble(), bounds.y.toDouble(), (tree.width - 2 * inset).toDouble(), bounds.height.toDouble(), arc)
                    g2.color = HIGHLIGHT
                    g2.fillRect(0, bounds.y, JBUI.scale(BAR_WIDTH), bounds.height)
                } finally {
                    g2.dispose()
                }
            }
        }

        companion object {
            /** The horizontal offset the new UI uses for its rounded selection. */
            private const val SELECTION_INSET = 12
        }
    }
}
