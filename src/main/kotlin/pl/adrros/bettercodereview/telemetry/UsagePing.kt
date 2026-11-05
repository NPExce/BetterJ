package pl.adrros.bettercodereview.telemetry

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.util.io.HttpRequests
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.adrros.bettercodereview.settings.Feature
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

private val LOG = logger<UsagePing>()

/**
 * Tells, once a day, that one more install of BetterJ is in use, so its author knows how many people use it: a random
 * install id made once, the plugin's and the IDE's versions, and the nick the user chose to show, if any. Nothing
 * else about the user, the machine or the projects.
 */
class UsagePing : ProjectActivity {
    override suspend fun execute(project: Project) {
        val body = dueBody(LocalDate.now(ZoneOffset.UTC)) ?: return
        withContext(Dispatchers.IO) { send(body) }
    }

    companion object {
        /** The telemetry service in the repository's `telemetry/` folder. */
        const val ENDPOINT = "https://adrros-telemetry.adrros.workers.dev/ping"

        private const val INSTALL_ID = "BetterCodeReview.telemetry.installId"
        private const val NICK = "BetterCodeReview.telemetry.nick"
        private const val LAST_DAY = "BetterCodeReview.telemetry.lastDay"
        private const val TIMEOUT_MS = 5_000

        /** The ping to send [today], or null when it was sent already, is switched off or has nowhere to go. */
        fun dueBody(today: LocalDate): String? {
            if (ENDPOINT.isEmpty() || !Feature.TELEMETRY.isOn || ApplicationManager.getApplication().isUnitTestMode) return null
            val properties = PropertiesComponent.getInstance()
            if (properties.getValue(LAST_DAY) == today.toString()) return null
            properties.setValue(LAST_DAY, today.toString())
            return currentBody()
        }

        /** The nick shown to the author, empty for none. */
        val nick: String get() = PropertiesComponent.getInstance().getValue(NICK).orEmpty()

        /** Keeps the nick and tells the service at once, so it shows up (or goes away) without waiting a day. */
        fun updateNick(value: String) {
            val trimmed = value.trim().take(NICK_CHARS)
            if (trimmed == nick) return
            PropertiesComponent.getInstance().setValue(NICK, trimmed, "")
            if (ENDPOINT.isEmpty() || !Feature.TELEMETRY.isOn || ApplicationManager.getApplication().isUnitTestMode) return
            val body = currentBody()
            ApplicationManager.getApplication().executeOnPooledThread { send(body) }
        }

        private fun currentBody(): String {
            val properties = PropertiesComponent.getInstance()
            val id = properties.getValue(INSTALL_ID) ?: UUID.randomUUID().toString().also { properties.setValue(INSTALL_ID, it) }
            return body(id, ownVersion() ?: "unknown", ApplicationInfo.getInstance().build.asString(), nick)
        }

        private fun send(body: String) {
            runCatching {
                HttpRequests.post(ENDPOINT, "application/json").connectTimeout(TIMEOUT_MS).readTimeout(TIMEOUT_MS).write(body)
            }.onFailure { LOG.info("Usage ping not sent: ${it.message}") }
        }

        /** The version the build wrote into the plugin's own descriptor; the IDE's lookup of it is internal API. */
        fun ownVersion(): String? = UsagePing::class.java.classLoader.getResource("META-INF/plugin.xml")?.readText()
            ?.let { Regex("<version>([^<]+)</version>").find(it)?.groupValues?.get(1)?.trim() }

        /** The service takes up to this many characters of a nick. */
        const val NICK_CHARS = 40

        fun body(id: String, version: String, ide: String, nick: String = ""): String =
            """{"product":"betterj","id":"$id","version":"$version","ide":"$ide","nick":"${jsonText(nick)}"}"""

        /** A JSON string's content: quotes, backslashes and control characters escaped. */
        private fun jsonText(text: String): String = buildString {
            for (c in text) {
                when {
                    c == '"' || c == '\\' -> append('\\').append(c)
                    c < ' ' -> append("\\u%04x".format(c.code))
                    else -> append(c)
                }
            }
        }
    }
}
