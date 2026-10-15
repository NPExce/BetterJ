package pl.adrros.betterpullrequests.review.submit

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.plugins.github.api.data.GHPullRequestReviewEvent
import org.jetbrains.plugins.github.pullrequest.ui.review.GHPRSubmitReviewViewModel
import javax.swing.JButton
import javax.swing.JLabel

class ReviewSubmitPopupTest : BasePlatformTestCase() {
    private class FakeSubmitVm(override val viewerIsAuthor: Boolean, comments: Int = 0) : GHPRSubmitReviewViewModel {
        override val hasPendingReview: Boolean = comments > 0
        override val draftCommentsCount: StateFlow<Int> = MutableStateFlow(comments)
        override val text: MutableStateFlow<String> = MutableStateFlow("")
        override val isBusy: StateFlow<Boolean> = MutableStateFlow(false)
        override val error: StateFlow<Throwable?> = MutableStateFlow(null)
        val submitted = mutableListOf<GHPullRequestReviewEvent>()
        override fun submit(event: GHPullRequestReviewEvent) { submitted += event }
        override fun discard() {}
        override fun cancel() {}
    }

    private val cs = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    override fun tearDown() {
        try {
            cs.cancel()
        } finally {
            super.tearDown()
        }
    }

    private fun buttons(vm: GHPRSubmitReviewViewModel): Map<String, JButton> =
        ReviewSubmitPopup.buttonsIn(cs, vm).components.filterIsInstance<JButton>().associateBy { it.text }

    fun `test a reviewer gets approve, request changes and comment`() {
        val vm = FakeSubmitVm(viewerIsAuthor = false)
        val buttons = buttons(vm)

        assertEquals(setOf("Approve", "Request Changes", "Comment"), buttons.keys)
        assertTrue(buttons.getValue("Approve").isEnabled)
        assertFalse("needs text or comments", buttons.getValue("Request Changes").isEnabled)
        assertFalse("needs text or comments", buttons.getValue("Comment").isEnabled)

        vm.text.value = "please rename it"
        assertTrue(buttons.getValue("Request Changes").isEnabled)
        assertTrue(buttons.getValue("Comment").isEnabled)

        buttons.getValue("Approve").doClick()
        buttons.getValue("Request Changes").doClick()
        assertEquals(listOf(GHPullRequestReviewEvent.APPROVE, GHPullRequestReviewEvent.REQUEST_CHANGES), vm.submitted)
    }

    fun `test pending comments alone enable request changes and comment`() {
        val buttons = buttons(FakeSubmitVm(viewerIsAuthor = false, comments = 2))
        assertTrue(buttons.getValue("Request Changes").isEnabled)
        assertTrue(buttons.getValue("Comment").isEnabled)
    }

    fun `test the author can only comment`() {
        val panel = ReviewSubmitPopup.buttonsIn(cs, FakeSubmitVm(viewerIsAuthor = true))
        val buttons = panel.components.filterIsInstance<JButton>().map { it.text }
        assertEquals(listOf("Comment"), buttons)
        assertTrue(panel.components.any { it is JLabel && it.text.contains("own pull request") })
    }
}
