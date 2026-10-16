package pl.adrros.betterpullrequests.review.files

import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import org.jetbrains.plugins.github.api.data.pullrequest.GHPullRequestReviewCommentState
import org.jetbrains.plugins.github.api.data.pullrequest.GHPullRequestReviewThread
import org.jetbrains.plugins.github.pullrequest.data.GHPRDataContext
import org.jetbrains.plugins.github.pullrequest.data.provider.GHPRDataProvider
import org.jetbrains.plugins.github.pullrequest.data.provider.threadsComputationFlow
import org.jetbrains.plugins.github.pullrequest.ui.details.model.GHPRChangeListViewModel

private val LOG = logger<PendingThreadsSource>()

/**
 * The review threads of the pull request behind a change list, reduced to the files holding pending comments.
 * The GitHub plugin exposes its thread flow on a public data provider but hands that provider only to internal
 * classes; it is read from the change list model's private field, and the feature stays off when that fails.
 * Collecting costs no extra requests: the plugin caches one load and shares it with every collector.
 */
class PendingThreadsSource internal constructor(private val threads: Flow<List<GHPullRequestReviewThread>>, val repositoryRootPath: String) {
    /** Repository-relative path of every pending comment, sorted; failed reloads keep the last known state. */
    val pendingCommentPaths: Flow<List<String>>
        get() = threads.map(::pendingCommentPaths).distinctUntilChanged()

    companion object {
        fun pendingCommentPaths(threads: List<GHPullRequestReviewThread>): List<String> =
            threads.flatMap { thread -> thread.comments.filter { it.state == GHPullRequestReviewCommentState.PENDING }.map { thread.path } }.sorted()

        fun from(changeList: GHPRChangeListViewModel): PendingThreadsSource? =
            try {
                val dataProvider = privateField(changeList, "dataProvider") as GHPRDataProvider
                val dataContext = privateField(changeList, "dataContext") as GHPRDataContext
                val root = dataContext.repositoryDataService.repositoryMapping.gitRepository.root
                val threads = dataProvider.reviewData.threadsComputationFlow
                    .filter { it.isSuccess }
                    .map { it.result!!.getOrThrow() }
                PendingThreadsSource(threads, root.path)
            } catch (e: ReflectiveOperationException) {
                unavailable(e)
            } catch (e: RuntimeException) {
                unavailable(e)
            } catch (e: LinkageError) {
                unavailable(e)
            }

        private fun unavailable(e: Throwable): PendingThreadsSource? {
            LOG.info("Pending comment marks unavailable: the change list model has no reachable data provider (${e.javaClass.simpleName}: ${e.message})")
            return null
        }

        private fun privateField(target: Any, name: String): Any? =
            target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    }
}
