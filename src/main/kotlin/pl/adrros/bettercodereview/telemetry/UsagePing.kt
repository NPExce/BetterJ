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
 * install id made once, the plugin's and the IDE's versions. Nothing about the user, the machine or the projects.
 */
class UsagePing : ProjectActivity {
    override suspend fun execute(project: Project) {
        val body = dueBody(LocalDate.now(ZoneOffset.UTC)) ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                HttpRequests.post(ENDPOINT, "application/json").connectTimeout(TIMEOUT_MS).readTimeout(TIMEOUT_MS).write(body)
            }.onFailure { LOG.info("Usage ping not sent: ${it.message}") }
        }
    }

    companion object {
        /** The telemetry service in the repository's `telemetry/` folder. */
        const val ENDPOINT = "https://adrros-telemetry.adrros.workers.dev/ping"

        private const val INSTALL_ID = "BetterCodeReview.telemetry.installId"
        private const val LAST_DAY = "BetterCodeReview.telemetry.lastDay"
        private const val TIMEOUT_MS = 5_000

        /** The ping to send [today], or null when it was sent already, is switched off or has nowhere to go. */
        fun dueBody(today: LocalDate): String? {
            if (ENDPOINT.isEmpty() || !Feature.TELEMETRY.isOn || ApplicationManager.getApplication().isUnitTestMode) return null
            val properties = PropertiesComponent.getInstance()
            if (properties.getValue(LAST_DAY) == today.toString()) return null
            properties.setValue(LAST_DAY, today.toString())
            val id = properties.getValue(INSTALL_ID) ?: UUID.randomUUID().toString().also { properties.setValue(INSTALL_ID, it) }
            val version = ownVersion() ?: "unknown"
            return body(id, version, ApplicationInfo.getInstance().build.asString())
        }

        /** The version the build wrote into the plugin's own descriptor; the IDE's lookup of it is internal API. */
        fun ownVersion(): String? = UsagePing::class.java.classLoader.getResource("META-INF/plugin.xml")?.readText()
            ?.let { Regex("<version>([^<]+)</version>").find(it)?.groupValues?.get(1)?.trim() }

        fun body(id: String, version: String, ide: String): String =
            """{"product":"betterj","id":"$id","version":"$version","ide":"$ide"}"""
    }
}
