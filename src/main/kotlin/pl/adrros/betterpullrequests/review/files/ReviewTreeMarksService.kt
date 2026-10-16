package pl.adrros.betterpullrequests.review.files

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.plugins.github.pullrequest.ui.details.model.GHPRChangeListViewModel

/** Keeps a change tree's overlay fed with the pending-comment marks of its pull request. */
@Service(Service.Level.PROJECT)
class ReviewTreeMarksService(@Suppress("unused") private val project: Project, private val cs: CoroutineScope) {
    /** Returns the following job, or null when the pull request's threads cannot be reached. */
    fun follow(changeList: GHPRChangeListViewModel, overlay: ReviewChangeTrees.CurrentFileOverlay): Job? {
        val source = PendingThreadsSource.from(changeList) ?: return null
        return cs.launch(Dispatchers.Default) {
            source.pendingThreadPaths
                .map { paths -> PendingMarks.of(source.repositoryRootPath, paths) }
                .collect { marks -> withContext(Dispatchers.EDT) { overlay.pendingMarks = marks } }
        }
    }
}
