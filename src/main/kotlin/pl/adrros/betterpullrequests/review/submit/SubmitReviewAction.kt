package pl.adrros.betterpullrequests.review.submit

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction

/** Approve, request changes or comment: available wherever a concrete pull request is in the context. */
class SubmitReviewAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && ReviewSubmitService.resolve(project, e.dataContext) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = ReviewSubmitService.resolve(project, e.dataContext) ?: return
        // From a menu the context component is a whole panel or editor; a centred popup beats one "above" it.
        project.service<ReviewSubmitService>().open(target, anchor = null)
    }
}
