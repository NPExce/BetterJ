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
    /** The event the platform's own handler acts on; the decision acts on the same one, so a jittery click splits neither way. */
    enum class Trigger { RELEASE, CLICK }

    /** What to do with a press at a point: null when the press is not on a slot this decoration handles. */
    class Decision(val consume: Boolean, val trigger: Trigger = Trigger.RELEASE, val onClick: () -> Unit)

    const val HANDLER = "BetterCodeReview.checkboxClickHandler"

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
                pending = handler(e.point)?.let { Pending(e.component, e.point, it) }
            }
            MouseEvent.MOUSE_RELEASED -> {
                val current = pending?.takeIf { it.component === e.component } ?: return
                if (!withinSlop(current.at, e.point)) {
                    pending = null // dragged away: not a click on the slot, and nothing of ours to run
                    return
                }
                if (current.decision.trigger != Trigger.RELEASE) return
                // The Commit tree's own toggle acts on the release, with the same tolerance for a jittery click.
                if (current.decision.consume) e.consume()
                current.done = true
                current.decision.onClick()
            }
            MouseEvent.MOUSE_CLICKED -> {
                val current = pending?.takeIf { it.component === e.component } ?: return
                pending = null
                when (current.decision.trigger) {
                    Trigger.RELEASE -> if (current.done && current.decision.consume) e.consume()
                    Trigger.CLICK -> {
                        if (current.decision.consume) e.consume()
                        current.decision.onClick()
                    }
                }
            }
        }
    }

    /** The platform's ClickListener tolerance: fewer than four pixels on each axis. */
    private fun withinSlop(pressed: Point, released: Point): Boolean =
        kotlin.math.abs(pressed.x - released.x) < 4 && kotlin.math.abs(pressed.y - released.y) < 4

    private fun hasModifiers(e: MouseEvent): Boolean =
        e.modifiersEx and (MouseEvent.SHIFT_DOWN_MASK or MouseEvent.CTRL_DOWN_MASK or MouseEvent.META_DOWN_MASK or MouseEvent.ALT_DOWN_MASK) != 0
}
