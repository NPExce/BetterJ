package pl.adrros.bettercodereview.review.files

import java.awt.Component
import java.awt.Point
import java.awt.event.MouseEvent
import java.util.WeakHashMap

/**
 * Lets a tree decoration take over a click on a row's checkbox slot before the platform's own click handler
 * sees it. The events are intercepted at the toolkit level, which runs before any listener on the component:
 * a decision taken on the press consumes the release and the click, and the platform's click listeners skip
 * consumed events. The press itself is left alone, so the row still gets selected.
 */
object CheckboxClicks {
    /** What to do with a press at a point: null when the press is not on a slot this decoration handles. */
    class Decision(val consume: Boolean, val onClick: () -> Unit)

    private val handlers = WeakHashMap<Component, (Point) -> Decision?>()
    private var pending: Pair<Component, Decision>? = null

    fun register(component: Component, handler: (Point) -> Decision?) {
        synchronized(handlers) { handlers[component] = handler }
    }

    fun unregister(component: Component) {
        synchronized(handlers) { handlers.remove(component) }
        if (pending?.first === component) pending = null
    }

    /** Called for every mouse event on the toolkit; cheap when the component has no handler. */
    fun intercept(e: MouseEvent) {
        val handler = synchronized(handlers) { handlers[e.component] } ?: return
        when (e.id) {
            MouseEvent.MOUSE_PRESSED -> {
                pending = null
                if (e.button != MouseEvent.BUTTON1 || e.isPopupTrigger || e.clickCount != 1 || hasModifiers(e)) return
                pending = handler(e.point)?.let { e.component to it }
            }
            MouseEvent.MOUSE_RELEASED -> {
                val decision = pending?.takeIf { it.first === e.component }?.second ?: return
                if (decision.consume) e.consume()
            }
            MouseEvent.MOUSE_CLICKED -> {
                val decision = pending?.takeIf { it.first === e.component }?.second ?: return
                pending = null
                if (decision.consume) e.consume()
                decision.onClick()
            }
        }
    }

    private fun hasModifiers(e: MouseEvent): Boolean =
        e.modifiersEx and (MouseEvent.SHIFT_DOWN_MASK or MouseEvent.CTRL_DOWN_MASK or MouseEvent.META_DOWN_MASK or MouseEvent.ALT_DOWN_MASK) != 0
}
