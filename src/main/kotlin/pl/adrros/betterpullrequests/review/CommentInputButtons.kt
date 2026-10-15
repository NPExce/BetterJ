package pl.adrros.betterpullrequests.review

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.panels.ListLayout
import com.intellij.util.ui.InlineIconButton
import com.intellij.util.ui.JBUI
import pl.adrros.betterpullrequests.BetterPullRequestsBundle
import pl.adrros.betterpullrequests.emoji.EmojiIcon
import pl.adrros.betterpullrequests.paste.PastedImages
import java.awt.Component
import java.awt.Container
import java.awt.event.ActionListener
import javax.swing.JComponent
import javax.swing.JPanel

private val LOG = logger<CommentInputButtons>()

/**
 * Two small buttons under every review comment field: an emoji picker and "paste image from clipboard".
 * `CommentInputActionsComponentFactory.attachActions` stacks the field and a MigLayout row (hints on the left,
 * Reply / Resolve on the right) in a vertical list panel; the buttons slot in as the row's first cell. The row
 * exists from the start while its own buttons arrive later from a coroutine, so the row is recognised by its
 * place next to the field, never by what it contains.
 */
object CommentInputButtons {
    const val INSTALLED = "BetterPullRequests.inputButtons"
    private const val MIG_LAYOUT = "MigLayout"
    private const val MAX_ANCESTORS = 8
    private const val ICON_SIZE = 16

    fun watch(editor: Editor) {
        Retrying.whenDisplayable(editor.component) { editor.isDisposed || install(editor) }
    }

    fun install(editor: Editor): Boolean {
        val row = findActionRow(editor.component)
        if (row == null) {
            LOG.debug { "No action row yet for review comment editor; ancestors: ${describeAncestors(editor.component)}" }
            return false
        }
        if (row.getClientProperty(INSTALLED) != null) return true
        val buttons = JPanel(ListLayout.horizontal(JBUI.scale(4))).apply {
            isOpaque = false
            add(button(EmojiIcon("😀", ICON_SIZE), emojiTooltip()) {
                perform(ReviewCommentEditorListener.INSERT_EMOJI_ACTION, editor)
            })
            add(button(AllIcons.FileTypes.Image, imageTooltip()) {
                if (PastedImages.fromClipboard(respectText = false).isNotEmpty()) perform(ReviewCommentEditorListener.PASTE_IMAGE_ACTION, editor)
                else showHint(it, BetterPullRequestsBundle.message("input.button.image.empty"))
            })
        }
        row.putClientProperty(INSTALLED, buttons)
        row.add(buttons, 0)
        row.revalidate()
        row.repaint()
        return true
    }

    /** The MigLayout row that follows the field inside the vertical list panel built by `attachActions`. */
    fun findActionRow(editorComponent: JComponent): JComponent? {
        var child: Component = editorComponent
        var ancestor: Container? = editorComponent.parent
        repeat(MAX_ANCESTORS) {
            val current = ancestor ?: return null
            if (current is JPanel && current.layout is ListLayout) {
                val index = current.components.indexOf(child)
                val next = current.components.getOrNull(index + 1)
                if (next is JPanel && next.layout?.javaClass?.simpleName == MIG_LAYOUT) return next
            }
            child = current
            ancestor = current.parent
        }
        return null
    }

    private fun describeAncestors(component: JComponent): String {
        val chain = mutableListOf<String>()
        var current: Container? = component.parent
        repeat(MAX_ANCESTORS) {
            val c = current ?: return@repeat
            val children = c.components.joinToString(",") { it.javaClass.simpleName + "/" + ((it as? Container)?.layout?.javaClass?.simpleName ?: "-") }
            chain += "${c.javaClass.simpleName}/${c.layout?.javaClass?.simpleName}[$children]"
            current = c.parent
        }
        return chain.joinToString(" <- ")
    }

    private fun emojiTooltip(): String {
        val shortcut = ActionManager.getInstance().getAction(ReviewCommentEditorListener.INSERT_EMOJI_ACTION)
            ?.let { KeymapUtil.getFirstKeyboardShortcutText(it) }.orEmpty()
        return BetterPullRequestsBundle.message("input.button.emoji", shortcut)
    }

    private fun imageTooltip(): String {
        val shortcut = listOf(IdeActions.ACTION_EDITOR_PASTE, IdeActions.ACTION_PASTE)
            .map { KeymapUtil.getFirstKeyboardShortcutText(it) }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
        return BetterPullRequestsBundle.message("input.button.image", shortcut)
    }

    private fun button(icon: javax.swing.Icon, tooltip: String, onClick: (JComponent) -> Unit): InlineIconButton =
        InlineIconButton(icon).apply {
            toolTipText = tooltip
            withBackgroundHover = true
            actionListener = ActionListener { onClick(this) }
        }

    private fun perform(actionId: String, editor: Editor) {
        if (editor.isDisposed) return
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
