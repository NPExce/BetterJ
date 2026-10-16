package pl.adrros.bettercodereview.review.files

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Point
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

    private fun event(id: Int, component: java.awt.Component = tree, modifiers: Int = 0, button: Int = MouseEvent.BUTTON1, clickCount: Int = 1) =
        MouseEvent(component, id, 0, modifiers, 5, 5, clickCount, false, button)

    private fun press(vararg events: MouseEvent) = events.map { CheckboxClicks.intercept(it); it.isConsumed }

    @Test
    fun `a consuming decision eats the release and the click and runs once`() {
        CheckboxClicks.register(tree) { CheckboxClicks.Decision(consume = true) { clicks++ } }
        val consumed = press(event(MouseEvent.MOUSE_PRESSED), event(MouseEvent.MOUSE_RELEASED), event(MouseEvent.MOUSE_CLICKED))
        assertEquals(listOf(false, true, true), consumed)
        assertEquals(1, clicks)
        // The next sequence starts afresh: a released without a press is ignored.
        assertEquals(listOf(false), press(event(MouseEvent.MOUSE_RELEASED)))
    }

    @Test
    fun `a non-consuming decision runs its action but leaves the events to the platform`() {
        CheckboxClicks.register(tree) { CheckboxClicks.Decision(consume = false) { clicks++ } }
        val consumed = press(event(MouseEvent.MOUSE_PRESSED), event(MouseEvent.MOUSE_RELEASED), event(MouseEvent.MOUSE_CLICKED))
        assertEquals(listOf(false, false, false), consumed)
        assertEquals(1, clicks)
    }

    @Test
    fun `no decision, another component, modifiers, the right button and double-clicks are left alone`() {
        var asked = 0
        CheckboxClicks.register(tree) { asked++; null }
        assertEquals(listOf(false, false, false), press(event(MouseEvent.MOUSE_PRESSED), event(MouseEvent.MOUSE_RELEASED), event(MouseEvent.MOUSE_CLICKED)))
        assertEquals(1, asked)
        press(event(MouseEvent.MOUSE_PRESSED, other), event(MouseEvent.MOUSE_PRESSED, modifiers = MouseEvent.SHIFT_DOWN_MASK), event(MouseEvent.MOUSE_PRESSED, button = MouseEvent.BUTTON3), event(MouseEvent.MOUSE_PRESSED, clickCount = 2))
        assertEquals("only the plain left press asked the handler", 1, asked)
    }

    @Test
    fun `unregistering drops a pending decision`() {
        CheckboxClicks.register(tree) { CheckboxClicks.Decision(consume = true) { clicks++ } }
        press(event(MouseEvent.MOUSE_PRESSED))
        CheckboxClicks.unregister(tree)
        val click = event(MouseEvent.MOUSE_CLICKED)
        CheckboxClicks.intercept(click)
        assertFalse(click.isConsumed)
        assertEquals(0, clicks)
        assertTrue(true)
    }
}
