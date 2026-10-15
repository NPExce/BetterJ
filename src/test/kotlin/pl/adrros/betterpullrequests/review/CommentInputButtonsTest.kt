package pl.adrros.betterpullrequests.review

import com.intellij.collaboration.ui.HorizontalListPanel
import com.intellij.collaboration.ui.VerticalListPanel
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import net.miginfocom.swing.MigLayout
import pl.adrros.betterpullrequests.ReviewEditorFixtures.openReviewCommentEditor
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

class CommentInputButtonsTest : BasePlatformTestCase() {
    /** Mirrors `CommentInputActionsComponentFactory.attachActions`: the field above a MigLayout row of hints and buttons. */
    private fun inputWithActions(editorComponent: JPanel): JPanel {
        val row = JPanel(MigLayout()).apply {
            add(HorizontalListPanel().apply { add(JLabel("hint")) })
            add(HorizontalListPanel().apply { add(JButton("Reply")) })
        }
        return VerticalListPanel().apply {
            add(JPanel().apply { add(editorComponent) })
            add(row)
        }
    }

    fun `test buttons land at the start of the action row`() {
        myFixture.openReviewCommentEditor("<caret>")
        val editor = myFixture.editor
        val holder = JPanel()
        inputWithActions(holder.also { it.add(editor.component) })

        assertTrue(CommentInputButtons.install(editor))

        val row = CommentInputButtons.findActionRow(editor.component)!!
        assertEquals(3, row.componentCount)
        assertEquals(2, (row.getComponent(0) as JPanel).componentCount)
        assertTrue(CommentInputButtons.install(editor))
        assertEquals("installing twice adds nothing", 3, row.componentCount)
    }

    fun `test an editor without an action row is left alone`() {
        myFixture.openReviewCommentEditor("<caret>")
        JPanel().add(myFixture.editor.component)

        assertFalse(CommentInputButtons.install(myFixture.editor))
    }
}
