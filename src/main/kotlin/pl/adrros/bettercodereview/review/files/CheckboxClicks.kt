package pl.adrros.bettercodereview.review.files

import java.awt.Component
import java.awt.Point
import java.awt.event.MouseEvent
import javax.swing.JComponent

/**
 * Lets a tree decoration take over a click on a row's checkbox slot before the platform's own click handler
 * sees it. The events are intercepted at the toolkit level, which runs before any listener on the component:
 * a decision taken on the press consumes the release and the click, and the platform's click listeners skip
 * consumed events. The press itself is left alone, so the row still gets selected. The handler lives in a
 * client property of the tree, so it dies with the tree.
 */
object CheckboxClicks {
    /** What to do with a press at a point: null when the press is not on a slot this decoration handles. */
    class Decision(val consume: Boolean, val onClick: () -> Unit)

    private const val HANDLER = "BetterCodeReview.checkboxClickHandler"

    /** The decided press, waiting for its release: the component, where it was pressed, what to do, and whether it was done. */
    private class Pending(val component: Component, val at: Point, val decision: Decision) {
        var done = false
    }

    private var pending: Pending? = null

    fun register(component: JComponent, handler: (Point) -> Decision?) {
        component.putClientProperty(HANDLER, handler)
    }

    fun unregister(component: JComponent) {
        component.putClientProperty(HANDLER, null)
        if (pending?.component === component) pending = null
    }

    @Suppress("UNCHECKED_CAST")
    private fun handlerOf(component: Component): ((Point) -> Decision?)? =
        (component as? JComponent)?.getClientProperty(HANDLER) as? (Point) -> Decision?

    /** Called for every mouse event on the toolkit; cheap when the component has no handler. */
    fun intercept(e: MouseEvent) {
        if (e.id == MouseEvent.MOUSE_PRESSED) pending = null // any press ends the previous sequence, decided or not
        val handler = handlerOf(e.component) ?: return
        if (e.button != MouseEvent.BUTTON1 || e.isPopupTrigger) return
        when (e.id) {
            MouseEvent.MOUSE_PRESSED -> {
                if (hasModifiers(e)) return
                val decision = handler(e.point) ?: return
                // The second press of a double-click on a slot would make the platform toggle again: swallow it.
                pending = Pending(e.component, e.point, if (e.clickCount == 1) decision else Decision(consume = true) {})
            }
            MouseEvent.MOUSE_RELEASED -> {
                // The platform's click listeners act on the release too, with the same small tolerance for a jittery click.
                val current = pending?.takeIf { it.component === e.component } ?: return
                if (current.at.distance(e.point) > CLICK_SLOP) {
                    pending = null // dragged away: not a click on the slot, and nothing of ours to run
                    return
                }
                if (current.decision.consume) e.consume()
                current.done = true
                current.decision.onClick()
            }
            MouseEvent.MOUSE_CLICKED -> {
                val current = pending?.takeIf { it.component === e.component } ?: return
                pending = null
                if (current.done && current.decision.consume) e.consume()
            }
        }
    }

    private const val CLICK_SLOP = 4.0

    private fun hasModifiers(e: MouseEvent): Boolean =
        e.modifiersEx and (MouseEvent.SHIFT_DOWN_MASK or MouseEvent.CTRL_DOWN_MASK or MouseEvent.META_DOWN_MASK or MouseEvent.ALT_DOWN_MASK) != 0
}
