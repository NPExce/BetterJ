package pl.adrros.bettercodereview.review.submit

import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class SubmitReviewActionTest : BasePlatformTestCase() {
    fun `test hidden without a pull request in the context`() {
        val action = SubmitReviewAction()
        val event = TestActionEvent.createTestEvent(action, SimpleDataContext.getProjectContext(project))
        action.update(event)
        assertFalse(event.presentation.isEnabledAndVisible)
    }
}
