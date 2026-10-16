package pl.adrros.bettercodereview.review.files

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
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

    /** GitHub's viewed state per repository-relative path, as the plugin last loaded it. */
    val viewedStateByPath: Flow<Map<String, GHPullRequestFileViewedState>>
        get() = viewedStates.distinctUntilChanged()

    companion object {
        fun pendingCommentPaths(threads: List<GHPullRequestReviewThread>): List<String> =
            threads.flatMap { thread -> thread.comments.filter { it.state == GHPullRequestReviewCommentState.PENDING }.map { thread.path } }.sorted()

        fun dismissedPaths(states: Map<String, GHPullRequestFileViewedState>): List<String> =
            states.filterValues { it == GHPullRequestFileViewedState.DISMISSED }.keys.sorted()

        /**
         * False only for a change list pinned to one commit. 2026.1 and 2026.2 say so through `isOnLatest()`;
         * 2026.3 splits the model into a cumulative and a per-commit class instead, told apart by name.
         */
        fun isOnLatest(changeList: Any): Boolean {
            runCatching { changeList.javaClass.getMethod("isOnLatest").invoke(changeList) as? Boolean }.getOrNull()?.let { return it }
            val types = generateSequence<Class<*>>(changeList.javaClass) { it.superclass }.flatMap { sequenceOf(it) + it.interfaces.asSequence() }
            if (types.any { it.simpleName == "GHPRCumulativeChangeListViewModel" }) return true
            return !changeList.javaClass.simpleName.contains("Commit")
        }

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

        /**
         * The plugin's own state flow for viewed states is internal; the same loader and reload signal are public.
         * The signal is subscribed before the first load, so a reload landing in between is not lost; bursts conflate.
         */
        internal fun viewedStatesOf(provider: GHPRViewedStateDataProvider): Flow<Map<String, GHPullRequestFileViewedState>> {
            var warned = false
            return channelFlow {
                launch(start = CoroutineStart.UNDISPATCHED) { provider.viewedStateNeedsReloadSignal.collect { send(Unit) } }
                send(Unit)
            }.conflate().transform {
                val loaded = try {
                    provider.loadViewedState()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive() // the provider's own scope went away: a failed load, not ours
                    LOG.debug("Viewed state load was cancelled by the provider", e)
                    return@transform
                } catch (e: Exception) {
                    // The next reload signal (a tick, a refresh, new commits) retries; the first failure is worth a warning.
                    if (warned) LOG.debug("Viewed state load failed again", e) else LOG.warn("Viewed state load failed; the viewed-then-changed marks wait for the next reload", e)
                    warned = true
                    return@transform
                }
                warned = false
                emit(loaded)
            }
        }

        private fun unavailable(e: Throwable): ReviewDataSource? {
            LOG.debug("Tree marks unavailable: the change list model has no reachable data provider", e)
            return null
        }

        /** Looks the field up along the class hierarchy: 2026.3 declares it in a shared base class. */
        internal fun privateField(target: Any, name: String): Any? {
            val field = generateSequence<Class<*>>(target.javaClass) { it.superclass }
                .firstNotNullOfOrNull { type -> type.declaredFields.firstOrNull { it.name == name } }
                ?: throw NoSuchFieldException("$name in ${target.javaClass.name}")
            field.isAccessible = true
            return field.get(target)
        }
    }
}
