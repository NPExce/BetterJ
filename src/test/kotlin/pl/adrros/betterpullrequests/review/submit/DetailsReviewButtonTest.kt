package pl.adrros.betterpullrequests.review.submit

import com.intellij.collaboration.ui.HorizontalListPanel
import com.intellij.icons.AllIcons
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBOptionButton
import com.intellij.ui.components.panels.Wrapper
import com.intellij.util.ui.InlineIconButton
import net.miginfocom.swing.MigLayout
import org.jetbrains.plugins.github.pullrequest.data.GHPRIdentifier
import pl.adrros.betterpullrequests.review.DecorationOwner
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

class DetailsReviewButtonTest : BasePlatformTestCase() {
    private fun action(name: String) = object : AbstractAction(name) { override fun actionPerformed(e: java.awt.event.ActionEvent) {} }

    private val rowsWithPullRequest = mutableSetOf<JPanel>()
    private lateinit var originalLookup: (JComponent) -> GHPRIdentifier?

    override fun setUp() {
        super.setUp()
        originalLookup = DetailsReviewButton.pullRequestOf
        // Headless tests get an empty data context, so the lookup is answered from the test's own bookkeeping.
        DetailsReviewButton.pullRequestOf = { component -> if (component in rowsWithPullRequest) GHPRIdentifier("PR_x", 236) else null }
    }

    override fun tearDown() {
        try {
            DetailsReviewButton.pullRequestOf = originalLookup
        } finally {
            super.tearDown()
        }
    }

    /** Mirrors `GHPRDetailsActionsComponentFactory`: the row inside a Wrapper, ending with the "more" button. */
    private fun detailsRow(vararg leading: JComponent, withPullRequest: Boolean = true): JPanel {
        val row = HorizontalListPanel(10).apply {
            leading.forEach { add(it) }
            add(JBOptionButton(action("Squash and Merge…"), arrayOf(action("Merge…"))))
            add(InlineIconButton(AllIcons.Actions.More))
        }
        Wrapper().setContent(row)
        if (withPullRequest) rowsWithPullRequest += row
        return row
    }

    private fun ours(row: JPanel): JButton = row.components.filterIsInstance<DetailsReviewButton.ReviewButton>().single()

    fun `test the review button lands before the more button`() {
        val row = detailsRow()

        assertTrue(DetailsReviewButton.install(row))

        assertEquals(3, row.componentCount)
        assertSame(ours(row), row.getComponent(1))
        assertTrue(row.getComponent(2) is InlineIconButton)
        assertTrue(ours(row).isVisible)
        assertTrue(DetailsReviewButton.install(row))
        assertEquals("installing twice adds nothing", 3, row.componentCount)
    }

    fun `test rows without a GitHub pull request in the context are left alone`() {
        val gitLabLike = detailsRow(withPullRequest = false)
        assertTrue(DetailsReviewButton.isDetailsActionsRow(gitLabLike))
        assertFalse(DetailsReviewButton.install(gitLabLike))
        assertEquals(2, gitLabLike.componentCount)
    }

    fun `test the review button hides while the original submit button is visible, whatever its language`() {
        val original = JButton().apply { text = "レビューの送信 (1)…" }
        val row = detailsRow(original)
        DetailsReviewButton.install(row)

        assertFalse(ours(row).isVisible)
        assertFalse("a hidden button must not take clicks", ours(row).contains(1, 1))
        original.isVisible = false
        assertTrue(ours(row).isVisible)
    }

    fun `test buttons built from a Swing action are not mistaken for the submit button`() {
        val row = detailsRow(JButton(action("Request Review")))
        DetailsReviewButton.install(row)
        assertTrue(ours(row).isVisible)
    }

    fun `test a button left by an unloaded plugin version is replaced`() {
        val row = detailsRow()
        val stale = JButton("Review…")
        row.putClientProperty(DetailsReviewButton.INSTALLED, stale)
        row.add(stale, 1)

        assertTrue(DetailsReviewButton.install(row))

        assertFalse(row.components.contains(stale))
        assertTrue(DecorationOwner.owns(row.getClientProperty(DetailsReviewButton.INSTALLED) as JComponent))
        assertEquals(3, row.componentCount)
    }

    fun `test rows elsewhere are not touched`() {
        val commentRow = JPanel(MigLayout()).apply { add(HorizontalListPanel().apply { add(JButton("Reply")) }) }
        assertFalse(DetailsReviewButton.isDetailsActionsRow(commentRow))
        val noMore = HorizontalListPanel().apply { add(JButton("Reopen")) }
        Wrapper().setContent(noMore)
        assertFalse(DetailsReviewButton.isDetailsActionsRow(noMore))
        val otherIcon = HorizontalListPanel().apply { add(InlineIconButton(AllIcons.Actions.Edit)) }
        Wrapper().setContent(otherIcon)
        assertFalse(DetailsReviewButton.isDetailsActionsRow(otherIcon))
    }
}
