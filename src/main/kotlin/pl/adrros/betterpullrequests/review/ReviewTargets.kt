package pl.adrros.betterpullrequests.review

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.github.api.GHRepositoryCoordinates
import org.jetbrains.plugins.github.authentication.accounts.GithubAccount
import org.jetbrains.plugins.github.pullrequest.ui.GHPRProjectViewModel

/** The GitHub account and repository a pasted screenshot belongs to: the pull request currently connected in the project. */
class ReviewTarget(val account: GithubAccount, val repository: GHRepositoryCoordinates)

/** Resolves the target at paste time, so a later switch of pull request or account cannot redirect the upload. */
interface ReviewTargets {
    fun current(project: Project): ReviewTarget?

    class Default : ReviewTargets {
        override fun current(project: Project): ReviewTarget? {
            val connected = project.service<GHPRProjectViewModel>().connectedProjectVm.value ?: return null
            return ReviewTarget(connected.dataContext.securityService.account, connected.repository)
        }
    }
}
