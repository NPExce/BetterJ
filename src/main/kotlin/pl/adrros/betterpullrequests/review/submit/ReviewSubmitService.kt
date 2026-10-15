package pl.adrros.betterpullrequests.review.submit

import com.intellij.collaboration.util.getOrNull
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.plugins.github.pullrequest.action.GHPRActionKeys
import org.jetbrains.plugins.github.pullrequest.data.GHPRIdentifier
import org.jetbrains.plugins.github.pullrequest.ui.GHPRConnectedProjectViewModel
import org.jetbrains.plugins.github.pullrequest.ui.GHPRProjectViewModel
import org.jetbrains.plugins.github.pullrequest.ui.review.GHPRReviewViewModel
import org.jetbrains.plugins.github.pullrequest.ui.review.GHPRSubmitReviewViewModel
import pl.adrros.betterpullrequests.BetterPullRequestsBundle
import java.awt.Component

private val LOG = logger<ReviewSubmitService>()
private const val NOTIFICATION_GROUP = "BetterPullRequests"
private const val LOADING_TIMEOUT_MS = 30_000L

/** Where the review to submit comes from: a review model already at hand, or a pull request to look it up for. */
sealed interface ReviewSubmitTarget {
    class Model(val reviewVm: GHPRReviewViewModel) : ReviewSubmitTarget
    class PullRequest(val connected: GHPRConnectedProjectViewModel, val id: GHPRIdentifier) : ReviewSubmitTarget
}

/**
 * Opens the submit-review palette for a pull request. The GitHub plugin keeps the submit flow behind a
 * pluggable popup handler on its review model; this service plugs [ReviewSubmitPopup] in for one call and
 * hands the previous handler back afterwards.
 */
@Service(Service.Level.PROJECT)
class ReviewSubmitService(private val project: Project, private val cs: CoroutineScope) {
    fun resolve(dataContext: DataContext): ReviewSubmitTarget? {
        dataContext.getData(GHPRReviewViewModel.DATA_KEY)?.let { return ReviewSubmitTarget.Model(it) }
        val connected = dataContext.getData(GHPRActionKeys.PULL_REQUESTS_CONNECTED_PROJECT_VM)
            ?: project.service<GHPRProjectViewModel>().connectedProjectVm.value
            ?: return null
        val id = dataContext.getData(GHPRActionKeys.PULL_REQUEST_ID)
            ?: connected.prOnCurrentBranch.value?.getOrNull()
            ?: return null
        return ReviewSubmitTarget.PullRequest(connected, id)
    }

    fun open(target: ReviewSubmitTarget, anchor: Component?) {
        cs.launch(Dispatchers.EDT) {
            try {
                when (target) {
                    is ReviewSubmitTarget.Model -> submitWith(target.reviewVm, anchor)
                    is ReviewSubmitTarget.PullRequest -> {
                        // The acquired model lives as long as this coroutine, so the popup must finish inside it.
                        val infoVm = target.connected.acquireInfoViewModel(target.id, this)
                        val detailsVm = withTimeoutOrNull(LOADING_TIMEOUT_MS) {
                            infoVm.detailsVm.mapNotNull { it.getOrNull() }.first()
                        }
                        if (detailsVm == null) {
                            notify(BetterPullRequestsBundle.message("review.submit.not.ready"))
                            return@launch
                        }
                        submitWith(detailsVm.reviewFlowVm, anchor)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("Submit review failed to open", e)
                notify(e.localizedMessage ?: e.toString())
            }
        }
    }

    private suspend fun submitWith(reviewVm: GHPRReviewViewModel, anchor: Component?) {
        val ready = withTimeoutOrNull(LOADING_TIMEOUT_MS) { reviewVm.pendingReview.first { !it.isInProgress } }
        if (ready == null) {
            notify(BetterPullRequestsBundle.message("review.submit.not.ready"))
            return
        }
        val finished = CompletableDeferred<Unit>()
        val previous = reviewVm.submitReviewInputHandler
        val handler: suspend (GHPRSubmitReviewViewModel) -> Unit = { submitVm ->
            try {
                withContext(Dispatchers.EDT) {
                    if (anchor != null && anchor.isShowing) ReviewSubmitPopup.show(submitVm, anchor, true)
                    else ReviewSubmitPopup.show(submitVm, project)
                }
            } finally {
                finished.complete(Unit)
            }
        }
        reviewVm.submitReviewInputHandler = handler
        try {
            reviewVm.submitReview()
        } finally {
            if (reviewVm.submitReviewInputHandler === handler) reviewVm.submitReviewInputHandler = previous
        }
        withTimeoutOrNull(LOADING_TIMEOUT_MS) { finished.await() }
    }

    private fun notify(message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(BetterPullRequestsBundle.message("review.submit.failed"), message, NotificationType.WARNING)
            .notify(project)
    }

    companion object {
        fun resolve(project: Project, dataContext: DataContext): ReviewSubmitTarget? = project.service<ReviewSubmitService>().resolve(dataContext)

        fun projectOf(dataContext: DataContext): Project? = dataContext.getData(CommonDataKeys.PROJECT)
    }
}
