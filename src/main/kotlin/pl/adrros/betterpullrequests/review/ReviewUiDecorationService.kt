package pl.adrros.betterpullrequests.review

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.adrros.betterpullrequests.copy.CommentCopyButtons
import pl.adrros.betterpullrequests.copy.CommentPanes
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.ContainerEvent
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JEditorPane

private val LOG = logger<ReviewUiDecorationService>()
private const val POPUP_GROUP_ID = "BetterPullRequests.CommentPopup"

/**
 * Owns everything the plugin adds to the GitHub review UI without touching that UI's code: the context menu on
 * comments, the copy button in comment actions and the buttons under comment fields.
 * Being an application service, it is disposed when the plugin unloads, and it then takes its additions back out
 * so a newer plugin version installed without restart starts from a clean UI.
 */
@Service(Service.Level.APP)
class ReviewUiDecorationService : Disposable {
    private val toolkit: Toolkit = Toolkit.getDefaultToolkit()
    private var listener: AWTEventListener? = null
    private val injected = LinkedHashMap<JComponent, JComponent>()
    private val flagged = LinkedHashMap<JComponent, String>()

    @Synchronized
    fun ensureInstalled() {
        if (listener != null) return
        val installed = AWTEventListener { event -> guarded { handle(event) } }
        toolkit.addAWTEventListener(installed, AWTEvent.MOUSE_EVENT_MASK or AWTEvent.CONTAINER_EVENT_MASK)
        listener = installed
        decorateExisting()
    }

    /** Remembers an injected component and the flag set on its host, so both can be undone on unload. */
    fun track(host: JComponent, component: JComponent, flag: String) {
        synchronized(injected) {
            injected[component] = host
            flagged[host] = flag
        }
    }

    fun flag(component: JComponent, flag: String) {
        component.putClientProperty(flag, true)
        synchronized(injected) { flagged[component] = flag }
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

    /** Comments and comment editors built before the plugin was loaded (install without restart). */
    private fun decorateExisting() {
        guarded {
            for (window in Window.getWindows()) {
                UIUtil.uiTraverser(window).forEach { component -> CommentPanes.asCommentPane(component)?.let(CommentCopyButtons::watch) }
            }
            EditorFactory.getInstance().allEditors.forEach(ReviewCommentEditorListener::decorate)
        }
    }

    @Synchronized
    override fun dispose() {
        listener?.let { toolkit.removeAWTEventListener(it) }
        listener = null
        guarded {
            synchronized(injected) {
                for ((component, host) in injected) {
                    host.remove(component)
                    host.revalidate()
                    host.repaint()
                }
                for ((component, flag) in flagged) component.putClientProperty(flag, null)
                injected.clear()
                flagged.clear()
            }
        }
    }

    companion object {
        /** Listener code runs inside AWT event dispatch; an exception there would be reported as an IDE error. */
        fun guarded(block: () -> Unit) {
            try {
                block()
            } catch (e: Exception) {
                LOG.warn("BetterPullRequests UI decoration failed", e)
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
