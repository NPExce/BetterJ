package pl.adrros.betterpullrequests.emoji

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import pl.adrros.betterpullrequests.ReviewEditorFixtures.openReviewCommentEditor

class EmojiCompletionTest : BasePlatformTestCase() {
    fun `test short code completion offers matching emoji in a review comment`() {
        myFixture.openReviewCommentEditor("nice :smi<caret>")
        myFixture.completeBasic()
        val names = myFixture.lookupElementStrings!!
        assertTrue(names.toString(), names.contains("smile"))
        assertTrue(names.toString(), names.contains("smiley_cat"))
    }

    fun `test choosing an emoji replaces the short code with the glyph`() {
        myFixture.openReviewCommentEditor("nice :smi<caret> work")
        myFixture.completeBasic()
        myFixture.lookup.currentItem = myFixture.lookupElements!!.first { it.lookupString == "smile" }
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        assertEquals("nice 😄 work", myFixture.editor.document.text)
    }

    fun `test no emoji completion outside review comments`() {
        myFixture.configureByText(PlainTextFileType.INSTANCE, "nice :smi<caret>")
        myFixture.completeBasic()
        val names = myFixture.lookupElementStrings ?: emptyList()
        assertFalse(names.toString(), names.contains("smile"))
    }

    fun `test auto popup waits for two characters`() {
        myFixture.openReviewCommentEditor("nice :s<caret>")
        myFixture.complete(com.intellij.codeInsight.completion.CompletionType.BASIC, 0)
        val auto = myFixture.lookupElementStrings ?: emptyList()
        assertTrue(auto.toString(), auto.isEmpty() || !myFixture.lookup.isCompletion)
    }

    fun `test no emoji completion without a colon`() {
        myFixture.openReviewCommentEditor("smi<caret>")
        myFixture.completeBasic()
        val names = myFixture.lookupElementStrings ?: emptyList()
        assertFalse(names.toString(), names.contains("smile"))
    }
}
