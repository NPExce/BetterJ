package pl.adrros.bettercodereview.review.submit

import com.intellij.collaboration.ui.HorizontalListPanel
import com.intellij.collaboration.ui.codereview.list.error.ErrorStatusPresenter
import com.intellij.collaboration.ui.codereview.review.CodeReviewSubmitPopupHandler
import com.intellij.collaboration.ui.util.bindDisabledIn
import com.intellij.collaboration.ui.util.bindVisibilityIn
import com.intellij.icons.AllIcons
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.util.ui.InlineIconButton
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.jetbrains.plugins.github.api.data.GHPullRequestReviewEvent
import org.jetbrains.plugins.github.pullrequest.ui.review.GHPRSubmitReviewViewModel
import pl.adrros.bettercodereview.BetterCodeReviewBundle
import java.awt.event.ActionListener
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel

/**
 * GitHub's "Review changes" palette inside the IDE: a text field for the summary and the three verdicts.
 * Approve and Request Changes are withheld from the pull request's author, as on github.com.
 */
object ReviewSubmitPopup : CodeReviewSubmitPopupHandler<GHPRSubmitReviewViewModel>() {
    override fun CoroutineScope.createActionsComponent(vm: GHPRSubmitReviewViewModel): JComponent = buttonsIn(this, vm)

    fun buttonsIn(cs: CoroutineScope, vm: GHPRSubmitReviewViewModel): JComponent {
        val hasAnyComment = combine(vm.text, vm.draftCommentsCount) { text, comments -> text.isNotBlank() || comments > 0 }
        val busyOrEmpty = vm.isBusy.combine(hasAnyComment) { busy, hasComment -> busy || !hasComment }
        return HorizontalListPanel(ACTIONS_GAP).apply {
            if (vm.viewerIsAuthor) {
                add(JLabel(BetterCodeReviewBundle.message("review.submit.author.hint")).apply {
                    foreground = UIUtil.getContextHelpForeground()
                })
            } else {
                add(JButton(BetterCodeReviewBundle.message("review.submit.approve")).apply {
                    isOpaque = false
                    putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
                    bindDisabledIn(cs, vm.isBusy)
                    addActionListener { vm.submit(GHPullRequestReviewEvent.APPROVE) }
                })
                add(JButton(BetterCodeReviewBundle.message("review.submit.request.changes")).apply {
                    isOpaque = false
                    toolTipText = BetterCodeReviewBundle.message("review.submit.needs.text")
                    bindDisabledIn(cs, busyOrEmpty)
                    addActionListener { vm.submit(GHPullRequestReviewEvent.REQUEST_CHANGES) }
                })
            }
            add(JButton(BetterCodeReviewBundle.message("review.submit.comment")).apply {
                isOpaque = false
                toolTipText = BetterCodeReviewBundle.message("review.submit.comment.tooltip")
                bindDisabledIn(cs, busyOrEmpty)
                addActionListener { vm.submit(GHPullRequestReviewEvent.COMMENT) }
            })
        }
    }

    override fun createTitleActionsComponentIn(cs: CoroutineScope, vm: GHPRSubmitReviewViewModel): JComponent =
        titleActionsIn(cs, vm, super.createTitleActionsComponentIn(cs, vm))

    fun titleActionsIn(cs: CoroutineScope, vm: GHPRSubmitReviewViewModel, defaultActions: JComponent): JComponent {
        if (!vm.hasPendingReview) return defaultActions
        val discard = InlineIconButton(AllIcons.Actions.GC).apply {
            toolTipText = BetterCodeReviewBundle.message("review.submit.discard")
            border = JBUI.Borders.empty(5)
            bindDisabledIn(cs, vm.isBusy)
            bindVisibilityIn(cs, vm.draftCommentsCount.map { it > 0 })
        }
        discard.actionListener = ActionListener {
            val confirmed = MessageDialogBuilder.yesNo(
                BetterCodeReviewBundle.message("review.submit.discard.title"),
                BetterCodeReviewBundle.message("review.submit.discard.message"),
            ).ask(discard)
            if (confirmed) vm.discard()
        }
        return HorizontalListPanel(TITLE_ACTIONS_GAP).apply {
            add(discard)
            add(defaultActions)
        }
    }

    override val errorPresenter: ErrorStatusPresenter<Throwable> =
        ErrorStatusPresenter.simple(BetterCodeReviewBundle.message("review.submit.failed"), descriptionProvider = { it.localizedMessage })
}
