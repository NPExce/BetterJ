package pl.adrros.betterpullrequests.paste

import com.intellij.ide.PasteProvider
import com.intellij.openapi.editor.actions.PasteAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import pl.adrros.betterpullrequests.review.ReviewTargets
import java.awt.datatransfer.Transferable

/**
 * The platform's paste handler asks every custom paste provider before pasting text, on every road to a paste:
 * the keymap's shortcut (whatever it is bound to), the Edit menu, the context menu and paste from history.
 * This provider claims the paste when a connected GitHub review comment editor receives an image.
 */
class ReviewImagePasteProvider : PasteProvider {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun isPastePossible(dataContext: DataContext): Boolean {
        val editor = dataContext.getData(CommonDataKeys.EDITOR) ?: return false
        val project = editor.project ?: dataContext.getData(CommonDataKeys.PROJECT) ?: return false
        return PasteReviewImageAction.applies(project, editor)
    }

    override fun isPasteEnabled(dataContext: DataContext): Boolean =
        isPastePossible(dataContext) && images(dataContext).isNotEmpty()

    override fun performPaste(dataContext: DataContext) {
        val editor = dataContext.getData(CommonDataKeys.EDITOR) ?: return
        val project = editor.project ?: dataContext.getData(CommonDataKeys.PROJECT) ?: return
        val target = service<ReviewTargets>().current(project) ?: return
        val images = images(dataContext)
        if (images.isEmpty()) return
        ReviewImageInserter.insertAndUpload(project, editor, target, images)
    }

    private fun images(dataContext: DataContext): List<PastedImage> {
        val transferable: Transferable = dataContext.getData(PasteAction.TRANSFERABLE_PROVIDER)?.produce()
            ?: CopyPasteManager.getInstance().contents
            ?: return emptyList()
        return PastedImages.fromTransferable(transferable)
    }
}
