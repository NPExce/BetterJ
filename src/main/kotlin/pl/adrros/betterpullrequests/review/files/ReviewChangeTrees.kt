package pl.adrros.betterpullrequests.review.files

import com.intellij.collaboration.util.RefComparisonChange
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangesUtil
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.tree.ui.DefaultTreeUI
import com.intellij.util.ui.tree.TreeUtil
import pl.adrros.betterpullrequests.review.DecorationOwner
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.plaf.TreeUI

/**
 * Marks the file open in the editor inside the pull request's change tree, so the reader always knows which
 * changed file they are in and can tick it as viewed. The tree paints row backgrounds only for selection and
 * hover; a thin UI subclass paints the mark underneath everything else, and the renderer stays untouched.
 */
object ReviewChangeTrees {
    const val INSTALLED = "BetterPullRequests.currentFileHighlight"
    private const val REVIEW_TREE_FACTORY = "com.intellij.collaboration.ui.codereview.changes.CodeReviewChangeListComponentFactory"

    /** Light and dark purple, a tint rather than a selection colour. */
    val HIGHLIGHT: Color = JBColor(Color(0xEA, 0xDC, 0xFB), Color(0x3D, 0x2C, 0x5E))

    /** The change tree built by the collaboration tools for a pull request (GitHub and GitLab alike). */
    fun isReviewChangesTree(component: Component): Boolean =
        component is ChangesTree && component.javaClass.name.startsWith(REVIEW_TREE_FACTORY)

    fun watch(tree: ChangesTree) {
        (tree.getClientProperty(INSTALLED) as? JComponent)?.let { if (DecorationOwner.owns(it)) return }
        install(tree)
    }

    fun install(tree: ChangesTree): Boolean {
        val marker = Marker()
        DecorationOwner.mark(marker)
        tree.putClientProperty(INSTALLED, marker)
        tree.project.service<CurrentReviewFile>().track(tree)
        applyUi(tree)
        // A theme change puts the look-and-feel's UI back; follow it with ours again.
        tree.addPropertyChangeListener("UI", marker.uiListener(tree))
        return true
    }

    fun uninstall(tree: JTree) {
        (tree.getClientProperty(INSTALLED) as? Marker)?.let { tree.removePropertyChangeListener("UI", it.listener) }
        tree.putClientProperty(INSTALLED, null)
        if (tree.ui is HighlightingTreeUI) tree.updateUI()
    }

    private fun applyUi(tree: ChangesTree) {
        if (tree.ui is HighlightingTreeUI) return
        tree.setUI(HighlightingTreeUI(tree.project))
    }

    /** The absolute path a change-tree node stands for, if it is a file. */
    fun pathOf(userObject: Any?): String? = when (userObject) {
        is RefComparisonChange -> (userObject.filePathAfter ?: userObject.filePathBefore)?.path
        is Change -> ChangesUtil.getFilePath(userObject).path
        is FilePath -> userObject.path
        is VirtualFile -> userObject.path
        else -> null
    }

    class Marker : JComponent() {
        lateinit var listener: PropertyChangeListener

        fun uiListener(tree: ChangesTree): PropertyChangeListener {
            listener = PropertyChangeListener { event ->
                val newUi = event.newValue as? TreeUI
                if (newUi != null && newUi !is HighlightingTreeUI && DecorationOwner.owns(this)) applyUi(tree)
            }
            return listener
        }
    }

    class HighlightingTreeUI(private val project: Project) : DefaultTreeUI() {
        override fun paint(g: Graphics, c: JComponent) {
            paintCurrentFile(g, c as JTree)
            super.paint(g, c)
        }

        private fun paintCurrentFile(g: Graphics, tree: JTree) {
            val current = project.service<CurrentReviewFile>().path ?: return
            for (row in 0 until tree.rowCount) {
                val path = tree.getPathForRow(row) ?: continue
                if (pathOf(TreeUtil.getLastUserObject(path)) != current) continue
                val bounds = tree.getRowBounds(row) ?: continue
                g.color = HIGHLIGHT
                g.fillRect(0, bounds.y, tree.width, bounds.height)
            }
        }
    }
}
