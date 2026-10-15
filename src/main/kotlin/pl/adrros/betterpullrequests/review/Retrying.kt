package pl.adrros.betterpullrequests.review

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Condition
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import javax.swing.JComponent

/**
 * The GitHub plugin assembles its comment UI in several steps, some of them in coroutines resumed after the
 * component is already on screen. Decorations therefore try when the component becomes displayable and again
 * on later EDT turns until they succeed or give up.
 */
object Retrying {
    private const val ATTEMPTS = 5

    fun whenDisplayable(component: JComponent, attempt: () -> Boolean) {
        if (component.isDisplayable) {
            retryLater(attempt)
            return
        }
        component.addHierarchyListener(object : HierarchyListener {
            override fun hierarchyChanged(e: HierarchyEvent) {
                if (e.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() == 0L || !component.isDisplayable) return
                component.removeHierarchyListener(this)
                retryLater(attempt)
            }
        })
    }

    private fun retryLater(attempt: () -> Boolean, attemptsLeft: Int = ATTEMPTS) {
        if (attemptsLeft == 0) return
        val service = service<ReviewUiDecorationService>()
        ApplicationManager.getApplication().invokeLater({
            ReviewUiDecorationService.guarded {
                if (!attempt()) retryLater(attempt, attemptsLeft - 1)
            }
        }, ModalityState.any(), Condition<Any?> { service.isDisposed })
    }
}
