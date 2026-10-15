package pl.adrros.betterpullrequests.review.submit

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.plugins.github.pullrequest.action.GHPRActionKeys
import org.jetbrains.plugins.github.pullrequest.data.GHPRIdentifier
import org.jetbrains.plugins.github.pullrequest.ui.GHPRConnectedProjectViewModel
import org.jetbrains.plugins.github.pullrequest.ui.GHPRProjectViewModel
import org.jetbrains.plugins.github.pullrequest.ui.details.model.GHPRDetailsLoadingViewModel
import org.jetbrains.plugins.github.pullrequest.ui.details.model.impl.GHPRDetailsViewModel
import org.jetbrains.plugins.github.pullrequest.ui.review.GHPRReviewViewModel
import org.jetbrains.plugins.github.pullrequest.ui.review.GHPRSubmitReviewViewModel
import pl.adrros.betterpullrequests.BetterPullRequestsBundle
import java.awt.Component
import kotlin.coroutines.coroutineContext

private val LOG = logger<ReviewSubmitService>()
private const val NOTIFICATION_GROUP = "BetterPullRequests"
private const val LOADING_TIMEOUT_MS = 30_000L
private const val TIMELINE_FILE_CLASS = "org.jetbrains.plugins.github.pullrequest.GHPRTimelineVirtualFile"

/** Where the review to submit comes from. Every variant names one concrete pull request, never "the current branch". */
sealed interface ReviewSubmitTarget {
    /** A review model already at hand (the diff viewer provides one). */
    class Model(val reviewVm: GHPRReviewViewModel) : ReviewSubmitTarget

    /** The details the pull request tab already holds; nothing has to be borrowed. */
    class Details(val loading: GHPRDetailsLoadingViewModel) : ReviewSubmitTarget

    /** A pull request to look the details up for, e.g. from its timeline editor. */
    class PullRequest(val connected: GHPRConnectedProjectViewModel, val id: GHPRIdentifier) : ReviewSubmitTarget
}

/**
 * Opens the submit-review palette for a pull request. The GitHub plugin keeps the submit flow behind a
 * pluggable popup handler on its review model; this service plugs [ReviewSubmitPopup] in for one call and
 * hands the previous handler back afterwards.
 */
@Service(Service.Level.PROJECT)
class ReviewSubmitService(private val project: Project, private val cs: CoroutineScope) {
    /** Replaceable in tests, where no popup can be shown. */
    var popup: suspend (GHPRSubmitReviewViewModel, Component?) -> Unit = { vm, anchor ->
        if (anchor != null && anchor.isShowing) ReviewSubmitPopup.show(vm, anchor, true) else ReviewSubmitPopup.show(vm, project)
    }

    private var running: Job? = null

    /** True while a palette is open or being opened. */
    val isBusy: Boolean get() = running?.isActive == true

    fun resolve(dataContext: DataContext): ReviewSubmitTarget? {
        dataContext.getData(GHPRReviewViewModel.DATA_KEY)?.let { return ReviewSubmitTarget.Model(it) }
        dataContext.getData(GHPRDetailsLoadingViewModel.DATA_KEY)?.let { return ReviewSubmitTarget.Details(it) }
        timelineFile(dataContext)?.let { return it }
        val id = dataContext.getData(GHPRActionKeys.PULL_REQUEST_ID) ?: return null
        val connected = dataContext.getData(GHPRActionKeys.PULL_REQUESTS_CONNECTED_PROJECT_VM)
            ?: project.serviceIfCreated<GHPRProjectViewModel>()?.connectedProjectVm?.value
            ?: return null
        return ReviewSubmitTarget.PullRequest(connected, id)
    }

    /**
     * The timeline editor's file names its pull request. The class is Kotlin-internal to the GitHub plugin, so it
     * is read through its public bytecode accessors.
     */
    private fun timelineFile(dataContext: DataContext): ReviewSubmitTarget.PullRequest? {
        val file: VirtualFile = dataContext.getData(CommonDataKeys.VIRTUAL_FILE)
            ?: dataContext.getData(PlatformCoreDataKeys.FILE_EDITOR)?.file
            ?: return null
        if (file.javaClass.name != TIMELINE_FILE_CLASS) return null
        return runCatching {
            val id = file.javaClass.getMethod("getPullRequest").invoke(file) as GHPRIdentifier
            val connected = file.javaClass.getMethod("findProjectVm").invoke(file) as? GHPRConnectedProjectViewModel ?: return null
            ReviewSubmitTarget.PullRequest(connected, id)
        }.getOrNull()
    }

    /** Returns false when a palette is already open. */
    fun open(target: ReviewSubmitTarget, anchor: Component?): Boolean {
        if (isBusy) return false
        running = cs.launch(Dispatchers.EDT) {
            // A borrowed pull request model lives as long as this scope; the popup must close before it is released.
            val borrow = CoroutineScope(coroutineContext + Job(coroutineContext.job))
            try {
                val reviewVm: GHPRReviewViewModel = when (target) {
                    is ReviewSubmitTarget.Model -> target.reviewVm
                    is ReviewSubmitTarget.Details -> awaitDetails(target.loading.detailsVm)?.reviewFlowVm ?: return@launch
                    is ReviewSubmitTarget.PullRequest ->
                        awaitDetails(target.connected.acquireInfoViewModel(target.id, borrow).detailsVm)?.reviewFlowVm ?: return@launch
                }
                submitWith(reviewVm, anchor)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is ControlFlowException) throw e
                LOG.warn("Submit review failed to open", e)
                notify(e.localizedMessage ?: e.toString())
            } finally {
                borrow.cancel()
            }
        }
        return true
    }

    private suspend fun awaitDetails(details: StateFlow<com.intellij.collaboration.util.ComputedResult<GHPRDetailsViewModel>>): GHPRDetailsViewModel? {
        val computed = withTimeoutOrNull(LOADING_TIMEOUT_MS) { details.first { !it.isInProgress } }
        if (computed == null) {
            notify(BetterPullRequestsBundle.message("review.submit.not.ready"))
            return null
        }
        return computed.result!!.getOrThrow()
    }

    suspend fun submitWith(reviewVm: GHPRReviewViewModel, anchor: Component?) {
        val ready = withTimeoutOrNull(LOADING_TIMEOUT_MS) {
            // The pending review may flip back to loading right after it settles (a reload signal); re-check on the EDT.
            while (true) {
                reviewVm.pendingReview.first { !it.isInProgress }
                if (!reviewVm.pendingReview.value.isInProgress) break
            }
        }
        if (ready == null) {
            notify(BetterPullRequestsBundle.message("review.submit.not.ready"))
            return
        }
        val opened = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val handler: suspend (GHPRSubmitReviewViewModel) -> Unit = { submitVm ->
            opened.complete(Unit)
            try {
                withContext(Dispatchers.EDT) { popup(submitVm, anchor) }
            } finally {
                closed.complete(Unit)
            }
        }
        val previous = reviewVm.submitReviewInputHandler
        reviewVm.submitReviewInputHandler = handler
        try {
            reviewVm.submitReview()
        } finally {
            if (reviewVm.submitReviewInputHandler === handler) reviewVm.submitReviewInputHandler = previous
        }
        if (withTimeoutOrNull(LOADING_TIMEOUT_MS) { opened.await() } == null) {
            notify(BetterPullRequestsBundle.message("review.submit.not.ready"))
            return
        }
        closed.await()
    }

    private fun notify(message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(BetterPullRequestsBundle.message("review.submit.failed"), message, NotificationType.WARNING)
            .notify(project)
    }

    companion object {
        fun resolve(project: Project, dataContext: DataContext): ReviewSubmitTarget? = project.service<ReviewSubmitService>().resolve(dataContext)
    }
}
