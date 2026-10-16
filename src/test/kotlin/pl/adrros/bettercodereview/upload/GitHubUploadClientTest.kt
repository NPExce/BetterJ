package pl.adrros.bettercodereview.upload

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

class GitHubUploadClientTest {
    private lateinit var server: HttpServer
    private val requests = mutableListOf<Request>()

    private class Request(val method: String, val path: String, val query: String?, val headers: Map<String, String>, val body: ByteArray)

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes()
            val headers = exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") }
            requests += Request(exchange.requestMethod, exchange.requestURI.path, exchange.requestURI.rawQuery, headers, body)
            val (status, response) = when {
                exchange.requestURI.path == "/repos/octo/repo" -> 200 to """{"id": 1066997033, "name": "repo"}"""
                exchange.requestURI.path == "/user-attachments/assets" && headers["authorization"] == "Bearer good" ->
                    201 to """{"url":"https://github.com/user-attachments/assets/abc-123"}"""
                exchange.requestURI.path == "/user-attachments/assets" -> 401 to """{"message":"Bad credentials"}"""
                else -> 404 to "{}"
            }
            val bytes = response.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun stopServer() = server.stop(0)

    private fun client(): GitHubUploadClient {
        val base = "http://127.0.0.1:${server.address.port}"
        return GitHubUploadClient(apiUrl = base, uploadsUrl = base)
    }

    @Test
    fun `fetches the numeric repository id`() {
        assertEquals(1066997033L, client().fetchRepositoryId("good", "octo", "repo"))
        val request = requests.single()
        assertEquals("GET", request.method)
        assertEquals("Bearer good", request.headers["authorization"])
    }

    @Test
    fun `uploads raw bytes with name, content type and repository id in the query`() {
        val bytes = byteArrayOf(1, 2, 3)
        val url = client().upload("good", 1066997033L, "my shot.png", "image/png", bytes)
        assertEquals("https://github.com/user-attachments/assets/abc-123", url)
        val request = requests.single()
        assertEquals("POST", request.method)
        assertEquals("name=my+shot.png&content_type=image%2Fpng&repository_id=1066997033", request.query)
        assertEquals("image/png", request.headers["content-type"])
        assertEquals("application/json", request.headers["accept"])
        assertTrue(bytes.contentEquals(request.body))
    }

    @Test
    fun `reports the http status and body on failure`() {
        try {
            client().upload("bad", 1L, "x.png", "image/png", byteArrayOf())
            fail("expected an exception")
        } catch (e: AttachmentUploadException) {
            assertTrue(e.message, e.message!!.contains("HTTP 401"))
        }
    }
}
