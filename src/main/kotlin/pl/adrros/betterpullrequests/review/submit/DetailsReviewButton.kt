package pl.adrros.betterpullrequests.review.submit

import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.ui.components.panels.ListLayout
import com.intellij.ui.components.panels.Wrapper
import com.intellij.util.ui.InlineIconButton
import org.jetbrains.plugins.github.pullrequest.action.GHPRActionKeys
import org.jetbrains.plugins.github.pullrequest.data.GHPRIdentifier
import pl.adrros.betterpullrequests.BetterPullRequestsBundle
import pl.adrros.betterpullrequests.review.DecorationOwner
import pl.adrros.betterpullrequests.review.Retrying
import java.awt.Component
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * A "Review…" button next to the merge button at the bottom of the pull request details. The GitHub plugin's
 * own "Submit Review" button only shows for reviewers while the pull request still needs a review; this one is
 * always there and steps aside while the original is visible.
 */
object DetailsReviewButton {
    const val INSTALLED = "BetterPullRequests.reviewButton"

    /** The pull request the row belongs to, read from the data providers above it. Replaceable in headless tests. */
    var pullRequestOf: (JComponent) -> GHPRIdentifier? = { component ->
        DataManager.getInstance().getDataContext(component).getData(GHPRActionKeys.PULL_REQUEST_ID)
    }

    fun watch(row: JPanel) {
        (row.getClientProperty(INSTALLED) as? JComponent)?.let { if (DecorationOwner.owns(it)) return }
        Retrying.whenDisplayable(row) { install(row) }
    }

    /**
     * The bottom actions row: a horizontal list panel inside a [Wrapper], ending with the "more" icon button.
     * GitLab merge request details share this shape, which is why [install] also insists on a GitHub pull request
     * in the data context.
     */
    fun isDetailsActionsRow(component: Component): Boolean =
        component is JPanel &&
            component.layout is ListLayout &&
            component.parent is Wrapper &&
            component.components.lastOrNull { !isOurs(it) }.isMoreButton()

    private fun isOurs(component: Component): Boolean = component is JComponent && DecorationOwner.owns(component)

    private fun Component?.isMoreButton(): Boolean = this is InlineIconButton && icon === AllIcons.Actions.More

    fun install(row: JPanel): Boolean {
        if (!isDetailsActionsRow(row)) return false
        when (val existing = row.getClientProperty(INSTALLED)) {
            is JComponent -> if (DecorationOwner.owns(existing)) return true else row.remove(existing)
        }
        if (pullRequestOf(row) == null) return false
        val button = ReviewButton(row)
        DecorationOwner.mark(button)
        row.putClientProperty(INSTALLED, button)
        val moreIndex = row.components.indexOfFirst { it.isMoreButton() }
        row.add(button, if (moreIndex >= 0) moreIndex else row.componentCount)
        row.revalidate()
        row.repaint()
        return true
    }

    class ReviewButton(private val row: JPanel) : JButton(BetterPullRequestsBundle.message("review.button.text")) {
        init {
            isOpaque = false
            toolTipText = BetterPullRequestsBundle.message("action.BetterPullRequests.SubmitReview.description")
            addActionListener { open() }
        }

        /** Hidden while the GitHub plugin shows its own submit button in the same row. */
        override fun isVisible(): Boolean = super.isVisible() && originalSubmitButton()?.isVisible != true

        /** An invisible component keeps its old bounds; make sure it cannot take the clicks meant for its neighbours. */
        override fun contains(x: Int, y: Int): Boolean = isVisible && super.contains(x, y)

        /**
         * The bundled submit button is the only plain `JButton` in the row built without a Swing `Action`
         * (its text is bound to the pending comment count); request-review and reviewer buttons all carry one.
         * Recognising it by structure keeps this working with every language pack.
         */
        private fun originalSubmitButton(): JButton? =
            row.components.firstOrNull { it !== this && it is JButton && it.javaClass == JButton::class.java && it.action == null } as JButton?

        override fun toString(): String = "BetterPullRequests ReviewButton"

        private fun open() {
            val context = DataManager.getInstance().getDataContext(this)
            val project = context.getData(CommonDataKeys.PROJECT) ?: return
            val target = ReviewSubmitService.resolve(project, context) ?: return
            project.service<ReviewSubmitService>().open(target, this)
        }
    }
}
