package pl.adrros.betterpullrequests.copy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import javax.swing.JEditorPane
import javax.swing.JLabel

class CommentPanesTest {
    private fun commentPane(html: String): JEditorPane = JEditorPane("text/html", html).apply {
        name = "Simple HTML Pane"
        isEditable = false
    }

    @Test
    fun `only read-only simple html panes count as comment panes`() {
        assertNotNull(CommentPanes.asCommentPane(commentPane("<p>x</p>")))
        assertNull(CommentPanes.asCommentPane(JEditorPane("text/html", "<p>x</p>")))
        assertNull(CommentPanes.asCommentPane(commentPane("<p>x</p>").apply { isEditable = true }))
        assertNull(CommentPanes.asCommentPane(JLabel("x")))
        assertNull(CommentPanes.asCommentPane(null))
    }

    @Test
    fun `whole text is the rendered plain text without zero-width spaces`() {
        val pane = commentPane("<p>Nie potrzebujemy ju​ż walidacji?</p><p>Może w <b>VisitPackageDefinition</b></p>")
        assertEquals("Nie potrzebujemy już walidacji?\nMoże w VisitPackageDefinition", CommentPanes.wholeText(pane))
    }

    @Test
    fun `selection wins over the whole text when present`() {
        val pane = commentPane("<p>first second third</p>")
        assertNull(CommentPanes.selectedText(pane))
        assertEquals("first second third", CommentPanes.selectedOrWholeText(pane))
        val start = pane.document.getText(0, pane.document.length).indexOf("second")
        pane.select(start, start + "second".length)
        assertEquals("second", CommentPanes.selectedText(pane))
        assertEquals("second", CommentPanes.selectedOrWholeText(pane))
    }
}
