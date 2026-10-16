package pl.adrros.bettercodereview.review.submit

import com.intellij.collaboration.util.ComputedResult
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.components.service
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.plugins.github.api.data.GHPullRequestReviewEvent
import org.jetbrains.plugins.github.pullrequest.data.GHPullRequestPendingReview
import org.jetbrains.plugins.github.pullrequest.ui.review.GHPRReviewViewModel
import org.jetbrains.plugins.github.pullrequest.ui.review.GHPRSubmitReviewViewModel
import java.awt.Component
import kotlinx.coroutines.launch

class ReviewSubmitServiceTest : BasePlatformTestCase() {
    /** Stands in for the GitHub plugin's review model: `submitReview()` hands a submit model to the current handler. */
    private class FakeReviewVm : GHPRReviewViewModel {
        override val pendingReview = MutableStateFlow<ComputedResult<GHPullRequestPendingReview?>>(ComputedResult.success(null))
        override var submitReviewInputHandler: (suspend (GHPRSubmitReviewViewModel) -> Unit)? = null
        var handlerUsed: (suspend (GHPRSubmitReviewViewModel) -> Unit)? = null
        var pendingHandlerRun: (suspend () -> Unit)? = null

        override fun submitReview() {
            val handler = checkNotNull(submitReviewInputHandler)
            handlerUsed = handler
            pendingHandlerRun = { handler(FakeSubmitVm()) }
        }
    }

    private class FakeSubmitVm : GHPRSubmitReviewViewModel {
        override val viewerIsAuthor = false
        override val hasPendingReview = false
        override val draftCommentsCount: StateFlow<Int> = MutableStateFlow(0)
        override val text = MutableStateFlow("")
        override val isBusy: StateFlow<Boolean> = MutableStateFlow(false)
        override val error: StateFlow<Throwable?> = MutableStateFlow(null)
        override fun submit(event: GHPullRequestReviewEvent) {}
        override fun discard() {}
        override fun cancel() {}
    }

    private fun pump(what: String, until: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!until() && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(10)
        }
        assertTrue("timed out waiting for $what", until())
    }

    fun `test the popup is shown through the review model's handler, which is then restored`() {
        val service = project.service<ReviewSubmitService>()
        val shown = mutableListOf<Component?>()
        service.popup = { _, anchor -> shown += anchor }
        val reviewVm = FakeReviewVm()
        val previous: suspend (GHPRSubmitReviewViewModel) -> Unit = {}
        reviewVm.submitReviewInputHandler = previous

        assertTrue(service.open(ReviewSubmitTarget.Model(reviewVm), null))
        pump("handler to be used") { reviewVm.handlerUsed != null }

        assertSame("the bundled handler is back in place right after submitReview()", previous, reviewVm.submitReviewInputHandler)
        assertFalse("a second palette is refused while the first one is open", service.open(ReviewSubmitTarget.Model(reviewVm), null))

        // The GitHub plugin runs the handler in its own coroutine; a background one stands in for it here.
        val handlerRun = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch { reviewVm.pendingHandlerRun!!.invoke() }
        pump("popup shown") { shown.size == 1 && handlerRun.isCompleted }
        assertEquals(listOf<Component?>(null), shown)
        pump("service free again") { !service.isBusy }
    }

    fun `test resolve prefers a review model in the context and finds nothing without a pull request`() {
        val reviewVm = FakeReviewVm()
        val withModel = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(GHPRReviewViewModel.DATA_KEY, reviewVm).build()
        val target = ReviewSubmitService.resolve(project, withModel)
        assertTrue(target is ReviewSubmitTarget.Model && target.reviewVm === reviewVm)

        val action = SubmitReviewAction()
        val enabled = TestActionEvent.createTestEvent(action, withModel)
        action.update(enabled)
        assertTrue(enabled.presentation.isEnabledAndVisible)

        val bare = TestActionEvent.createTestEvent(action, SimpleDataContext.getProjectContext(project))
        action.update(bare)
        assertFalse(bare.presentation.isEnabledAndVisible)
    }

    fun `test the action sits in the GitHub context menus`() {
        val actionManager = ActionManager.getInstance()
        val action = actionManager.getAction("BetterCodeReview.SubmitReview")
        assertNotNull(action)
        assertEquals("Submit Review…", action.templatePresentation.text)
        for (group in listOf("Github.PullRequest.Details.Popup", "Github.PullRequest.Changes.Popup", "Github.PullRequest.Timeline.Popup")) {
            val children = (actionManager.getAction(group) as ActionGroup).getChildren(null)
            assertTrue(group, children.any { it is SubmitReviewAction })
        }
    }
}
