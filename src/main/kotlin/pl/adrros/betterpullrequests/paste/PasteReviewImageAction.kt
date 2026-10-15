package pl.adrros.betterpullrequests.paste

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import pl.adrros.betterpullrequests.review.ReviewCommentEditors
import pl.adrros.betterpullrequests.review.ReviewTargets

/**
 * Uploads the clipboard image into the review comment editor as a GitHub attachment. Reachable from the Edit
 * menu, the button under the comment field and, through [PasteShortcutInterceptor], the paste shortcut.
 */
class PasteReviewImageAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val project = e.project
        e.presentation.isEnabledAndVisible = editor != null && project != null && applies(project, editor) && PastedImages.fromClipboard().isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return
        paste(project, editor)
    }

    companion object {
        /** A GitHub review comment editor in a project with a connected pull request. */
        fun applies(project: Project, editor: Editor): Boolean =
            !editor.isDisposed && ReviewCommentEditors.isReviewCommentEditor(editor) && service<ReviewTargets>().current(project) != null

        /** Returns true when at least one image was taken from the clipboard. */
        fun paste(project: Project, editor: Editor): Boolean {
            val target = service<ReviewTargets>().current(project) ?: return false
            val images = PastedImages.fromClipboard()
            if (images.isEmpty()) return false
            ReviewImageInserter.insertAndUpload(project, editor, target, images)
            return true
        }
    }
}
