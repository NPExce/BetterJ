package pl.adrros.betterpullrequests.upload

import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.util.io.HttpRequests
import java.net.HttpURLConnection
import java.net.URLEncoder

/**
 * Talks to GitHub the way the web UI's attachment box does. `uploads.github.com/user-attachments/assets`
 * is not documented, but it accepts a regular OAuth or personal token and returns a
 * `github.com/user-attachments/assets/...` URL that renders everywhere a pasted screenshot would.
 * Requests go through the IDE's HTTP stack, so proxy and certificate settings apply.
 */
class GitHubUploadClient(
    private val apiUrl: String = DEFAULT_API_URL,
    private val uploadsUrl: String = DEFAULT_UPLOADS_URL,
) {
    private val json = ObjectMapper()

    fun fetchRepositoryId(token: String, owner: String, repository: String): Long {
        val body = HttpRequests.request("$apiUrl/repos/${encode(owner)}/${encode(repository)}")
            .accept("application/vnd.github+json")
            .tuner { it.setRequestProperty("Authorization", "Bearer $token") }
            .throwStatusCodeException(false)
            .connect { request -> readOrFail(request) }
        return json.readTree(body).path("id").asLong().takeIf { it > 0 }
            ?: throw AttachmentUploadException("GitHub returned no repository id for $owner/$repository")
    }

    fun upload(token: String, repositoryId: Long, fileName: String, contentType: String, bytes: ByteArray): String {
        val query = "name=${encode(fileName)}&content_type=${encode(contentType)}&repository_id=$repositoryId"
        val body = HttpRequests.post("$uploadsUrl/user-attachments/assets?$query", contentType)
            .accept("application/json")
            .readTimeout(UPLOAD_TIMEOUT_MS)
            .tuner { it.setRequestProperty("Authorization", "Bearer $token") }
            .throwStatusCodeException(false)
            .connect { request ->
                request.write(bytes)
                readOrFail(request)
            }
        return json.readTree(body).path("url").asText().takeIf { it.isNotEmpty() }
            ?: throw AttachmentUploadException("GitHub returned no attachment url: ${body.take(200)}")
    }

    private fun readOrFail(request: HttpRequests.Request): String {
        val status = (request.connection as HttpURLConnection).responseCode
        if (status !in 200..299) {
            val error = runCatching { request.readError() }.getOrNull().orEmpty()
            throw AttachmentUploadException("GitHub answered with HTTP $status: ${error.take(300)}")
        }
        return request.readString()
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

    companion object {
        const val DEFAULT_API_URL = "https://api.github.com"
        const val DEFAULT_UPLOADS_URL = "https://uploads.github.com"
        private const val UPLOAD_TIMEOUT_MS = 120_000
    }
}
