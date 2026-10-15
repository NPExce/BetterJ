package pl.adrros.betterpullrequests.review

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.panels.ListLayout
import com.intellij.util.ui.InlineIconButton
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import pl.adrros.betterpullrequests.BetterPullRequestsBundle
import pl.adrros.betterpullrequests.emoji.EmojiIcon
import pl.adrros.betterpullrequests.paste.PastedImages
import java.awt.Component
import java.awt.Container
import java.awt.event.ActionListener
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Two small buttons under every review comment field: an emoji picker and "paste image from clipboard".
 * The GitHub plugin lays the field's action row out with MigLayout (hints on the left, Reply/Resolve on the
 * right); the buttons slot in as the row's first cell.
 */
object CommentInputButtons {
    private const val INSTALLED = "BetterPullRequests.inputButtons"
    private const val MIG_LAYOUT = "MigLayout"
    private const val MAX_ANCESTORS = 8
    private const val ICON_SIZE = 16

    fun watch(editor: Editor) {
        val component = editor.component
        if (component.isDisplayable && install(editor)) return
        component.addHierarchyListener(object : HierarchyListener {
            override fun hierarchyChanged(e: HierarchyEvent) {
                if (e.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() == 0L || !component.isDisplayable) return
                component.removeHierarchyListener(this)
                install(editor)
            }
        })
    }

    fun install(editor: Editor): Boolean {
        val row = findActionRow(editor.component) ?: return false
        if (row.getClientProperty(INSTALLED) != null) return true
        val buttons = JPanel(ListLayout.horizontal(JBUI.scale(4))).apply {
            isOpaque = false
            add(button(EmojiIcon("😀", ICON_SIZE), BetterPullRequestsBundle.message("input.button.emoji")) {
                perform(ReviewCommentEditorListener.INSERT_EMOJI_ACTION, editor)
            })
            add(button(AllIcons.FileTypes.Image, BetterPullRequestsBundle.message("input.button.image")) {
                if (PastedImages.clipboardHasImagesOnly()) perform(ReviewCommentEditorListener.PASTE_IMAGE_ACTION, editor)
                else showHint(it, BetterPullRequestsBundle.message("input.button.image.empty"))
            })
        }
        row.add(buttons, 0)
        row.putClientProperty(INSTALLED, buttons)
        row.revalidate()
        row.repaint()
        return true
    }

    /** The MigLayout row holding the Reply / Resolve buttons, found next to the editor in its ancestors. */
    fun findActionRow(editorComponent: JComponent): JComponent? {
        var ancestor: Container? = editorComponent.parent
        repeat(MAX_ANCESTORS) {
            val current = ancestor ?: return null
            current.components.firstOrNull(::isActionRow)?.let { return it as JComponent }
            ancestor = current.parent
        }
        return null
    }

    private fun isActionRow(component: Component): Boolean =
        component is JPanel &&
            component.layout?.javaClass?.simpleName == MIG_LAYOUT &&
            UIUtil.uiTraverser(component).any { it is JButton }

    private fun button(icon: javax.swing.Icon, tooltip: String, onClick: (JComponent) -> Unit): InlineIconButton =
        InlineIconButton(icon).apply {
            toolTipText = tooltip
            withBackgroundHover = true
            actionListener = ActionListener { onClick(this) }
        }

    private fun perform(actionId: String, editor: Editor) {
        val action: AnAction = ActionManager.getInstance().getAction(actionId) ?: return
        editor.contentComponent.requestFocusInWindow()
        ActionUtil.invokeAction(action, editor.contentComponent, ActionPlaces.EDITOR_TOOLBAR, null, null)
    }

    private fun showHint(anchor: JComponent, text: String) {
        JBPopupFactory.getInstance()
            .createHtmlTextBalloonBuilder(text, MessageType.INFO, null)
            .setFadeoutTime(3000)
            .createBalloon()
            .show(RelativePoint.getCenterOf(anchor), Balloon.Position.above)
    }
}
