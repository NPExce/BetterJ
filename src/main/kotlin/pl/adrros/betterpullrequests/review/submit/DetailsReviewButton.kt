package pl.adrros.betterpullrequests.review.submit

import com.intellij.ide.DataManager
import com.intellij.openapi.components.service
import com.intellij.ui.components.panels.ListLayout
import com.intellij.ui.components.panels.Wrapper
import com.intellij.util.ui.InlineIconButton
import pl.adrros.betterpullrequests.BetterPullRequestsBundle
import pl.adrros.betterpullrequests.review.Retrying
import java.awt.Component
import javax.swing.JButton
import javax.swing.JPanel

/**
 * A "Review…" button next to the merge button at the bottom of the pull request details. The GitHub plugin's
 * own "Submit Review" button only shows for reviewers while the pull request still needs a review; this one is
 * always there and steps aside when the original is visible.
 */
object DetailsReviewButton {
    const val INSTALLED = "BetterPullRequests.reviewButton"

    fun watch(row: JPanel) {
        Retrying.whenDisplayable(row) { install(row) }
    }

    /** The bottom actions row: a horizontal list panel inside a [Wrapper], ending with the "more" icon button. */
    fun isDetailsActionsRow(component: Component): Boolean =
        component is JPanel &&
            component.layout is ListLayout &&
            component.parent is Wrapper &&
            component.components.any { it is InlineIconButton }

    fun install(row: JPanel): Boolean {
        if (!isDetailsActionsRow(row)) return false
        if (row.getClientProperty(INSTALLED) != null) return true
        val button = ReviewButton(row)
        row.putClientProperty(INSTALLED, button)
        val moreIndex = row.components.indexOfFirst { it is InlineIconButton }
        row.add(button, if (moreIndex >= 0) moreIndex else row.componentCount)
        row.revalidate()
        row.repaint()
        return true
    }

    private class ReviewButton(private val row: JPanel) : JButton(BetterPullRequestsBundle.message("review.button.text")) {
        init {
            isOpaque = false
            toolTipText = BetterPullRequestsBundle.message("action.BetterPullRequests.SubmitReview.description")
            addActionListener { open() }
        }

        /** Hidden while the GitHub plugin shows its own submit button in the same row. */
        override fun isVisible(): Boolean = super.isVisible() && !originalSubmitButtonVisible()

        private fun originalSubmitButtonVisible(): Boolean =
            row.components.any { it !== this && it is JButton && it.javaClass == JButton::class.java && it.isVisible && it.text.orEmpty().contains(SUBMIT_MARKER, ignoreCase = true) }

        private fun open() {
            val context = DataManager.getInstance().getDataContext(this)
            val project = ReviewSubmitService.projectOf(context) ?: return
            val target = ReviewSubmitService.resolve(project, context) ?: return
            project.service<ReviewSubmitService>().open(target, this)
        }
    }

    private const val SUBMIT_MARKER = "Submit Review"
}
