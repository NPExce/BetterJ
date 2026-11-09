package pl.adrros.bettercodereview.gitlab

import com.intellij.collaboration.ui.codereview.details.model.CodeReviewChangeListViewModel
import com.intellij.collaboration.util.RefComparisonChange
import com.intellij.ide.DataManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.jetbrains.plugins.gitlab.mergerequest.data.GitLabMergeRequest
import org.jetbrains.plugins.gitlab.mergerequest.ui.details.model.GitLabMergeRequestChangeListViewModel
import pl.adrros.bettercodereview.review.files.ReviewDataSource
import pl.adrros.bettercodereview.review.files.ReviewTreeBackend
import pl.adrros.bettercodereview.review.files.ReviewTreeSource
import pl.adrros.bettercodereview.review.files.TreeOpensFile.BranchState
import pl.adrros.bettercodereview.review.files.ViewedState
import javax.swing.JTree

private val LOG = logger<GitLabTreeBackend>()

/**
 * The tree marks for GitLab merge requests. The GitLab plugin keeps the merge request behind its change list in a
 * private field; it is read by name, and the marks stay off when that fails.
 */
class GitLabTreeBackend : ReviewTreeBackend {
    override fun changeListModelOf(tree: JTree): Any? =
        DataManager.getInstance().getDataContext(tree).getData(CodeReviewChangeListViewModel.DATA_KEY) as? GitLabMergeRequestChangeListViewModel

    override fun owns(changeList: Any): Boolean = changeList is GitLabMergeRequestChangeListViewModel

    override fun sourceOf(changeList: Any): ReviewTreeSource? = guarded("Tree marks unavailable") {
        val model = changeList as GitLabMergeRequestChangeListViewModel
        val mergeRequest = mergeRequestOf(model)
        val viewedState = runCatching { ReviewDataSource.privateField(model, "persistentChangesViewedState") }.getOrNull()
        GitLabReviewSource(model, mergeRequest, rootOf(mergeRequest), viewedState)
    }

    /** 2026.3 declares the setter on the shared interface; 2026.1 and 2026.2 only on GitLab's own, called by name. */
    override fun setViewedState(changeList: Any, changes: List<RefComparisonChange>, viewed: Boolean) {
        if (changeList is CodeReviewChangeListViewModel.WithViewedState) {
            changeList.setViewedState(changes, viewed)
            return
        }
        guarded("Cannot change the viewed state") {
            changeList.javaClass.getMethod("setViewedState", Iterable::class.java, Boolean::class.javaPrimitiveType).invoke(changeList, changes, viewed)
        }
    }

    /** Checked out when the current branch has the merge request's source branch name or sits on its newest commit. */
    override fun branchState(tree: ChangesTree, changeList: Any): BranchState = guarded("Current-branch state unavailable") {
        val model = changeList as GitLabMergeRequestChangeListViewModel
        val mergeRequest = mergeRequestOf(model)
        val repository: Any = mergeRequest.gitRemote.repository
        val branch = repository.javaClass.getMethod("getCurrentBranchName").invoke(repository) as String?
        val head = repository.javaClass.getMethod("getCurrentRevision").invoke(repository) as String?
        val newest = model.changes.firstOrNull()?.revisionNumberAfter?.asString()
        val onBranch = branch != null && branch == mergeRequest.details.value.sourceBranch
        if (onBranch || (head != null && head == newest)) BranchState.CHECKED_OUT else BranchState.OTHER
    } ?: BranchState.UNKNOWN

    private fun mergeRequestOf(model: GitLabMergeRequestChangeListViewModel): GitLabMergeRequest =
        ReviewDataSource.privateField(model, "mergeRequest") as GitLabMergeRequest

    private fun <T> guarded(what: String, block: () -> T): T? =
        try {
            block()
        } catch (e: ReflectiveOperationException) {
            LOG.debug("$what: the GitLab plugin's model changed", e)
            null
        } catch (e: RuntimeException) {
            LOG.debug("$what: the GitLab plugin's model changed", e)
            null
        } catch (e: LinkageError) {
            LOG.debug("$what: the GitLab plugin's model changed", e)
            null
        }

    companion object {
        // The git repository type pulls in a platform package that moves between IDE versions; ask for the root by name.
        fun rootOf(mergeRequest: GitLabMergeRequest): String {
            val repository: Any = mergeRequest.gitRemote.repository
            return (repository.javaClass.getMethod("getRoot").invoke(repository) as VirtualFile).path
        }

        /** GitLab keeps only "read" per file; a file it remembers as viewed at an older version is shown as viewed-then-changed. */
        fun viewedState(isRead: Boolean, rememberedAsViewed: Boolean): ViewedState = when {
            isRead -> ViewedState.VIEWED
            rememberedAsViewed -> ViewedState.DISMISSED
            else -> ViewedState.UNVIEWED
        }
    }
}

private class GitLabReviewSource(
    private val model: GitLabMergeRequestChangeListViewModel,
    private val mergeRequest: GitLabMergeRequest,
    override val repositoryRootPath: String,
    /** The GitLab plugin's viewed-file storage, internal to it, so read by method names. */
    private val persistentViewedState: Any?,
) : ReviewTreeSource {
    override val reviewKey: String = "$repositoryRootPath!${mergeRequest.iid}"

    override val pendingCommentPaths: Flow<List<String>>
        get() = mergeRequest.draftNotes.map { result ->
            result.getOrNull().orEmpty().mapNotNull { note ->
                note.position.value?.let { it.filePathAfter ?: it.filePathBefore }
            }.sorted()
        }.distinctUntilChanged()

    override val viewedStateByPath: Flow<Map<String, ViewedState>>
        get() = model.detailsByChange.map { details ->
            val remembered = rememberedViewedPaths()
            details.entries.mapNotNull { (change, detail) ->
                val path = change.filePathAfter ?: change.filePathBefore ?: return@mapNotNull null
                val relative = FileUtil.getRelativePath(repositoryRootPath, path.path, '/') ?: return@mapNotNull null
                relative to GitLabTreeBackend.viewedState(detail.isRead, relative in remembered || path.path in remembered)
            }.toMap()
        }.distinctUntilChanged()

    override suspend fun ensureRevisionsFetched() {
        mergeRequest.changes.first().ensureAllRevisionsFetched()
    }

    /** Paths the GitLab plugin remembers as viewed, at any version; empty when its storage cannot be read. */
    private fun rememberedViewedPaths(): Set<String> {
        val storage = persistentViewedState ?: return emptySet()
        return try {
            val state = call(storage, "getState") ?: return emptySet()
            val states = call(state, "getStates") as? List<*> ?: return emptySet()
            val mine = states.filterNotNull().firstOrNull { entry ->
                val id = call(entry, "getId") ?: return@firstOrNull false
                call(id, "getProjectId") == mergeRequest.projectId && call(id, "getIid") == mergeRequest.iid && call(id, "getServerPath") == mergeRequest.serverPath
            } ?: return emptySet()
            (call(mine, "getViewedFiles") as? Map<*, *>)?.keys?.filterIsInstance<String>()?.toSet().orEmpty()
        } catch (e: ReflectiveOperationException) {
            LOG.debug("The GitLab plugin's viewed-file storage could not be read", e)
            emptySet()
        } catch (e: RuntimeException) {
            LOG.debug("The GitLab plugin's viewed-file storage could not be read", e)
            emptySet()
        }
    }

    private fun call(target: Any, method: String): Any? = target.javaClass.getMethod(method).invoke(target)
}
