package pl.adrros.bettercodereview.review.files

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.awt.Component
import java.awt.event.MouseEvent
import javax.swing.JPanel

class CheckboxClicksTest {
    private val tree = JPanel()
    private val other = JPanel()
    private var clicks = 0

    @After
    fun tearDown() {
        CheckboxClicks.unregister(tree)
        CheckboxClicks.unregister(other)
    }

    private fun event(id: Int, component: Component = tree, modifiers: Int = 0, button: Int = MouseEvent.BUTTON1, clickCount: Int = 1, x: Int = 5, y: Int = 5, popup: Boolean = false) =
        MouseEvent(component, id, 0, modifiers, x, y, clickCount, popup, button)

    private fun send(vararg events: MouseEvent) = events.map { CheckboxClicks.intercept(it); it.isConsumed }

    @Test
    fun `a consuming decision runs on the release and eats the release and the click`() {
        CheckboxClicks.register(tree) { CheckboxClicks.Decision(consume = true) { clicks++ } }
        val pressed = event(MouseEvent.MOUSE_PRESSED)
        CheckboxClicks.intercept(pressed)
        assertFalse("the press stays with the tree, so the row gets selected", pressed.isConsumed)
        val released = event(MouseEvent.MOUSE_RELEASED)
        CheckboxClicks.intercept(released)
        assertEquals("the action ran on the release, like the platform's own toggle", 1, clicks)
        assertEquals(listOf(true), listOf(released.isConsumed))
        assertEquals(listOf(true), send(event(MouseEvent.MOUSE_CLICKED)))
        assertEquals(1, clicks)
        assertEquals("a release without a press is ignored", listOf(false), send(event(MouseEvent.MOUSE_RELEASED)))
    }

    @Test
    fun `a non-consuming decision runs its action but leaves the events to the platform`() {
        CheckboxClicks.register(tree) { CheckboxClicks.Decision(consume = false) { clicks++ } }
        assertEquals(listOf(false, false, false), send(event(MouseEvent.MOUSE_PRESSED), event(MouseEvent.MOUSE_RELEASED), event(MouseEvent.MOUSE_CLICKED)))
        assertEquals(1, clicks)
    }

    @Test
    fun `a release away from the press is a drag, not a click`() {
        CheckboxClicks.register(tree) { CheckboxClicks.Decision(consume = true) { clicks++ } }
        assertEquals(listOf(false, false), send(event(MouseEvent.MOUSE_PRESSED), event(MouseEvent.MOUSE_RELEASED, x = 30, y = 5)))
        assertEquals(0, clicks)
        assertEquals("nothing left over for a later click", listOf(false), send(event(MouseEvent.MOUSE_CLICKED)))
    }

    @Test
    fun `no decision, another component, modifiers and the right button are left alone`() {
        var asked = 0
        CheckboxClicks.register(tree) { asked++; null }
        assertEquals(listOf(false, false, false), send(event(MouseEvent.MOUSE_PRESSED), event(MouseEvent.MOUSE_RELEASED), event(MouseEvent.MOUSE_CLICKED)))
        assertEquals(1, asked)
        send(event(MouseEvent.MOUSE_PRESSED, other), event(MouseEvent.MOUSE_PRESSED, modifiers = MouseEvent.SHIFT_DOWN_MASK), event(MouseEvent.MOUSE_PRESSED, button = MouseEvent.BUTTON3), event(MouseEvent.MOUSE_PRESSED, popup = true, button = MouseEvent.BUTTON3))
        assertEquals("only the plain left press asked the handler", 1, asked)
    }

    @Test
    fun `the second press of a double-click on a slot is swallowed instead of toggling again`() {
        CheckboxClicks.register(tree) { CheckboxClicks.Decision(consume = false) { clicks++ } }
        send(event(MouseEvent.MOUSE_PRESSED), event(MouseEvent.MOUSE_RELEASED), event(MouseEvent.MOUSE_CLICKED))
        assertEquals(listOf(false, true, true), send(event(MouseEvent.MOUSE_PRESSED, clickCount = 2), event(MouseEvent.MOUSE_RELEASED, clickCount = 2), event(MouseEvent.MOUSE_CLICKED, clickCount = 2)))
        assertEquals("the first click's action ran once", 1, clicks)
    }

    @Test
    fun `a press that never got its release does not haunt a later right-click`() {
        CheckboxClicks.register(tree) { CheckboxClicks.Decision(consume = true) { clicks++ } }
        send(event(MouseEvent.MOUSE_PRESSED))
        // macOS: the right press is the popup trigger; it must still end the pending sequence.
        assertEquals(listOf(false, false, false), send(event(MouseEvent.MOUSE_PRESSED, button = MouseEvent.BUTTON3, popup = true), event(MouseEvent.MOUSE_RELEASED, button = MouseEvent.BUTTON3), event(MouseEvent.MOUSE_CLICKED, button = MouseEvent.BUTTON3)))
        assertEquals(0, clicks)
    }

    @Test
    fun `unregistering drops a pending decision`() {
        CheckboxClicks.register(tree) { CheckboxClicks.Decision(consume = true) { clicks++ } }
        send(event(MouseEvent.MOUSE_PRESSED))
        CheckboxClicks.unregister(tree)
        assertEquals(listOf(false), send(event(MouseEvent.MOUSE_RELEASED)))
        assertEquals(0, clicks)
    }
}
