package pl.adrros.bettercodereview.review.files

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.jetbrains.plugins.github.api.data.pullrequest.GHPullRequestFileViewedState
import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewDataSourceTest {
    @Test
    fun `dismissed viewed states become sorted repository paths`() {
        val states = mapOf(
            "src/B.java" to GHPullRequestFileViewedState.DISMISSED,
            "src/A.java" to GHPullRequestFileViewedState.DISMISSED,
            "README.md" to GHPullRequestFileViewedState.VIEWED,
            "docs/x.md" to GHPullRequestFileViewedState.UNVIEWED,
        )
        assertEquals(listOf("src/A.java", "src/B.java"), ReviewDataSource.dismissedPaths(states))
    }

    @Test
    fun `the dismissed flow follows the viewed states`() = runBlocking {
        val states = MutableStateFlow(mapOf("a" to GHPullRequestFileViewedState.DISMISSED))
        val source = ReviewDataSource(emptyFlow(), states, "/repo")
        assertEquals(listOf("a"), source.dismissedPaths.first())
        states.value = mapOf("a" to GHPullRequestFileViewedState.VIEWED, "b" to GHPullRequestFileViewedState.DISMISSED)
        assertEquals(listOf("b"), source.dismissedPaths.first())
    }
}
