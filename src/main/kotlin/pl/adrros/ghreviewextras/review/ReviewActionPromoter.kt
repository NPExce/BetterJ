package pl.adrros.ghreviewextras.review

import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import pl.adrros.ghreviewextras.copy.CommentPanes
import pl.adrros.ghreviewextras.copy.CopyCommentAction
import pl.adrros.ghreviewextras.paste.PasteReviewImageAction

/**
 * Copy and paste shortcuts are claimed by the platform and, inside diff inlays, by the host editor;
 * other plugins also wrap the editor paste handler and swallow pastes without text. Promoting our
 * actions makes them run first exactly where they apply, everywhere else they stay disabled.
 */
class ReviewActionPromoter : ActionPromoter {
    override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction>? {
        val promoted = actions.filter { action ->
            when (action) {
                is CopyCommentAction -> CommentPanes.asCommentPane(context.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT)) != null
                is PasteReviewImageAction -> context.getData(CommonDataKeys.EDITOR)?.let(PasteReviewImageAction::applies) == true
                else -> false
            }
        }
        return promoted.takeIf { it.isNotEmpty() }
    }
}
