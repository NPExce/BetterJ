package pl.adrros.betterpullrequests.emoji

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorModificationUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import pl.adrros.betterpullrequests.review.ReviewCommentEditors

class InsertEmojiAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor != null && ReviewCommentEditors.isReviewCommentEditor(editor)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return
        EmojiPickerPopup(service<EmojiCatalog>(), service<RecentEmoji>()) { emoji -> insertAtCaret(project, editor, emoji.glyph) }
            .show(editor)
    }

    private fun insertAtCaret(project: Project, editor: Editor, text: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            EditorModificationUtil.insertStringAtCaret(editor, text)
        }
    }
}
