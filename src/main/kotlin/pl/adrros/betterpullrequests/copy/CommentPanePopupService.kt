package pl.adrros.betterpullrequests.copy

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.adrros.betterpullrequests.review.ReviewCommentEditorListener
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.ContainerEvent
import java.awt.event.MouseEvent
import javax.swing.JEditorPane

private const val POPUP_GROUP_ID = "BetterPullRequests.CommentPopup"

/**
 * One application-wide listener decorates the comment panes the GitHub plugin builds: a context menu on
 * right click and a copy button in the hover actions, without touching the plugin's own components.
 */
@Service(Service.Level.APP)
class CommentPanePopupService : Disposable {
    private val toolkit: Toolkit = Toolkit.getDefaultToolkit()
    private var listener: AWTEventListener? = null

    @Synchronized
    fun ensureInstalled() {
        if (listener != null) return
        val installed = AWTEventListener { event -> handle(event) }
        toolkit.addAWTEventListener(installed, AWTEvent.MOUSE_EVENT_MASK or AWTEvent.CONTAINER_EVENT_MASK)
        listener = installed
        decorateExisting()
    }

    /** Comments and comment editors built before the plugin was loaded (install without restart) get the same treatment. */
    private fun decorateExisting() {
        for (window in Window.getWindows()) {
            UIUtil.uiTraverser(window).forEach { component -> CommentPanes.asCommentPane(component)?.let(CommentCopyButtons::watch) }
        }
        EditorFactory.getInstance().allEditors.forEach(ReviewCommentEditorListener::decorate)
    }

    private fun handle(event: AWTEvent) {
        when {
            event is ContainerEvent && event.id == ContainerEvent.COMPONENT_ADDED ->
                CommentPanes.asCommentPane(event.child)?.let(CommentCopyButtons::watch)
            event is MouseEvent && event.isPopupTrigger ->
                CommentPanes.asCommentPane(event.component)?.let { showPopup(it, event) }
        }
    }

    private fun showPopup(pane: JEditorPane, event: MouseEvent) {
        val actionManager = ActionManager.getInstance()
        val group = actionManager.getAction(POPUP_GROUP_ID) as? ActionGroup ?: return
        val menu = actionManager.createActionPopupMenu(POPUP_GROUP_ID, group)
        menu.setTargetComponent(pane)
        menu.component.show(pane, event.x, event.y)
        event.consume()
    }

    @Synchronized
    override fun dispose() {
        listener?.let { toolkit.removeAWTEventListener(it) }
        listener = null
    }
}

class CommentPanePopupInstaller : ProjectActivity {
    override suspend fun execute(project: Project) {
        withContext(Dispatchers.EDT) {
            service<CommentPanePopupService>().ensureInstalled()
            service<pl.adrros.betterpullrequests.paste.PasteShortcutInterceptor>().ensureInstalled()
        }
    }
}
