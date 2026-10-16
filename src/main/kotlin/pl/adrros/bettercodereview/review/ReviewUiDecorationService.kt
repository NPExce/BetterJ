package pl.adrros.bettercodereview.review

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.adrros.bettercodereview.copy.CommentCopyButtons
import pl.adrros.bettercodereview.copy.CommentPanes
import pl.adrros.bettercodereview.commit.CommitInclusionMarks
import pl.adrros.bettercodereview.review.files.CheckboxClicks
import pl.adrros.bettercodereview.review.files.ReviewChangeTrees
import pl.adrros.bettercodereview.review.submit.DetailsReviewButton
import com.intellij.openapi.vcs.changes.ui.ChangesListView
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import javax.swing.JPanel
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.ContainerEvent
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JEditorPane

private val LOG = logger<ReviewUiDecorationService>()
private const val POPUP_GROUP_ID = "BetterCodeReview.CommentPopup"

/**
 * Owns everything the plugin adds to the GitHub review UI without touching that UI's code: the context menu on
 * comments, the copy button in comment actions, the buttons under comment fields, the Review button in the
 * details, the marks in the change tree and the marks in the Commit tool window's tree.
 * Being an application service, it is disposed when the plugin unloads, and it then takes its additions back out
 * so a newer plugin version installed without restart starts from a clean UI.
 */
@Service(Service.Level.APP)
class ReviewUiDecorationService : Disposable {
    private val toolkit: Toolkit = Toolkit.getDefaultToolkit()
    private var listener: AWTEventListener? = null

    @Volatile
    var isDisposed: Boolean = false
        private set

    @Synchronized
    fun ensureInstalled() {
        if (listener != null || isDisposed) return
        val installed = AWTEventListener { event -> guarded { handle(event) } }
        toolkit.addAWTEventListener(installed, AWTEvent.MOUSE_EVENT_MASK or AWTEvent.CONTAINER_EVENT_MASK)
        listener = installed
        decorateExisting()
    }

    private fun handle(event: AWTEvent) {
        when {
            event is ContainerEvent && event.id == ContainerEvent.COMPONENT_ADDED -> decorate(event.child)
            event is MouseEvent && event.isPopupTrigger ->
                CommentPanes.asCommentPane(event.component)?.let { showPopup(it, event) }
            event is MouseEvent -> CheckboxClicks.intercept(event)
        }
    }

    /** Each decoration is guarded on its own, so one failing never costs the others. */
    private fun decorate(component: Component) {
        guarded { CommentPanes.asCommentPane(component)?.let(CommentCopyButtons::watch) }
        guarded { (component as? JPanel)?.takeIf(DetailsReviewButton::isDetailsActionsRow)?.let(DetailsReviewButton::watch) }
        guarded { (component as? ChangesTree)?.takeIf(ReviewChangeTrees::isReviewChangesTree)?.let(ReviewChangeTrees::watch) }
        guarded { (component as? ChangesListView)?.takeIf(CommitInclusionMarks::isCommitTree)?.let(CommitInclusionMarks::watch) }
    }

    private fun showPopup(pane: JEditorPane, event: MouseEvent) {
        val actionManager = ActionManager.getInstance()
        val group = actionManager.getAction(POPUP_GROUP_ID) as? ActionGroup ?: return
        val menu = actionManager.createActionPopupMenu(POPUP_GROUP_ID, group)
        menu.setTargetComponent(pane)
        menu.component.show(pane, event.x, event.y)
        event.consume()
    }

    /** Comments and comment editors built before the plugin was loaded (install without restart). */
    private fun decorateExisting() {
        for (window in Window.getWindows()) {
            UIUtil.uiTraverser(window).forEach(::decorate)
        }
        EditorFactory.getInstance().allEditors.forEach { editor -> guarded { ReviewCommentEditorListener.decorate(editor) } }
    }

    /**
     * Takes every injected component back out and clears the markers, so a newer plugin version loaded without a
     * restart decorates the same comments and fields again. Nothing is tracked in between: the markers on the
     * components themselves are the record.
     */
    @Synchronized
    override fun dispose() {
        isDisposed = true
        listener?.let { toolkit.removeAWTEventListener(it) }
        listener = null
        guarded { ReviewChangeTrees.uninstallAll() }
        guarded { CommitInclusionMarks.uninstallAll() }
        guarded {
            for (window in Window.getWindows()) {
                for (component in UIUtil.uiTraverser(window).filter(JComponent::class.java)) {
                    for (flag in INJECTED_FLAGS) {
                        (component.getClientProperty(flag) as? JComponent)?.let { injected ->
                            component.remove(injected)
                            component.revalidate()
                            component.repaint()
                        }
                    }
                    for (flag in ALL_FLAGS) {
                        if (component.getClientProperty(flag) != null) component.putClientProperty(flag, null)
                    }
                }
            }
        }
    }

    companion object {
        /** Markers whose value is the injected component. */
        val INJECTED_FLAGS: List<String> = listOf(CommentCopyButtons.INSTALLED, CommentInputButtons.INSTALLED, DetailsReviewButton.INSTALLED, ReviewChangeTrees.INSTALLED, CommitInclusionMarks.INSTALLED)
        val ALL_FLAGS: List<String> = INJECTED_FLAGS + CommentCopyButtons.WATCHED

        /** Listener code runs inside AWT event dispatch; an exception there would be reported as an IDE error. */
        fun guarded(block: () -> Unit) {
            try {
                block()
            } catch (e: Exception) {
                if (e is ControlFlowException || e is CancellationException) throw e
                LOG.warn("BetterCodeReview UI decoration failed", e)
            }
        }
    }
}

class ReviewUiDecorationInstaller : ProjectActivity {
    override suspend fun execute(project: Project) {
        withContext(Dispatchers.EDT) {
            service<ReviewUiDecorationService>().ensureInstalled()
        }
    }
}
