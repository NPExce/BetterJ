package pl.adrros.bettercodereview.review.files

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import org.jetbrains.plugins.github.api.data.pullrequest.GHPullRequestFileViewedState
import org.jetbrains.plugins.github.api.data.pullrequest.GHPullRequestReviewCommentState
import org.jetbrains.plugins.github.api.data.pullrequest.GHPullRequestReviewThread
import org.jetbrains.plugins.github.pullrequest.data.GHPRDataContext
import org.jetbrains.plugins.github.pullrequest.data.provider.GHPRDataProvider
import org.jetbrains.plugins.github.pullrequest.data.provider.GHPRViewedStateDataProvider
import org.jetbrains.plugins.github.pullrequest.data.provider.threadsComputationFlow
import org.jetbrains.plugins.github.pullrequest.ui.details.model.GHPRChangeListViewModel

private val LOG = logger<ReviewDataSource>()

/**
 * Review data of the pull request behind a change list, reduced to what the tree marks need: files holding
 * pending comments and files whose "viewed" tick GitHub dismissed because they changed afterwards.
 * The GitHub plugin exposes the flows on public data providers but hands those providers only to internal
 * classes; they are read from the change list model's private fields, and the marks stay off when that fails.
 * Collecting costs no extra requests: the plugin caches each load and shares it with every collector.
 */
class ReviewDataSource internal constructor(
    private val threads: Flow<List<GHPullRequestReviewThread>>,
    private val viewedStates: Flow<Map<String, GHPullRequestFileViewedState>>,
    val repositoryRootPath: String,
) {
    /** Repository-relative path of every pending comment, sorted. */
    val pendingCommentPaths: Flow<List<String>>
        get() = threads.map(::pendingCommentPaths).distinctUntilChanged()

    /** Repository-relative paths of files viewed earlier and changed since (GitHub's DISMISSED state), sorted. */
    val dismissedPaths: Flow<List<String>>
        get() = viewedStates.map(::dismissedPaths).distinctUntilChanged()

    companion object {
        fun pendingCommentPaths(threads: List<GHPullRequestReviewThread>): List<String> =
            threads.flatMap { thread -> thread.comments.filter { it.state == GHPullRequestReviewCommentState.PENDING }.map { thread.path } }.sorted()

        fun dismissedPaths(states: Map<String, GHPullRequestFileViewedState>): List<String> =
            states.filterValues { it == GHPullRequestFileViewedState.DISMISSED }.keys.sorted()

        /** False only when the change list is pinned to one commit; IDE builds without the property count as latest. */
        fun isOnLatest(changeList: GHPRChangeListViewModel): Boolean =
            runCatching { changeList.javaClass.getMethod("isOnLatest").invoke(changeList) as? Boolean }.getOrNull() ?: true

        fun from(changeList: GHPRChangeListViewModel): ReviewDataSource? =
            try {
                val dataProvider = privateField(changeList, "dataProvider") as GHPRDataProvider
                val dataContext = privateField(changeList, "dataContext") as GHPRDataContext
                // The git repository type pulls in a platform package that moves between IDE versions; ask for the root by name.
                val repository: Any = dataContext.repositoryDataService.repositoryMapping.gitRepository
                val root = repository.javaClass.getMethod("getRoot").invoke(repository) as VirtualFile
                val threads = dataProvider.reviewData.threadsComputationFlow
                    .filter { it.isSuccess }
                    .map { it.result!!.getOrThrow() }
                ReviewDataSource(threads, viewedStatesOf(dataProvider.viewedStateData), root.path)
            } catch (e: ReflectiveOperationException) {
                unavailable(e)
            } catch (e: RuntimeException) {
                unavailable(e)
            } catch (e: LinkageError) {
                unavailable(e)
            }

        /** The plugin's own state flow for viewed states is internal; the same loader and reload signal are public. */
        private fun viewedStatesOf(provider: GHPRViewedStateDataProvider): Flow<Map<String, GHPullRequestFileViewedState>> = flow {
            emitLoaded(provider)
            provider.viewedStateNeedsReloadSignal.collect { emitLoaded(provider) }
        }

        private suspend fun kotlinx.coroutines.flow.FlowCollector<Map<String, GHPullRequestFileViewedState>>.emitLoaded(provider: GHPRViewedStateDataProvider) {
            val loaded = try {
                provider.loadViewedState()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.debug("Viewed state load failed, keeping the previous marks", e)
                return
            }
            emit(loaded)
        }

        private fun unavailable(e: Throwable): ReviewDataSource? {
            LOG.info("Tree marks unavailable: the change list model has no reachable data provider (${e.javaClass.simpleName}: ${e.message})")
            return null
        }

        private fun privateField(target: Any, name: String): Any? =
            target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    }
}
