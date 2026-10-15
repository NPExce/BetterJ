package pl.adrros.ghreviewextras.emoji

import com.intellij.openapi.components.Service

data class Emoji(val name: String, val glyph: String) {
    val shortcode: String get() = ":$name:"
}

/** GitHub's emoji short codes paired with their Unicode glyphs; generated from the `GET /emojis` API. */
@Service(Service.Level.APP)
class EmojiCatalog(val all: List<Emoji>) {
    @Suppress("unused")
    constructor() : this(loadBundled())

    private val byName: Map<String, Emoji> = all.associateBy { it.name }

    fun find(name: String): Emoji? = byName[name]

    /** Names starting with the query come first, then names merely containing it. */
    fun search(query: String): List<Emoji> {
        val needle = query.lowercase()
        if (needle.isEmpty()) return all
        val (prefixed, contained) = all.filter { it.name.contains(needle) }.partition { it.name.startsWith(needle) }
        return prefixed + contained
    }

    companion object {
        private const val RESOURCE = "/emoji.tsv"

        fun loadBundled(): List<Emoji> {
            val stream = EmojiCatalog::class.java.getResourceAsStream(RESOURCE)
                ?: error("Missing resource $RESOURCE")
            return stream.bufferedReader(Charsets.UTF_8).useLines { lines -> parse(lines) }
        }

        fun parse(lines: Sequence<String>): List<Emoji> =
            lines.filter { it.isNotBlank() }
                .map { line ->
                    val (name, glyph) = line.split('\t', limit = 2)
                    Emoji(name, glyph)
                }
                .toList()
    }
}
