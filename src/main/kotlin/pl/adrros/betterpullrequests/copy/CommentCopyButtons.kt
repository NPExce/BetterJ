package pl.adrros.betterpullrequests.copy

import com.intellij.icons.AllIcons
import com.intellij.ui.components.panels.ListLayout
import com.intellij.util.ui.InlineIconButton
import com.intellij.util.ui.UIUtil
import pl.adrros.betterpullrequests.BetterPullRequestsBundle
import java.awt.Component
import java.awt.Container
import java.awt.event.ActionListener
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel

/**
 * Adds a copy button next to the edit / delete / react buttons that appear when a comment is hovered.
 * The GitHub plugin builds that row with `HorizontalListPanel` (a `JPanel` with a `ListLayout`) holding only
 * `InlineIconButton`s, inside a header laid out with MigLayout; those facts are the only way to recognise it.
 */
object CommentCopyButtons {
    private const val WATCHED = "BetterPullRequests.copyButton.watched"
    private const val INSTALLED = "BetterPullRequests.copyButton"
    private const val MIG_LAYOUT = "MigLayout"
    private const val MAX_ANCESTORS = 8

    /** Call when a comment pane joins a container; the button lands once the whole comment is on screen. */
    fun watch(pane: JEditorPane) {
        if (pane.getClientProperty(WATCHED) != null) return
        pane.putClientProperty(WATCHED, true)
        if (pane.isDisplayable && install(pane)) return
        pane.addHierarchyListener(object : HierarchyListener {
            override fun hierarchyChanged(e: HierarchyEvent) {
                if (e.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() == 0L || !pane.isDisplayable) return
                pane.removeHierarchyListener(this)
                install(pane)
            }
        })
    }

    fun install(pane: JEditorPane): Boolean {
        val actions = findActionsPanel(pane) ?: return false
        if (actions.getClientProperty(INSTALLED) != null) return true
        val button = InlineIconButton(AllIcons.Actions.Copy).apply {
            toolTipText = BetterPullRequestsBundle.message("copy.button.tooltip")
            withBackgroundHover = true
            actionListener = ActionListener { CommentPanes.copyToClipboard(CommentPanes.wholeText(pane)) }
        }
        actions.add(button)
        actions.putClientProperty(INSTALLED, button)
        actions.revalidate()
        actions.repaint()
        return true
    }

    fun findActionsPanel(pane: JEditorPane): JComponent? {
        var ancestor: Container? = pane.parent
        repeat(MAX_ANCESTORS) {
            val current = ancestor ?: return null
            UIUtil.uiTraverser(current).filter(::isActionsPanel).firstOrNull()?.let { return it as JComponent }
            ancestor = current.parent
        }
        return null
    }

    private fun isActionsPanel(component: Component): Boolean =
        component is JPanel &&
            component.layout is ListLayout &&
            component.components.all { it is InlineIconButton } &&
            component.parent?.layout?.javaClass?.simpleName == MIG_LAYOUT
}
