package pl.adrros.ghreviewextras.emoji

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PlainPrefixMatcher
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.components.service
import pl.adrros.ghreviewextras.review.ReviewCommentEditors

class EmojiCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile.virtualFile ?: return
        if (!ReviewCommentEditors.isReviewCommentFile(file)) return
        val typed = EmojiShortcodes.typedBefore(parameters.editor.document.charsSequence, parameters.offset) ?: return

        val matching = result.withPrefixMatcher(PlainPrefixMatcher(typed.prefix, false))
        for (emoji in service<EmojiCatalog>().search(typed.prefix)) {
            matching.addElement(lookupElement(emoji))
        }
        matching.stopHere()
    }

    private fun lookupElement(emoji: Emoji): LookupElement =
        LookupElementBuilder.create(emoji, emoji.name)
            .withPresentableText(emoji.shortcode)
            .withTypeText(emoji.glyph, true)
            .withInsertHandler { context, _ -> replaceShortcodeWithGlyph(context, emoji) }

    private fun replaceShortcodeWithGlyph(context: InsertionContext, emoji: Emoji) {
        val document = context.document
        val colonOffset = context.startOffset - 1
        val start = if (colonOffset >= 0 && document.charsSequence[colonOffset] == ':') colonOffset else context.startOffset
        document.replaceString(start, context.tailOffset, emoji.glyph)
    }
}
