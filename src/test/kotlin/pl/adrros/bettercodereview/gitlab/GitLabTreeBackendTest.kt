package pl.adrros.bettercodereview.gitlab

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.plugins.github.api.data.pullrequest.GHPullRequestFileViewedState
import pl.adrros.bettercodereview.review.files.ReviewDataSource
import pl.adrros.bettercodereview.review.files.ReviewTreeBackend
import pl.adrros.bettercodereview.review.files.ViewedState

class GitLabTreeBackendTest : BasePlatformTestCase() {
    fun `test the GitLab backend is registered when the GitLab plugin is enabled`() {
        assertTrue(ReviewTreeBackend.EP.extensionList.any { it is GitLabTreeBackend })
    }

    fun `test a model that is not GitLab's belongs to no backend`() {
        assertNull(ReviewTreeBackend.forModel(Any()))
        assertFalse(GitLabTreeBackend().owns("not a change list"))
    }

    fun `test read files are viewed and files remembered at an older version are viewed-then-changed`() {
        assertEquals(ViewedState.VIEWED, GitLabTreeBackend.viewedState(isRead = true, rememberedAsViewed = true))
        assertEquals(ViewedState.DISMISSED, GitLabTreeBackend.viewedState(isRead = false, rememberedAsViewed = true))
        assertEquals(ViewedState.UNVIEWED, GitLabTreeBackend.viewedState(isRead = false, rememberedAsViewed = false))
    }

    fun `test GitHub's viewed states map to the shared ones`() {
        assertEquals(ViewedState.VIEWED, ReviewDataSource.viewedState(GHPullRequestFileViewedState.VIEWED))
        assertEquals(ViewedState.DISMISSED, ReviewDataSource.viewedState(GHPullRequestFileViewedState.DISMISSED))
        assertEquals(ViewedState.UNVIEWED, ReviewDataSource.viewedState(GHPullRequestFileViewedState.UNVIEWED))
    }
}
