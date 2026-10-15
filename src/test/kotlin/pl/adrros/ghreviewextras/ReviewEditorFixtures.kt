package pl.adrros.ghreviewextras

import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.testFramework.EditorTestUtil
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import pl.adrros.ghreviewextras.review.ReviewCommentEditors

object ReviewEditorFixtures {
    /**
     * Opens an editor shaped like the bundled review comment editor: an in-memory `Dummy.md` file.
     * Plain text stands in for Markdown, which the test IDE does not bundle, exactly as the real
     * editor falls back when the Markdown plugin is disabled.
     */
    fun CodeInsightTestFixture.openReviewCommentEditor(textWithCaret: String) {
        val caretOffset = textWithCaret.indexOf(EditorTestUtil.CARET_TAG)
        val text = textWithCaret.replace(EditorTestUtil.CARET_TAG, "")
        val file = LightVirtualFile(ReviewCommentEditors.COMMENT_FILE_NAME, PlainTextFileType.INSTANCE, text)
        configureFromExistingVirtualFile(file)
        if (caretOffset >= 0) editor.caretModel.moveToOffset(caretOffset)
    }
}
