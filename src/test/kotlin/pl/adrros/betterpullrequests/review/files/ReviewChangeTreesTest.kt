package pl.adrros.betterpullrequests.review.files

import com.intellij.collaboration.util.RefComparisonChange
import com.intellij.openapi.components.service
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.openapi.vcs.history.ShortVcsRevisionNumber
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.treeStructure.Tree
import java.awt.image.BufferedImage
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class ReviewChangeTreesTest : BasePlatformTestCase() {
    private val revision = object : ShortVcsRevisionNumber {
        override fun toShortString(): String = "abc"
        override fun asString(): String = "abcdef"
        override fun compareTo(other: com.intellij.openapi.vcs.history.VcsRevisionNumber): Int = 0
    }

    private fun change(after: String?, before: String? = null) =
        RefComparisonChange(revision, before?.let { LocalFilePath(it, false) }, revision, after?.let { LocalFilePath(it, false) })

    override fun tearDown() {
        try {
            project.service<CurrentReviewFile>().update(null as com.intellij.openapi.vcs.FilePath?)
        } finally {
            super.tearDown()
        }
    }

    fun `test node user objects map to the file they stand for`() {
        assertEquals(LocalFilePath("/repo/src/A.java", false), ReviewChangeTrees.pathOf(change("/repo/src/A.java")))
        assertEquals(LocalFilePath("/repo/src/Old.java", false), ReviewChangeTrees.pathOf(change(after = null, before = "/repo/src/Old.java")))
        assertEquals(LocalFilePath("/repo/x.txt", false), ReviewChangeTrees.pathOf(LocalFilePath("/repo/x.txt", false)))
        assertNull(ReviewChangeTrees.pathOf("a directory label"))
        assertNull(ReviewChangeTrees.pathOf(null))
    }

    fun `test only the collaboration tools change tree is decorated`() {
        assertFalse(ReviewChangeTrees.isReviewChangesTree(JTree()))
        assertFalse(ReviewChangeTrees.isReviewChangesTree(Tree()))
    }

    private fun treeWith(vararg nodes: DefaultMutableTreeNode): Tree {
        val root = DefaultMutableTreeNode("root")
        nodes.forEach(root::add)
        return Tree(DefaultTreeModel(root)).apply {
            isRootVisible = false
            setUI(ReviewChangeTrees.HighlightingTreeUI(project))
            setSize(300, 120)
            doLayout()
        }
    }

    private fun render(tree: JTree): BufferedImage {
        val image = BufferedImage(tree.width, tree.height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        tree.paint(g)
        g.dispose()
        return image
    }

    fun `test the row of the file open in the editor gets the purple bar and tint`() {
        val current = DefaultMutableTreeNode(change("/repo/src/Current.java"))
        val other = DefaultMutableTreeNode(change("/repo/src/Other.java"))
        val tree = treeWith(current, other)
        project.service<CurrentReviewFile>().update(LocalFilePath("/repo/src/Current.java", false))

        val image = render(tree)

        val currentRow = tree.getRowBounds(tree.getRowForPath(TreePath(current.path)))
        val otherRow = tree.getRowBounds(tree.getRowForPath(TreePath(other.path)))
        val bar = ReviewChangeTrees.HIGHLIGHT.rgb and 0xFFFFFF
        val currentMid = image.getRGB(tree.width / 2, currentRow.y + currentRow.height / 2) and 0xFFFFFF
        val otherMid = image.getRGB(tree.width / 2, otherRow.y + otherRow.height / 2) and 0xFFFFFF
        assertEquals("bar at the left edge", bar, image.getRGB(1, currentRow.y + currentRow.height / 2) and 0xFFFFFF)
        assertNotSame("tint over the row", otherMid, currentMid)
        assertTrue("tint over the row", otherMid != currentMid)
        assertTrue("other row has no bar", (image.getRGB(1, otherRow.y + otherRow.height / 2) and 0xFFFFFF) != bar)
    }

    fun `test nothing is painted without a current file`() {
        val node = DefaultMutableTreeNode(change("/repo/src/Current.java"))
        val tree = treeWith(node)
        val image = render(tree)
        val row = tree.getRowBounds(0)
        assertTrue((image.getRGB(1, row.y + row.height / 2) and 0xFFFFFF) != (ReviewChangeTrees.HIGHLIGHT.rgb and 0xFFFFFF))
    }

    fun `test the selected editor file is tracked as a file path`() {
        val file = myFixture.configureByText("tracked.txt", "x").virtualFile
        assertEquals(com.intellij.vcsUtil.VcsUtil.getFilePath(file), project.service<CurrentReviewFile>().path)
    }

    fun `test install survives a look-and-feel reset and uninstall gives the platform UI back`() {
        val tree = object : ChangesTree(project, false, false) {
            override fun rebuildTree() {}
        }
        ReviewChangeTrees.install(tree)
        assertTrue(tree.ui is ReviewChangeTrees.HighlightingTreeUI)

        tree.updateUI()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertTrue("re-applied after the look-and-feel reset", tree.ui is ReviewChangeTrees.HighlightingTreeUI)

        ReviewChangeTrees.uninstall(tree)
        assertFalse(tree.ui is ReviewChangeTrees.HighlightingTreeUI)
        assertNull(tree.getClientProperty(ReviewChangeTrees.INSTALLED))
        tree.updateUI()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertFalse("stays off after uninstall", tree.ui is ReviewChangeTrees.HighlightingTreeUI)
    }

    fun `test an installation from another plugin version steps aside`() {
        val tree = object : ChangesTree(project, false, false) {
            override fun rebuildTree() {}
        }
        ReviewChangeTrees.install(tree)
        val old = tree.getClientProperty(ReviewChangeTrees.INSTALLED) as ReviewChangeTrees.Installation
        ReviewChangeTrees.install(tree) // a newer version takes over
        assertNotSame(old, tree.getClientProperty(ReviewChangeTrees.INSTALLED))

        tree.updateUI()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertTrue(tree.ui is ReviewChangeTrees.HighlightingTreeUI)
        assertFalse("the old listener removed itself", tree.getPropertyChangeListeners("UI").contains(old.listener))
        ReviewChangeTrees.uninstall(tree)
    }
}
