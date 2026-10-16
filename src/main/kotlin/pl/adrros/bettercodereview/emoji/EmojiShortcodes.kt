package pl.adrros.bettercodereview.emoji

/**
 * Finds a short code being typed before the caret, e.g. `:smi` in `looks good :smi`.
 * A colon glued to a word or digit (`12:30`, `http:`) is not a short code.
 */
object EmojiShortcodes {
    data class TypedShortcode(val colonOffset: Int, val prefix: String)

    fun typedBefore(text: CharSequence, offset: Int): TypedShortcode? {
        var start = offset
        while (start > 0 && isNameChar(text[start - 1])) start--
        if (start == 0 || text[start - 1] != ':') return null
        val colonOffset = start - 1
        if (colonOffset > 0 && text[colonOffset - 1].isLetterOrDigit()) return null
        return TypedShortcode(colonOffset, text.subSequence(start, offset).toString())
    }

    fun isNameChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '+' || c == '-'
}
