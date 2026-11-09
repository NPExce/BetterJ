package pl.adrros.bettercodereview.review.files

import com.intellij.collaboration.util.RefComparisonChange
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import kotlinx.coroutines.flow.Flow
import javax.swing.JTree

/** A file's "viewed" tick as the tree marks see it, whichever service stores it. */
enum class ViewedState { VIEWED, UNVIEWED, DISMISSED }

/** What the tree marks read from the review behind a change tree. */
interface ReviewTreeSource {
    val repositoryRootPath: String

    /** Key of the reviewer's own dash flags; null leaves the flags to the caller. */
    val reviewKey: String? get() = null

    /** Repository-relative path of every comment not submitted yet. */
    val pendingCommentPaths: Flow<List<String>>

    /** Viewed state per repository-relative path. */
    val viewedStateByPath: Flow<Map<String, ViewedState>>

    /** Fetches the review's commits into the local repository, which reading their files from git needs. */
    suspend fun ensureRevisionsFetched()
}

/**
 * A code review service other than GitHub (which the plugin requires and reads directly). Registered from an
 * optional plugin descriptor, so its classes load only when that service's plugin is enabled.
 */
interface ReviewTreeBackend {
    /** The change list model the tree shows, or null when the tree is not this service's. */
    fun changeListModelOf(tree: JTree): Any?

    fun owns(changeList: Any): Boolean

    fun sourceOf(changeList: Any): ReviewTreeSource?

    fun setViewedState(changeList: Any, changes: List<RefComparisonChange>, viewed: Boolean)

    fun branchState(tree: ChangesTree, changeList: Any): TreeOpensFile.BranchState

    companion object {
        val EP: ExtensionPointName<ReviewTreeBackend> = ExtensionPointName.create("pl.adrros.bettercodereview.reviewTreeBackend")

        fun forModel(changeList: Any): ReviewTreeBackend? = EP.extensionList.firstOrNull { it.owns(changeList) }

        fun changeListModelOf(tree: JTree): Any? = EP.extensionList.firstNotNullOfOrNull { it.changeListModelOf(tree) }
    }
}
