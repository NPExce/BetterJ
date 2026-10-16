package pl.adrros.bettercodereview.paste

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import pl.adrros.bettercodereview.review.ReviewCommentEditors
import pl.adrros.bettercodereview.review.ReviewTargets

/**
 * Uploads the clipboard image into the review comment editor as a GitHub attachment. Reachable from the Edit
 * menu and the button under the comment field; the paste shortcut goes through [ReviewImagePasteProvider].
 */
class PasteReviewImageAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val project = e.project
        e.presentation.isEnabledAndVisible = editor != null && project != null && applies(project, editor) && PastedImages.fromClipboard(respectText = false).isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return
        paste(project, editor)
    }

    companion object {
        /** A GitHub review comment editor in a project with a connected github.com pull request. */
        fun applies(project: Project, editor: Editor): Boolean =
            !editor.isDisposed && ReviewCommentEditors.isReviewCommentEditor(editor) &&
                service<ReviewTargets>().current(project)?.account?.server?.isGithubDotCom == true

        /** Returns true when at least one image was taken from the clipboard. */
        fun paste(project: Project, editor: Editor): Boolean {
            val target = service<ReviewTargets>().current(project) ?: return false
            val images = PastedImages.fromClipboard(respectText = false)
            if (images.isEmpty()) return false
            ReviewImageInserter.insertAndUpload(project, editor, target, images)
            return true
        }
    }
}
