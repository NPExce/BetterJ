package pl.adrros.betterpullrequests.copy

import com.intellij.collaboration.ui.HorizontalListPanel
import com.intellij.collaboration.ui.VerticalListPanel
import com.intellij.icons.AllIcons
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.InlineIconButton
import net.miginfocom.swing.MigLayout
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JPanel

class CommentCopyButtonsTest : BasePlatformTestCase() {
    private fun commentPane(): JEditorPane = JEditorPane("text/html", "<p>hello</p>").apply {
        name = "Simple HTML Pane"
        isEditable = false
    }

    /** Mirrors `CodeReviewChatItemUIUtil.ComponentFactory.wrapWithHeader`: header (title + actions) above the body. */
    private fun commentItem(pane: JEditorPane, actions: JPanel): JPanel {
        val header = JPanel(MigLayout()).apply {
            add(HorizontalListPanel().apply { add(JLabel("author")) })
            add(actions)
        }
        val body = VerticalListPanel().apply { add(JPanel().apply { add(pane) }) }
        return VerticalListPanel().apply {
            add(header)
            add(body)
        }
    }

    fun `test copy button joins the hover actions of the comment`() {
        val pane = commentPane()
        val actions = HorizontalListPanel().apply { add(InlineIconButton(AllIcons.Actions.Edit)) }
        commentItem(pane, actions)

        assertTrue(CommentCopyButtons.install(pane))

        assertEquals(2, actions.componentCount)
        assertTrue(actions.getComponent(1) is InlineIconButton)
        assertTrue(CommentCopyButtons.install(pane))
        assertEquals("installing twice adds nothing", 2, actions.componentCount)
    }

    fun `test an empty actions row still gets the button`() {
        val pane = commentPane()
        val actions = HorizontalListPanel()
        commentItem(pane, actions)

        assertTrue(CommentCopyButtons.install(pane))
        assertEquals(1, actions.componentCount)
    }

    fun `test a pane without an actions row is left alone`() {
        val pane = commentPane()
        JPanel().apply { add(HorizontalListPanel().apply { add(JLabel("tag")) }); add(pane) }

        assertFalse(CommentCopyButtons.install(pane))
    }
}
