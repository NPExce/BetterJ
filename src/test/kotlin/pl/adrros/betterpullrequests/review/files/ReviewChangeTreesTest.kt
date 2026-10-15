package pl.adrros.betterpullrequests.review.files

import com.intellij.collaboration.util.RefComparisonChange
import com.intellij.openapi.components.service
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.history.ShortVcsRevisionNumber
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.treeStructure.Tree
import java.awt.image.BufferedImage
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class ReviewChangeTreesTest : BasePlatformTestCase() {
    private val revision = object : ShortVcsRevisionNumber {
        override fun toShortString(): String = "abc"
        override fun asString(): String = "abcdef"
        override fun compareTo(other: com.intellij.openapi.vcs.history.VcsRevisionNumber): Int = 0
    }

    private fun change(after: String?, before: String? = null) =
        RefComparisonChange(revision, before?.let { LocalFilePath(it, false) }, revision, after?.let { LocalFilePath(it, false) })

    fun `test node user objects map to the file they stand for`() {
        assertEquals("/repo/src/A.java", ReviewChangeTrees.pathOf(change("/repo/src/A.java")))
        assertEquals("/repo/src/Old.java", ReviewChangeTrees.pathOf(change(after = null, before = "/repo/src/Old.java")))
        assertEquals("/repo/x.txt", ReviewChangeTrees.pathOf(LocalFilePath("/repo/x.txt", false)))
        assertNull(ReviewChangeTrees.pathOf("a directory label"))
        assertNull(ReviewChangeTrees.pathOf(null))
    }

    fun `test only the collaboration tools change tree is decorated`() {
        assertFalse(ReviewChangeTrees.isReviewChangesTree(JTree()))
        assertFalse(ReviewChangeTrees.isReviewChangesTree(Tree()))
    }

    fun `test the row of the file open in the editor is painted purple`() {
        val root = DefaultMutableTreeNode("root")
        val current = DefaultMutableTreeNode(change("/repo/src/Current.java"))
        val other = DefaultMutableTreeNode(change("/repo/src/Other.java"))
        root.add(current)
        root.add(other)
        val tree = Tree(DefaultTreeModel(root)).apply {
            isRootVisible = false
            setUI(ReviewChangeTrees.HighlightingTreeUI(project))
            setSize(300, 120)
            doLayout()
        }
        project.service<CurrentReviewFile>().update(myFixture.tempDirFixture.createFile("marker.txt"))
        // Point the service at the node's path directly: there is no editor for a made-up path.
        val service = project.service<CurrentReviewFile>()
        service.javaClass.getDeclaredField("path").apply { isAccessible = true }.set(service, "/repo/src/Current.java")

        val image = BufferedImage(300, 120, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        tree.paint(g)
        g.dispose()

        val currentRow = tree.getRowBounds(tree.getRowForPath(javax.swing.tree.TreePath(current.path)))
        val otherRow = tree.getRowBounds(tree.getRowForPath(javax.swing.tree.TreePath(other.path)))
        val highlight = ReviewChangeTrees.HIGHLIGHT.rgb and 0xFFFFFF
        assertEquals("current file row", highlight, image.getRGB(2, currentRow.y + currentRow.height / 2) and 0xFFFFFF)
        assertNotSame("other row", highlight, image.getRGB(2, otherRow.y + otherRow.height / 2) and 0xFFFFFF)
    }

    fun `test the selected editor file is tracked`() {
        val file = myFixture.configureByText("tracked.txt", "x").virtualFile
        assertEquals(file.path, project.service<CurrentReviewFile>().path)
    }
}
