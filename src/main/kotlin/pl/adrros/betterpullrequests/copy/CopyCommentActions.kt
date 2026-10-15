package pl.adrros.betterpullrequests.copy

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.project.DumbAwareAction
import javax.swing.JEditorPane

abstract class CommentPaneAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val pane = commentPane(e)
        e.presentation.isEnabledAndVisible = pane != null && isEnabledFor(pane)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val pane = commentPane(e) ?: return
        CommentPanes.copyToClipboard(textToCopy(pane))
    }

    protected open fun isEnabledFor(pane: JEditorPane): Boolean = true

    protected abstract fun textToCopy(pane: JEditorPane): String

    private fun commentPane(e: AnActionEvent): JEditorPane? =
        CommentPanes.asCommentPane(e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT))
}

/** Bound to the platform copy shortcut: copies the selection, or the whole comment when nothing is selected. */
class CopyCommentAction : CommentPaneAction() {
    override fun textToCopy(pane: JEditorPane): String = CommentPanes.selectedOrWholeText(pane)
}

class CopyWholeCommentAction : CommentPaneAction() {
    override fun textToCopy(pane: JEditorPane): String = CommentPanes.wholeText(pane)
}

class CopySelectedCommentTextAction : CommentPaneAction() {
    override fun isEnabledFor(pane: JEditorPane): Boolean = CommentPanes.selectedText(pane) != null

    override fun textToCopy(pane: JEditorPane): String = CommentPanes.selectedText(pane) ?: ""
}
