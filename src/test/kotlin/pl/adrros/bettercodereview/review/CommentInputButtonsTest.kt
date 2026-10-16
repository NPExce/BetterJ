package pl.adrros.bettercodereview.review

import com.intellij.collaboration.ui.HorizontalListPanel
import com.intellij.collaboration.ui.VerticalListPanel
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import net.miginfocom.swing.MigLayout
import pl.adrros.bettercodereview.ReviewEditorFixtures.openReviewCommentEditor
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JLayeredPane
import javax.swing.JPanel

class CommentInputButtonsTest : BasePlatformTestCase() {
    /**
     * Mirrors the real tree around a review comment editor: data-provider wrapper, progress overlay (a layered pane),
     * the avatar panel, then `attachActions`' vertical list panel with the field and a MigLayout row whose
     * Reply / Resolve buttons have NOT arrived yet (they come from a coroutine).
     */
    private fun wrapLikeGitHub(editorComponent: JComponent): JPanel {
        val wrapper = JPanel().apply { add(editorComponent) }
        val overlay = JLayeredPane().apply { add(wrapper) }
        val withIcon = JPanel().apply { add(overlay) }
        val row = JPanel(MigLayout()).apply {
            add(HorizontalListPanel().apply { add(JLabel("hint")) })
            add(HorizontalListPanel())
        }
        return VerticalListPanel().apply {
            add(withIcon)
            add(row)
        }
    }

    fun `test buttons land at the start of the action row even before Reply arrives`() {
        myFixture.openReviewCommentEditor("<caret>")
        val editor = myFixture.editor
        val list = wrapLikeGitHub(editor.component)

        assertTrue(CommentInputButtons.install(editor))

        val row = list.getComponent(1) as JPanel
        assertSame(row, CommentInputButtons.findActionRow(editor.component))
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

    fun `test a panel that merely contains a button somewhere is not the row`() {
        myFixture.openReviewCommentEditor("<caret>")
        val outer = JPanel(MigLayout()).apply { add(javax.swing.JButton("Run")) }
        outer.add(VerticalListPanel().apply { add(JPanel().apply { add(myFixture.editor.component) }) })

        assertNull(CommentInputButtons.findActionRow(myFixture.editor.component))
    }
}
