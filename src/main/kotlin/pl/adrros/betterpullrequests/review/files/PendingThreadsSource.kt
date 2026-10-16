package pl.adrros.betterpullrequests.review.files

import com.intellij.collaboration.util.getOrNull
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import org.jetbrains.plugins.github.api.data.pullrequest.GHPullRequestReviewCommentState
import org.jetbrains.plugins.github.pullrequest.data.GHPRDataContext
import org.jetbrains.plugins.github.pullrequest.data.provider.GHPRDataProvider
import org.jetbrains.plugins.github.pullrequest.data.provider.threadsComputationFlow
import org.jetbrains.plugins.github.pullrequest.ui.details.model.GHPRChangeListViewModel

private val LOG = logger<PendingThreadsSource>()

/**
 * The review threads of the pull request behind a change list, reduced to the files holding pending comments.
 * The GitHub plugin exposes its thread flow on a public data provider but hands that provider only to internal
 * classes; it is read from the change list model's private field, and the feature stays off when that fails.
 */
class PendingThreadsSource private constructor(private val dataProvider: GHPRDataProvider, val repositoryRootPath: String) {
    /** Repository-relative paths of the viewer's pending threads, re-emitted whenever the threads reload. */
    val pendingThreadPaths: Flow<List<String>>
        get() = dataProvider.reviewData.threadsComputationFlow
            .filter { !it.isInProgress }
            .map { result -> result.getOrNull().orEmpty().filter { it.state == GHPullRequestReviewCommentState.PENDING }.map { it.path } }

    companion object {
        fun from(changeList: GHPRChangeListViewModel): PendingThreadsSource? =
            try {
                val dataProvider = privateField(changeList, "dataProvider") as GHPRDataProvider
                val dataContext = privateField(changeList, "dataContext") as GHPRDataContext
                val root = dataContext.repositoryDataService.repositoryMapping.gitRepository.root
                PendingThreadsSource(dataProvider, root.path)
            } catch (e: ReflectiveOperationException) {
                LOG.info("Pending comment marks unavailable: the change list model has no reachable data provider (${e.message})")
                null
            } catch (e: ClassCastException) {
                LOG.info("Pending comment marks unavailable: unexpected model shape (${e.message})")
                null
            }

        private fun privateField(target: Any, name: String): Any? =
            target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    }
}
