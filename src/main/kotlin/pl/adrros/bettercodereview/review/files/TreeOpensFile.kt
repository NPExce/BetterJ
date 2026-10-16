package pl.adrros.bettercodereview.review.files

import com.intellij.collaboration.util.RefComparisonChange
import com.intellij.collaboration.util.getOrNull
import com.intellij.ide.DataManager
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.openapi.vcs.changes.ui.VcsTreeModelData
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.Processor
import org.jetbrains.plugins.github.pullrequest.action.GHPRActionKeys
import org.jetbrains.plugins.github.pullrequest.ui.GHPRProjectViewModel
import pl.adrros.bettercodereview.BetterCodeReviewBundle
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JTree

private val LOG = logger<TreeOpensFile>()

/**
 * Double-click and Enter on a file in the pull request's change tree open the file in the editor instead of
 * the diff, which is what reading a review on the checked-out branch calls for. Alt+double-click and
 * Shift+Enter keep opening the diff; so does everything when the pull request's branch is not checked out,
 * because the local file would then be a different version. A toggle in the tree's context menu turns it off.
 */
object TreeOpensFile {
    private const val SETTING = "BetterCodeReview.doubleClickOpensFile"

    var isEnabled: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(SETTING, true)
        set(value) = PropertiesComponent.getInstance().setValue(SETTING, value, true)

    /** Whether the tree's pull request is the one checked out; replaceable in headless tests. */
    var pullRequestBranchCheckedOut: (JTree) -> Boolean = { tree ->
        val context = DataManager.getInstance().getDataContext(tree)
        val id = context.getData(GHPRActionKeys.PULL_REQUEST_ID)
        val connected = context.getData(GHPRActionKeys.PULL_REQUESTS_CONNECTED_PROJECT_VM)
            ?: context.getData(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT)?.serviceIfCreated<GHPRProjectViewModel>()?.connectedProjectVm?.value
        id != null && connected?.prOnCurrentBranch?.value?.getOrNull() == id
    }

    class Handlers(val doubleClick: Processor<in MouseEvent>?, val enter: Processor<in KeyEvent>?)

    /** Wraps the tree's handlers and returns the originals for [uninstall]. */
    fun install(tree: ChangesTree): Handlers {
        val originals = Handlers(tree.doubleClickHandler, tree.enterKeyHandler)
        tree.doubleClickHandler = Processor { e ->
            if (shouldOpenFile(wantsDiff = e.modifiersEx and InputEvent.ALT_DOWN_MASK != 0) && openSelectedFile(tree)) true
            else originals.doubleClick?.process(e) ?: false
        }
        tree.enterKeyHandler = Processor { e ->
            if (shouldOpenFile(wantsDiff = e.isShiftDown) && openSelectedFile(tree)) true
            else originals.enter?.process(e) ?: false
        }
        return originals
    }

    fun uninstall(tree: ChangesTree, originals: Handlers) {
        tree.doubleClickHandler = originals.doubleClick
        tree.enterKeyHandler = originals.enter
    }

    fun shouldOpenFile(wantsDiff: Boolean): Boolean = isEnabled && !wantsDiff

    /** True when a single changed file was selected and opened; false hands the event to the original handler. */
    fun openSelectedFile(tree: ChangesTree): Boolean {
        val change = VcsTreeModelData.selected(tree).iterateUserObjects(RefComparisonChange::class.java).toList().singleOrNull() ?: return false
        val path = change.filePathAfter ?: return false // deleted in the pull request: only the diff can show it
        if (!pullRequestBranchCheckedOut(tree)) {
            hint(tree, BetterCodeReviewBundle.message("tree.open.file.branch.not.checked.out"))
            return false
        }
        val file = path.virtualFile ?: LocalFileSystem.getInstance().refreshAndFindFileByPath(path.path) ?: return false
        FileEditorManager.getInstance(tree.project).openFile(file, true)
        LOG.debug("Opened ${path.path} from the change tree")
        return true
    }

    private fun hint(tree: ChangesTree, text: String) {
        if (com.intellij.openapi.application.ApplicationManager.getApplication().isHeadlessEnvironment) return
        JBPopupFactory.getInstance()
            .createHtmlTextBalloonBuilder(text, MessageType.INFO, null)
            .setFadeoutTime(4000)
            .createBalloon()
            .show(RelativePoint.getCenterOf(tree), Balloon.Position.above)
    }

    class ToggleOpensFileAction : ToggleAction(), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        override fun isSelected(e: AnActionEvent): Boolean = isEnabled
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            isEnabled = state
        }
    }
}
