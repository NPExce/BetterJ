package pl.adrros.ghreviewextras.copy

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import javax.swing.JEditorPane

private const val POPUP_GROUP_ID = "GhReviewExtras.CommentPopup"

/**
 * Review comment panes have no context menu of their own. A single application-wide mouse listener
 * adds one without touching the components the GitHub plugin builds.
 */
@Service(Service.Level.APP)
class CommentPanePopupService : Disposable {
    private val toolkit: Toolkit = Toolkit.getDefaultToolkit()
    private var listener: AWTEventListener? = null

    @Synchronized
    fun ensureInstalled() {
        if (listener != null) return
        val installed = AWTEventListener { event -> showPopupIfTriggered(event) }
        toolkit.addAWTEventListener(installed, AWTEvent.MOUSE_EVENT_MASK)
        listener = installed
    }

    private fun showPopupIfTriggered(event: AWTEvent) {
        if (event !is MouseEvent || !event.isPopupTrigger) return
        val pane = CommentPanes.asCommentPane(event.component) ?: return
        showPopup(pane, event)
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
        com.intellij.openapi.components.service<CommentPanePopupService>().ensureInstalled()
    }
}
