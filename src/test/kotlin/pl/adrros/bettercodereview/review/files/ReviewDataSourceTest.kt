package pl.adrros.bettercodereview.review.files

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.plugins.github.api.data.pullrequest.GHPullRequestFileViewedState
import org.jetbrains.plugins.github.pullrequest.data.provider.GHPRViewedStateDataProvider
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

    private class FakeProvider : GHPRViewedStateDataProvider {
        val loads = mutableListOf<Map<String, GHPullRequestFileViewedState>?>() // null = fail this load
        private val counter = java.util.concurrent.atomic.AtomicInteger()
        val served get() = counter.get()
        val signal = MutableSharedFlow<Unit>()
        /** Subscribers to the reload signal at the moment of the first load: the source must subscribe before it loads. */
        @Volatile var subscribersAtFirstLoad = -1
        /** When set, the first load waits for it, so a reload signal can land while the first load is in flight. */
        var firstLoadGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        override val viewedStateNeedsReloadSignal get() = signal
        override suspend fun loadViewedState(): Map<String, GHPullRequestFileViewedState> {
            val index = counter.getAndIncrement()
            if (index == 0) {
                subscribersAtFirstLoad = signal.subscriptionCount.value
                firstLoadGate?.await()
            }
            return loads[index] ?: throw IllegalStateException("load failed")
        }
        override suspend fun updateViewedState(paths: Iterable<String>, isViewed: Boolean) {}
        override suspend fun signalViewedStateNeedsReload() {}
    }

    @Test
    fun `a reload signalled during the first load is not lost`() = runBlocking {
        val provider = FakeProvider()
        provider.firstLoadGate = kotlinx.coroutines.CompletableDeferred()
        provider.loads += mapOf("a" to GHPullRequestFileViewedState.VIEWED)
        provider.loads += mapOf("a" to GHPullRequestFileViewedState.DISMISSED)
        val collected = mutableListOf<Map<String, GHPullRequestFileViewedState>>()
        val job = launch(kotlinx.coroutines.Dispatchers.Default) {
            ReviewDataSource.viewedStatesOf(provider).take(2).toList(collected)
        }
        withTimeout(5000) {
            while (provider.served < 1) kotlinx.coroutines.delay(10)
            provider.signal.emit(Unit) // arrives while the first load is still running
            provider.firstLoadGate!!.complete(Unit)
            job.join()
        }
        assertEquals(listOf(mapOf("a" to GHPullRequestFileViewedState.VIEWED), mapOf("a" to GHPullRequestFileViewedState.DISMISSED)), collected)
        assertEquals("subscribed to the reload signal before the first load", 1, provider.subscribersAtFirstLoad)
    }

    private open class GHPRCumulativeChangeListViewModel
    private class GHPRCumulativeChangeListViewModelImpl : GHPRCumulativeChangeListViewModel()
    private class GHPRCommitChangeListViewModelImpl
    private class SaysSo { @Suppress("unused") fun isOnLatest(): Boolean = false }
    private open class Base { @Suppress("unused") private val dataProvider = "from the base class" }
    private class Derived : Base()

    @Test
    fun `without isOnLatest the model kind is told from the 2026_3 class names`() {
        assertEquals(false, ReviewDataSource.isOnLatest(SaysSo()))
        assertEquals(true, ReviewDataSource.isOnLatest(GHPRCumulativeChangeListViewModelImpl()))
        assertEquals(false, ReviewDataSource.isOnLatest(GHPRCommitChangeListViewModelImpl()))
    }

    @Test
    fun `private fields are found in a superclass`() {
        assertEquals("from the base class", ReviewDataSource.privateField(Derived(), "dataProvider"))
        try {
            ReviewDataSource.privateField(Derived(), "missing")
            org.junit.Assert.fail("expected NoSuchFieldException")
        } catch (expected: NoSuchFieldException) {
        }
    }

    @Test
    fun `viewed states load once at start, skip a failed load and reload on the signal`() = runBlocking {
        val provider = FakeProvider()
        provider.loads += mapOf("a" to GHPullRequestFileViewedState.VIEWED)
        provider.loads += null
        provider.loads += mapOf("a" to GHPullRequestFileViewedState.DISMISSED)
        val collected = mutableListOf<Map<String, GHPullRequestFileViewedState>>()
        val job = launch(kotlinx.coroutines.Dispatchers.Default) {
            ReviewDataSource.viewedStatesOf(provider).take(2).toList(collected)
        }
        withTimeout(5000) {
            while (provider.served < 1) kotlinx.coroutines.delay(10)
            provider.signal.emit(Unit) // fails: nothing emitted
            while (provider.served < 2) kotlinx.coroutines.delay(10)
            provider.signal.emit(Unit)
            job.join()
        }
        assertEquals(listOf(mapOf("a" to GHPullRequestFileViewedState.VIEWED), mapOf("a" to GHPullRequestFileViewedState.DISMISSED)), collected)
        assertEquals(3, provider.served)
    }
}
