package pl.adrros.betterpullrequests.emoji

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service

/** Most recently inserted emoji, newest first, kept across IDE restarts. */
@Service(Service.Level.APP)
class RecentEmoji(private val store: (String) -> String?, private val save: (String, String) -> Unit) {
    @Suppress("unused")
    constructor() : this(
        { key -> PropertiesComponent.getInstance().getValue(key) },
        { key, value -> PropertiesComponent.getInstance().setValue(key, value) },
    )

    fun names(): List<String> = store(KEY)?.split(SEPARATOR)?.filter { it.isNotEmpty() } ?: emptyList()

    fun emoji(catalog: EmojiCatalog = service()): List<Emoji> = names().mapNotNull(catalog::find)

    fun remember(emoji: Emoji) {
        val updated = (listOf(emoji.name) + names().filter { it != emoji.name }).take(LIMIT)
        save(KEY, updated.joinToString(SEPARATOR))
    }

    companion object {
        const val LIMIT = 24
        private const val KEY = "pl.adrros.betterpullrequests.recentEmoji"
        private const val SEPARATOR = ","
    }
}
