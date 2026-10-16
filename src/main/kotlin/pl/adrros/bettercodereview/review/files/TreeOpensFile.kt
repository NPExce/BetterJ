package pl.adrros.bettercodereview.review.files

import com.intellij.collaboration.util.RefComparisonChange
import com.intellij.collaboration.util.getOrNull
import com.intellij.ide.DataManager
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
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
 * the diff, which is what reading a review on the checked-out branch calls for. Alt+double-click keeps opening
 * the diff (the platform drops Enter with any modifier before it reaches the tree's handler, so there is no
 * keyboard variant; Show Diff in the context menu stays). Everything falls back to the original handler when
 * the pull request's branch is not checked out, because the local file would be a different version, when the
 * tree is pinned to one commit, or when the tree is not a GitHub pull request's. A toggle in the tree's context
 * menu turns the feature off.
 */
object TreeOpensFile {
    private const val SETTING = "BetterCodeReview.doubleClickOpensFile"

    var isEnabled: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(SETTING, true)
        set(value) = PropertiesComponent.getInstance().setValue(SETTING, value, true)

    enum class BranchState { CHECKED_OUT, OTHER, UNKNOWN }

    /** Whether the tree's pull request is the one checked out; replaceable in headless tests. */
    var pullRequestBranch: (JTree) -> BranchState = { tree ->
        val context = DataManager.getInstance().getDataContext(tree)
        val id = context.getData(GHPRActionKeys.PULL_REQUEST_ID)
        val connected = context.getData(GHPRActionKeys.PULL_REQUESTS_CONNECTED_PROJECT_VM)
            ?: context.getData(CommonDataKeys.PROJECT)?.serviceIfCreated<GHPRProjectViewModel>()?.connectedProjectVm?.value
        val computed = connected?.prOnCurrentBranch?.value
        val result = computed?.result
        when {
            id == null -> BranchState.UNKNOWN // not a GitHub pull request's tree
            result == null || result.isFailure -> {
                LOG.debug("Current-branch state of the pull request is ${if (result == null) "still computing" else "unavailable"}; opening the diff")
                BranchState.UNKNOWN
            }
            computed.getOrNull() == id -> BranchState.CHECKED_OUT
            else -> BranchState.OTHER
        }
    }

    /** Shows the "branch not checked out" hint at the selected row; replaceable in headless tests. */
    var showHint: (ChangesTree, String) -> Unit = { tree, text ->
        if (!ApplicationManager.getApplication().isHeadlessEnvironment) {
            val at = tree.selectionPath?.let { tree.getPathBounds(it) }
                ?.let { RelativePoint(tree, java.awt.Point(it.x + it.width / 2, it.y)) }
                ?: JBPopupFactory.getInstance().guessBestPopupLocation(tree)
            JBPopupFactory.getInstance()
                .createHtmlTextBalloonBuilder(text, MessageType.INFO, null)
                .setFadeoutTime(6000)
                .createBalloon()
                .show(at, Balloon.Position.above)
        }
    }

    // The originals also sit in string-keyed client properties, so a plugin version unloaded without a restart
    // leaves them where the next version finds them instead of wrapping a wrapper from a dead classloader.
    private const val ORIGINAL_DOUBLE_CLICK = "BetterCodeReview.originalDoubleClickHandler"
    private const val ORIGINAL_ENTER = "BetterCodeReview.originalEnterKeyHandler"

    class Handlers(
        val originalDoubleClick: Processor<MouseEvent>?,
        val originalEnter: Processor<KeyEvent>?,
    ) {
        lateinit var doubleClick: Processor<MouseEvent>
        lateinit var enter: Processor<KeyEvent>
        /** The hint is shown once per tree: a reviewer who does not check out hears it once, not on every file. */
        var hinted = false
    }

    /** Wraps the tree's handlers and returns what [uninstall] needs to restore them. */
    @Suppress("UNCHECKED_CAST")
    fun install(tree: ChangesTree): Handlers {
        val storedDoubleClick = tree.getClientProperty(ORIGINAL_DOUBLE_CLICK) as? Processor<MouseEvent>
        val storedEnter = tree.getClientProperty(ORIGINAL_ENTER) as? Processor<KeyEvent>
        val handlers = Handlers(
            storedDoubleClick ?: tree.doubleClickHandler as Processor<MouseEvent>?,
            storedEnter ?: tree.enterKeyHandler as Processor<KeyEvent>?,
        )
        handlers.doubleClick = Processor { e ->
            val wantsDiff = e.modifiersEx and InputEvent.ALT_DOWN_MASK != 0
            if (isEnabled && !wantsDiff && tryOpenSelectedFile(tree, handlers)) true
            else handlers.originalDoubleClick?.process(e) ?: false
        }
        handlers.enter = Processor { e ->
            if (isEnabled && tryOpenSelectedFile(tree, handlers)) true
            else handlers.originalEnter?.process(e) ?: false
        }
        tree.doubleClickHandler = handlers.doubleClick
        tree.enterKeyHandler = handlers.enter
        tree.putClientProperty(ORIGINAL_DOUBLE_CLICK, handlers.originalDoubleClick)
        tree.putClientProperty(ORIGINAL_ENTER, handlers.originalEnter)
        return handlers
    }

    /** Restores the originals, unless something else has replaced the wrappers since. */
    fun uninstall(tree: ChangesTree, handlers: Handlers) {
        if (tree.doubleClickHandler === handlers.doubleClick) tree.doubleClickHandler = handlers.originalDoubleClick
        if (tree.enterKeyHandler === handlers.enter) tree.enterKeyHandler = handlers.originalEnter
        tree.putClientProperty(ORIGINAL_DOUBLE_CLICK, null)
        tree.putClientProperty(ORIGINAL_ENTER, null)
    }

    /** A failure inside the plugin must not swallow the click: the original handler then opens the diff. */
    private fun tryOpenSelectedFile(tree: ChangesTree, handlers: Handlers): Boolean =
        try {
            openSelectedFile(tree, handlers)
        } catch (e: Exception) {
            if (e is com.intellij.openapi.diagnostic.ControlFlowException || e is kotlinx.coroutines.CancellationException) throw e
            LOG.warn("Opening the file from the change tree failed; opening the diff instead", e)
            false
        } catch (e: LinkageError) {
            LOG.warn("Opening the file from the change tree failed; opening the diff instead", e)
            false
        }

    /** True when exactly one changed file is selected and it was opened; false hands the event to the original handler. */
    fun openSelectedFile(tree: ChangesTree, handlers: Handlers): Boolean {
        val selection = tree.selectionPath ?: return false
        if (tree.selectionCount != 1 || !tree.model.isLeaf(selection.lastPathComponent)) return false
        val change = VcsTreeModelData.exactlySelected(tree).iterateUserObjects(RefComparisonChange::class.java).toList().singleOrNull() ?: return false
        val path = change.filePathAfter ?: return false // deleted in the pull request: only the diff can show it
        val changeList = ReviewChangeTrees.changeListModelOf(tree)
        if (changeList != null && !ReviewDataSource.isOnLatest(changeList)) return false // one commit's tree: the local file is another version
        when (pullRequestBranch(tree)) {
            BranchState.CHECKED_OUT -> {}
            BranchState.OTHER -> {
                if (!handlers.hinted) {
                    handlers.hinted = true
                    showHint(tree, BetterCodeReviewBundle.message("tree.open.file.branch.not.checked.out"))
                }
                return false
            }
            BranchState.UNKNOWN -> return false
        }
        val file = path.virtualFile ?: LocalFileSystem.getInstance().refreshAndFindFileByPath(path.path) ?: return false
        FileEditorManager.getInstance(tree.project).openFile(file, true)
        LOG.debug("Opened ${path.path} from the change tree")
        return true
    }

    class ToggleOpensFileAction : ToggleAction(), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        override fun isSelected(e: AnActionEvent): Boolean = isEnabled
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            isEnabled = state
        }
    }
}
