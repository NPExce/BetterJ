package pl.adrros.betterpullrequests.review.submit

import com.intellij.collaboration.ui.HorizontalListPanel
import com.intellij.icons.AllIcons
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBOptionButton
import com.intellij.ui.components.panels.Wrapper
import com.intellij.util.ui.InlineIconButton
import net.miginfocom.swing.MigLayout
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JPanel

class DetailsReviewButtonTest : BasePlatformTestCase() {
    private fun action(name: String) = object : AbstractAction(name) { override fun actionPerformed(e: java.awt.event.ActionEvent) {} }

    /** Mirrors `GHPRDetailsActionsComponentFactory.createActionsForReviewer`: submit, merge option button, "more". */
    private fun detailsRow(withSubmit: Boolean): JPanel {
        val row = HorizontalListPanel(10).apply {
            if (withSubmit) add(JButton("Submit Review (1)…"))
            add(JBOptionButton(action("Squash and Merge…"), arrayOf(action("Merge…"))))
            add(InlineIconButton(AllIcons.Actions.More))
        }
        Wrapper().setContent(row)
        return row
    }

    fun `test the review button lands before the more button`() {
        val row = detailsRow(withSubmit = false)

        assertTrue(DetailsReviewButton.install(row))

        assertEquals(3, row.componentCount)
        assertTrue(row.getComponent(1) is JButton)
        assertEquals("Review…", (row.getComponent(1) as JButton).text)
        assertTrue(row.getComponent(2) is InlineIconButton)
        assertTrue(row.getComponent(1).isVisible)
        assertTrue(DetailsReviewButton.install(row))
        assertEquals("installing twice adds nothing", 3, row.componentCount)
    }

    fun `test the review button hides while the original submit button is visible`() {
        val row = detailsRow(withSubmit = true)
        DetailsReviewButton.install(row)
        val ours = row.components.filterIsInstance<JButton>().first { it.text == "Review…" }
        val original = row.components.filterIsInstance<JButton>().first { it.text.startsWith("Submit Review") }

        assertFalse(ours.isVisible)
        original.isVisible = false
        assertTrue(ours.isVisible)
    }

    fun `test rows elsewhere are not touched`() {
        val commentRow = JPanel(MigLayout()).apply { add(HorizontalListPanel().apply { add(JButton("Reply")) }) }
        assertFalse(DetailsReviewButton.isDetailsActionsRow(commentRow))
        val noMore = HorizontalListPanel().apply { add(JButton("Reopen")) }
        Wrapper().setContent(noMore)
        assertFalse(DetailsReviewButton.isDetailsActionsRow(noMore))
    }
}
