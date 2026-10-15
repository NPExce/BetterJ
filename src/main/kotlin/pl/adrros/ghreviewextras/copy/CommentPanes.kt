package pl.adrros.ghreviewextras.copy

import com.intellij.openapi.ide.CopyPasteManager
import java.awt.datatransfer.StringSelection
import javax.swing.JEditorPane

/**
 * Review comments, pull request descriptions and similar read-only text in the bundled code review UI
 * are rendered by `SimpleHtmlPane` from the collaboration tools. That factory names every pane it
 * creates, and the name is the only stable marker available to other plugins.
 */
object CommentPanes {
    private const val SIMPLE_HTML_PANE_NAME = "Simple HTML Pane"
    private const val ZERO_WIDTH_SPACE = "​"

    fun asCommentPane(component: Any?): JEditorPane? =
        (component as? JEditorPane)?.takeIf { it.name == SIMPLE_HTML_PANE_NAME && !it.isEditable }

    fun wholeText(pane: JEditorPane): String {
        val document = pane.document
        return document.getText(0, document.length).withoutZeroWidthSpaces().trim()
    }

    fun selectedText(pane: JEditorPane): String? =
        pane.selectedText?.withoutZeroWidthSpaces()?.takeIf { it.isNotBlank() }

    fun selectedOrWholeText(pane: JEditorPane): String = selectedText(pane) ?: wholeText(pane)

    fun copyToClipboard(text: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    private fun String.withoutZeroWidthSpaces(): String = replace(ZERO_WIDTH_SPACE, "")
}
