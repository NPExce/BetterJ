package pl.adrros.betterpullrequests.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmojiShortcodesTest {
    @Test
    fun `recognises a short code being typed at the caret`() {
        val text = "looks good :smi"
        assertEquals(EmojiShortcodes.TypedShortcode(11, "smi"), EmojiShortcodes.typedBefore(text, text.length))
    }

    @Test
    fun `accepts an empty prefix right after the colon`() {
        assertEquals(EmojiShortcodes.TypedShortcode(0, ""), EmojiShortcodes.typedBefore(":", 1))
    }

    @Test
    fun `ignores colons glued to words and numbers`() {
        assertNull(EmojiShortcodes.typedBefore("at 12:30", 8))
        assertNull(EmojiShortcodes.typedBefore("http:", 5))
    }

    @Test
    fun `ignores text without a colon`() {
        assertNull(EmojiShortcodes.typedBefore("smile", 5))
    }

    @Test
    fun `allows plus, minus and underscore in names`() {
        val text = ":+1"
        assertEquals(EmojiShortcodes.TypedShortcode(0, "+1"), EmojiShortcodes.typedBefore(text, text.length))
        val another = "ok :thumbs_up-"
        assertEquals(EmojiShortcodes.TypedShortcode(3, "thumbs_up-"), EmojiShortcodes.typedBefore(another, another.length))
    }
}
