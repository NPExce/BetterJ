package pl.adrros.bettercodereview.review

import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import pl.adrros.bettercodereview.copy.CommentPanes
import pl.adrros.bettercodereview.copy.CopyCommentAction

/**
 * Inside a diff inlay the host editor's own copy action would win the shortcut and copy the (empty) editor
 * selection instead of the comment. Promoting our action makes it run first whenever the focused component
 * is a review comment pane; everywhere else it stays disabled.
 */
class ReviewActionPromoter : ActionPromoter {
    override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction>? {
        CommentPanes.asCommentPane(context.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT)) ?: return null
        return actions.filterIsInstance<CopyCommentAction>().takeIf { it.isNotEmpty() }
    }
}
