package pl.adrros.ghreviewextras.upload

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.plugins.github.api.GHRepositoryCoordinates
import org.jetbrains.plugins.github.authentication.accounts.GHAccountManager
import org.jetbrains.plugins.github.pullrequest.ui.GHPRProjectViewModel
import pl.adrros.ghreviewextras.GhReviewExtrasBundle
import java.util.concurrent.ConcurrentHashMap

private val LOG = logger<GitHubAttachmentUploadService>()

@Service(Service.Level.PROJECT)
class GitHubAttachmentUploadService(private val project: Project, private val cs: CoroutineScope) : AttachmentUploadService {
    private val client = GitHubUploadClient()
    private val repositoryIds = ConcurrentHashMap<GHRepositoryCoordinates, Long>()

    override fun uploadAsync(fileName: String, contentType: String, bytes: ByteArray, onFinished: (Result<String>) -> Unit) {
        cs.launch(Dispatchers.IO) {
            val result = runCatching { upload(fileName, contentType, bytes) }
            result.exceptionOrNull()?.let { LOG.warn("Attachment upload failed: $fileName", it) }
            withContext(Dispatchers.EDT) { onFinished(result) }
        }
    }

    private suspend fun upload(fileName: String, contentType: String, bytes: ByteArray): String {
        val target = currentReviewTarget()
        val token = service<GHAccountManager>().findCredentials(target.account)
            ?: throw AttachmentUploadException(GhReviewExtrasBundle.message("upload.failed.no.token", target.account.name))
        val repositoryId = repositoryIds.getOrPut(target.repository) {
            client.fetchRepositoryId(token, target.repository.repositoryPath.owner, target.repository.repositoryPath.repository)
        }
        return client.upload(token, repositoryId, fileName, contentType, bytes)
    }

    private fun currentReviewTarget(): ReviewTarget {
        val connected = project.service<GHPRProjectViewModel>().connectedProjectVm.value
            ?: throw AttachmentUploadException(GhReviewExtrasBundle.message("upload.failed.not.connected"))
        val account = connected.dataContext.securityService.account
        if (!account.server.isGithubDotCom) {
            throw AttachmentUploadException(GhReviewExtrasBundle.message("upload.failed.enterprise", account.server.toUrl()))
        }
        return ReviewTarget(account, connected.repository)
    }

    private class ReviewTarget(
        val account: org.jetbrains.plugins.github.authentication.accounts.GithubAccount,
        val repository: GHRepositoryCoordinates,
    )
}
