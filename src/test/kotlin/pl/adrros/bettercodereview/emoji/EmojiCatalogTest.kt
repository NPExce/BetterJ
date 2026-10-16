package pl.adrros.bettercodereview.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiCatalogTest {
    private val catalog = EmojiCatalog(EmojiCatalog.loadBundled())

    @Test
    fun `bundled catalogue holds the GitHub short codes`() {
        assertTrue(catalog.all.size > 1800)
        assertEquals("👍", catalog.find("+1")?.glyph)
        assertEquals("😄", catalog.find("smile")?.glyph)
        assertEquals("🇵🇱", catalog.find("poland")?.glyph)
    }

    @Test
    fun `search puts prefix matches before contains matches`() {
        val names = catalog.search("smile").map { it.name }
        assertTrue(names.isNotEmpty())
        val firstContainsOnly = names.indexOfFirst { !it.startsWith("smile") }
        val lastPrefixed = names.indexOfLast { it.startsWith("smile") }
        assertTrue("prefix matches should come first: $names", firstContainsOnly == -1 || lastPrefixed < firstContainsOnly)
        assertTrue(names.contains("smiley_cat"))
    }

    @Test
    fun `empty query lists everything`() {
        assertEquals(catalog.all, catalog.search(""))
    }
}
