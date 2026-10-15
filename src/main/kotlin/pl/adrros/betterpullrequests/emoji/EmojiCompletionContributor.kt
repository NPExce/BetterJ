package pl.adrros.betterpullrequests.emoji

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PlainPrefixMatcher
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.components.service
import pl.adrros.betterpullrequests.review.ReviewCommentEditors

class EmojiCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile.virtualFile ?: return
        if (!ReviewCommentEditors.isReviewCommentFile(file)) return
        val typed = EmojiShortcodes.typedBefore(parameters.editor.document.charsSequence, parameters.offset) ?: return
        if (parameters.isAutoPopup && typed.prefix.length < AUTO_POPUP_MIN_PREFIX) return

        val matching = result.withPrefixMatcher(PlainPrefixMatcher(typed.prefix, false))
        for (emoji in service<EmojiCatalog>().search(typed.prefix)) {
            matching.addElement(lookupElement(emoji))
        }
        matching.stopHere()
    }

    private fun lookupElement(emoji: Emoji): LookupElement =
        LookupElementBuilder.create(emoji, emoji.name)
            .withPresentableText(emoji.shortcode)
            .withIcon(EmojiIcon(emoji.glyph, LOOKUP_ICON_SIZE))
            .withInsertHandler { context, _ -> replaceShortcodeWithGlyph(context, emoji) }

    private fun replaceShortcodeWithGlyph(context: InsertionContext, emoji: Emoji) {
        val document = context.document
        val colonOffset = context.startOffset - 1
        val start = if (colonOffset >= 0 && document.charsSequence[colonOffset] == ':') colonOffset else context.startOffset
        document.replaceString(start, context.tailOffset, emoji.glyph)
        service<RecentEmoji>().remember(emoji)
    }

    companion object {
        /** GitHub opens its emoji suggestions after two characters as well. */
        const val AUTO_POPUP_MIN_PREFIX = 2
        private const val LOOKUP_ICON_SIZE = 16
    }
}
