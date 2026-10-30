package pl.adrros.bettercodereview.commit

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.AnActionResult
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.project.Project

/**
 * Commit (Cmd+K) resets the Commit panel's checkboxes to every change of the active changelist, or adds the changes
 * that appeared since the panel opened. The checkboxes set by hand, in the Commit panel or the project tree, are
 * put back right after, so both places keep showing the same state.
 */
class KeepInclusionOnCommit : AnActionListener {
    private var pending: Pair<CommitInclusionMarks.InclusionOverlay, CommitInclusionMarks.Inclusion>? = null

    override fun beforeActionPerformed(action: AnAction, event: AnActionEvent) =
        before(ActionManager.getInstance().getId(action), event.project)

    override fun afterActionPerformed(action: AnAction, event: AnActionEvent, result: AnActionResult) =
        after(ActionManager.getInstance().getId(action))

    internal fun before(actionId: String?, project: Project?) {
        if (actionId != COMMIT) return
        pending = null
        if (project == null || !CommitInclusionMarks.isEnabled) return
        val overlay = CommitInclusionMarks.anyOverlayFor(project) ?: return
        pending = overlay to overlay.inclusion()
    }

    internal fun after(actionId: String?) {
        if (actionId != COMMIT) return
        val (overlay, before) = pending ?: return
        pending = null
        overlay.restore(before)
    }

    companion object {
        const val COMMIT = "CheckinProject"
    }
}
