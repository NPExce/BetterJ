package pl.adrros.ghreviewextras.upload

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Talks to GitHub the way the web UI's attachment box does. `uploads.github.com/user-attachments/assets`
 * is not documented, but it accepts a regular OAuth or personal token and returns a
 * `github.com/user-attachments/assets/...` URL that renders everywhere a pasted screenshot would.
 */
class GitHubUploadClient(
    private val apiUrl: String = DEFAULT_API_URL,
    private val uploadsUrl: String = DEFAULT_UPLOADS_URL,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(),
) {
    private val json = ObjectMapper()

    fun fetchRepositoryId(token: String, owner: String, repository: String): Long {
        val request = HttpRequest.newBuilder(URI.create("$apiUrl/repos/${encode(owner)}/${encode(repository)}"))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .GET()
            .build()
        val response = send(request)
        return json.readTree(response.body()).path("id").asLong().takeIf { it > 0 }
            ?: throw AttachmentUploadException("GitHub returned no repository id for $owner/$repository")
    }

    fun upload(token: String, repositoryId: Long, fileName: String, contentType: String, bytes: ByteArray): String {
        val query = "name=${encode(fileName)}&content_type=${encode(contentType)}&repository_id=$repositoryId"
        val request = HttpRequest.newBuilder(URI.create("$uploadsUrl/user-attachments/assets?$query"))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .header("Content-Type", contentType)
            .timeout(Duration.ofMinutes(2))
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
            .build()
        val response = send(request)
        return json.readTree(response.body()).path("url").asText().takeIf { it.isNotEmpty() }
            ?: throw AttachmentUploadException("GitHub returned no attachment url: ${response.body().take(200)}")
    }

    private fun send(request: HttpRequest): HttpResponse<String> {
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw AttachmentUploadException("GitHub answered with HTTP ${response.statusCode()}: ${response.body().take(300)}")
        }
        return response
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

    companion object {
        const val DEFAULT_API_URL = "https://api.github.com"
        const val DEFAULT_UPLOADS_URL = "https://uploads.github.com"
    }
}
