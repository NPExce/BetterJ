package pl.adrros.bettercodereview.review.files

import org.jetbrains.plugins.github.api.data.pullrequest.GHPullRequestFileViewedState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.awt.event.MouseEvent
import java.awt.event.MouseListener
import javax.swing.JPanel

class ViewedStateCycleTest {
    @Test
    fun `ticked goes to the dash, the dash goes to unticked, unticked goes to the platform`() {
        assertEquals(ViewedStateCycle.Step.FLAG_AND_UNTICK, ViewedStateCycle.step(GHPullRequestFileViewedState.VIEWED, flagged = false))
        assertEquals(ViewedStateCycle.Step.CLEAR_TO_UNTICKED, ViewedStateCycle.step(GHPullRequestFileViewedState.UNVIEWED, flagged = true))
        assertEquals(ViewedStateCycle.Step.CLEAR_TO_UNTICKED, ViewedStateCycle.step(GHPullRequestFileViewedState.VIEWED, flagged = true))
        assertEquals(ViewedStateCycle.Step.CLEAR_TO_UNTICKED, ViewedStateCycle.step(GHPullRequestFileViewedState.DISMISSED, flagged = false))
        assertEquals(ViewedStateCycle.Step.NATIVE, ViewedStateCycle.step(GHPullRequestFileViewedState.UNVIEWED, flagged = false))
        assertEquals(ViewedStateCycle.Step.NATIVE, ViewedStateCycle.step(null, flagged = false))
    }

    @Test
    fun `the rewrapped platform listener skips consumed clicks only`() {
        val seen = mutableListOf<String>()
        val delegate = object : MouseListener {
            override fun mouseClicked(e: MouseEvent) { seen += "clicked" }
            override fun mousePressed(e: MouseEvent) { seen += "pressed" }
            override fun mouseReleased(e: MouseEvent) { seen += "released" }
            override fun mouseEntered(e: MouseEvent) { seen += "entered" }
            override fun mouseExited(e: MouseEvent) { seen += "exited" }
        }
        val wrapped = ViewedStateCycle.ConsumedAwareListener(delegate)
        val panel = JPanel()
        fun event() = MouseEvent(panel, MouseEvent.MOUSE_CLICKED, 0, 0, 1, 1, 1, false, MouseEvent.BUTTON1)
        wrapped.mouseClicked(event())
        wrapped.mouseClicked(event().apply { consume() })
        wrapped.mouseEntered(event().apply { consume() })
        assertEquals(listOf("clicked", "entered"), seen)
    }
}
