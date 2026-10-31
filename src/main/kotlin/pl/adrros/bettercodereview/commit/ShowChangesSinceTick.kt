package pl.adrros.bettercodereview.commit

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import pl.adrros.bettercodereview.BetterCodeReviewBundle
import pl.adrros.bettercodereview.settings.Feature

/** Diff of a ticked file between the content it was ticked with and the content now, in the Commit and project trees. */
class ShowChangesSinceTick : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = tickedTextOf(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val ticked = tickedTextOf(e) ?: return
        val contents = DiffContentFactory.getInstance()
        DiffManager.getInstance().showDiff(
            project,
            SimpleDiffRequest(
                BetterCodeReviewBundle.message("sinceTick.title", file.name),
                contents.create(project, ticked, file.fileType),
                contents.create(project, file),
                BetterCodeReviewBundle.message("sinceTick.ticked"),
                BetterCodeReviewBundle.message("sinceTick.now"),
            ),
        )
    }

    private fun tickedTextOf(e: AnActionEvent): String? {
        if (!Feature.COMMIT_MARKS.isOn) return null
        val project = e.project ?: return null
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
        return CommitInclusionMarks.anyOverlayFor(project)?.tickedText(file.path)
    }
}
