package pl.adrros.betterpullrequests.copy

import com.intellij.icons.AllIcons
import com.intellij.ui.components.panels.ListLayout
import com.intellij.util.ui.InlineIconButton
import com.intellij.util.ui.UIUtil
import pl.adrros.betterpullrequests.BetterPullRequestsBundle
import pl.adrros.betterpullrequests.review.Retrying
import pl.adrros.betterpullrequests.review.ReviewUiDecorationService
import java.awt.Component
import java.awt.Container
import java.awt.event.ActionListener
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel

/**
 * Adds a copy button next to the edit / delete / react buttons that appear when a comment is hovered.
 * The GitHub plugin builds that row with `HorizontalListPanel` (a `JPanel` with a `ListLayout`) holding only
 * `InlineIconButton`s, inside a header laid out with MigLayout; those facts are the only way to recognise it.
 */
object CommentCopyButtons {
    const val WATCHED = "BetterPullRequests.copyButton.watched"
    const val INSTALLED = "BetterPullRequests.copyButton"
    private const val MIG_LAYOUT = "MigLayout"
    private const val MAX_ANCESTORS = 8

    /** Call when a comment pane joins a container; the button lands once the whole comment is on screen. */
    fun watch(pane: JEditorPane) {
        if (pane.getClientProperty(WATCHED) != null) return
        pane.putClientProperty(WATCHED, true)
        Retrying.whenDisplayable(pane) { !pane.isDisplayable || install(pane) }
    }

    fun install(pane: JEditorPane): Boolean {
        val actions = findActionsPanel(pane) ?: return false
        if (actions.getClientProperty(INSTALLED) != null) return true
        val button = InlineIconButton(AllIcons.Actions.Copy).apply {
            toolTipText = BetterPullRequestsBundle.message("copy.button.tooltip")
            withBackgroundHover = true
            actionListener = ActionListener { CommentPanes.copyToClipboard(CommentPanes.wholeText(pane)) }
        }
        actions.putClientProperty(INSTALLED, button)
        actions.add(button)
        actions.revalidate()
        actions.repaint()
        return true
    }

    /**
     * The actions row of the comment the pane belongs to. The author-and-date title is a pane of the same kind
     * sitting inside the header next to the actions; it must not claim the button for itself.
     */
    fun findActionsPanel(pane: JEditorPane): JComponent? {
        var ancestor: Container? = pane.parent
        repeat(MAX_ANCESTORS) {
            val current = ancestor ?: return null
            val actions = UIUtil.uiTraverser(current).filter(::isActionsPanel).firstOrNull() as JComponent?
            if (actions != null) {
                return actions.takeUnless { pane.isInsideHeaderOf(it) }
            }
            ancestor = current.parent
        }
        return null
    }

    private fun JEditorPane.isInsideHeaderOf(actions: JComponent): Boolean {
        val header = actions.parent ?: return false
        return UIUtil.uiTraverser(header).contains(this)
    }

    private fun isActionsPanel(component: Component): Boolean =
        component is JPanel &&
            component.layout is ListLayout &&
            component.components.all { it is InlineIconButton } &&
            component.parent?.layout?.javaClass?.simpleName == MIG_LAYOUT
}
