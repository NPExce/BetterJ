package pl.adrros.betterpullrequests.emoji

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentEmojiTest {
    private val store = mutableMapOf<String, String>()
    private val recent = RecentEmoji({ store[it] }, { key, value -> store[key] = value })
    private val catalog = EmojiCatalog(listOf(Emoji("smile", "😄"), Emoji("tada", "🎉"), Emoji("eyes", "👀")))

    @Test
    fun `newest first without duplicates`() {
        recent.remember(catalog.find("smile")!!)
        recent.remember(catalog.find("tada")!!)
        recent.remember(catalog.find("smile")!!)
        assertEquals(listOf("smile", "tada"), recent.names())
        assertEquals(listOf("😄", "🎉"), recent.emoji(catalog).map { it.glyph })
    }

    @Test
    fun `keeps at most the limit`() {
        repeat(RecentEmoji.LIMIT + 5) { recent.remember(Emoji("e$it", "x")) }
        assertEquals(RecentEmoji.LIMIT, recent.names().size)
        assertEquals("e${RecentEmoji.LIMIT + 4}", recent.names().first())
    }

    @Test
    fun `unknown names are skipped when resolving`() {
        store["pl.adrros.betterpullrequests.recentEmoji"] = "ghost,eyes"
        assertEquals(listOf("👀"), recent.emoji(catalog).map { it.glyph })
    }
}
