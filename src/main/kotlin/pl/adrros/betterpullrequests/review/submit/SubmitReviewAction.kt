package pl.adrros.betterpullrequests.review.submit

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction

/** Approve, request changes or comment: available wherever a pull request is in the context. */
class SubmitReviewAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && ReviewSubmitService.resolve(project, e.dataContext) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = ReviewSubmitService.resolve(project, e.dataContext) ?: return
        project.service<ReviewSubmitService>().open(target, e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT))
    }
}
