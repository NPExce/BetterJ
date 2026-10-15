package pl.adrros.betterpullrequests.paste

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAwareAction
import pl.adrros.betterpullrequests.review.ReviewCommentEditors

/**
 * Bound to the platform paste shortcut and promoted ahead of it in review comment editors whenever the
 * clipboard holds an image but no text. The regular paste keeps handling text.
 */
class PasteReviewImageAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor != null && e.project != null && applies(editor)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return
        PastedImages.fromClipboard().forEach { ReviewImageInserter.insertAndUpload(project, editor, it) }
    }

    companion object {
        fun applies(editor: Editor): Boolean =
            ReviewCommentEditors.isReviewCommentEditor(editor) && PastedImages.clipboardHasImagesOnly()
    }
}
