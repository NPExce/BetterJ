package pl.adrros.betterpullrequests.upload

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.jetbrains.plugins.github.api.GHRepositoryCoordinates
import org.jetbrains.plugins.github.authentication.accounts.GHAccountManager
import pl.adrros.betterpullrequests.BetterPullRequestsBundle
import pl.adrros.betterpullrequests.review.ReviewTarget
import java.util.concurrent.ConcurrentHashMap

private val LOG = logger<GitHubAttachmentUploadService>()

class GitHubAttachmentUploadService(@Suppress("unused") private val project: Project, private val cs: CoroutineScope) : AttachmentUploadService {
    private val client = GitHubUploadClient()
    private val repositoryIds = ConcurrentHashMap<GHRepositoryCoordinates, Long>()

    override fun uploadAsync(target: ReviewTarget, fileName: String, contentType: String, load: () -> ByteArray, onFinished: (Result<String>) -> Unit) {
        cs.launch(Dispatchers.IO) {
            val result = try {
                Result.success(upload(target, fileName, contentType, load))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("Attachment upload failed: $fileName", e)
                Result.failure(e)
            }
            withContext(Dispatchers.EDT) { onFinished(result) }
        }
    }

    private suspend fun upload(target: ReviewTarget, fileName: String, contentType: String, load: () -> ByteArray): String {
        if (!target.account.server.isGithubDotCom) {
            throw AttachmentUploadException(BetterPullRequestsBundle.message("upload.failed.enterprise", target.account.server.toUrl()))
        }
        val token = service<GHAccountManager>().findCredentials(target.account)
            ?: throw AttachmentUploadException(BetterPullRequestsBundle.message("upload.failed.no.token", target.account.name))
        val bytes = runInterruptible { load() }
        if (bytes.size > MAX_BYTES) throw AttachmentUploadException(BetterPullRequestsBundle.message("upload.failed.too.big", bytes.size / 1_000_000))
        val path = target.repository.repositoryPath
        val repositoryId = repositoryIds[target.repository]
            ?: runInterruptible { client.fetchRepositoryId(token, path.owner, path.repository) }.also { repositoryIds[target.repository] = it }
        return runInterruptible { client.upload(token, repositoryId, fileName, contentType, bytes) }.also {
            LOG.info("Uploaded attachment $fileName (${bytes.size} bytes) to $it")
        }
    }

    companion object {
        /** GitHub rejects larger image attachments. */
        private const val MAX_BYTES = 10 * 1024 * 1024
    }
}
