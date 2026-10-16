package pl.adrros.bettercodereview.review.files

import com.intellij.collaboration.util.RefComparisonChange
import com.intellij.openapi.components.service
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.openapi.vcs.history.ShortVcsRevisionNumber
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

    private fun overlays(tree: JTree) = tree.components.filterIsInstance<ReviewChangeTrees.CurrentFileOverlay>()

    private fun treeWith(vararg nodes: DefaultMutableTreeNode): Tree {
        val root = DefaultMutableTreeNode("root")
        nodes.forEach(root::add)
        return Tree(DefaultTreeModel(root)).apply {
            isRootVisible = false
            setSize(300, 120)
            doLayout()
            ReviewChangeTrees.install(this, project.service<CurrentReviewFile>())
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
        assertTrue("tint over the row", otherMid != currentMid)
        assertTrue("other row has no bar", (image.getRGB(1, otherRow.y + otherRow.height / 2) and 0xFFFFFF) != bar)
    }

    fun `test the mark pulses only while a marked row is painted`() {
        val node = DefaultMutableTreeNode(change("/repo/src/Current.java"))
        val tree = treeWith(node)
        val overlay = overlays(tree).single()
        // Not showing (no window), so no animation even with a match; painting without a match stops it.
        project.service<CurrentReviewFile>().update(LocalFilePath("/repo/src/Current.java", false))
        render(tree)
        assertFalse(overlay.isPulsing)
        project.service<CurrentReviewFile>().update(null as com.intellij.openapi.vcs.FilePath?)
        render(tree)
        assertFalse(overlay.isPulsing)
    }

    fun `test files with pending comments get an amber bar and a count, beside the purple bar when current`() {
        // Files sit under a folder, as in the grouped pull request tree, so the indentation has room for the badge.
        val current = DefaultMutableTreeNode(change("/repo/src/Current.java"))
        val pendingOnly = DefaultMutableTreeNode(change("/repo/src/Other.java"))
        val plain = DefaultMutableTreeNode(change("/repo/src/Plain.java"))
        val folder = DefaultMutableTreeNode(LocalFilePath("/repo/src", true)).apply { add(current); add(pendingOnly); add(plain) }
        val tree = treeWith(folder)
        tree.expandRow(0)
        val overlay = overlays(tree).single()
        project.service<CurrentReviewFile>().update(LocalFilePath("/repo/src/Current.java", false))
        overlay.pendingMarks = PendingMarks.of("/repo", listOf("src/Current.java", "src/Other.java", "src/Other.java"))

        val image = render(tree)

        val amber = ReviewChangeTrees.PENDING.rgb and 0xFFFFFF
        val purple = ReviewChangeTrees.HIGHLIGHT.rgb and 0xFFFFFF
        val bar = com.intellij.util.ui.JBUI.scale(4)
        fun rgbAt(x: Int, node: DefaultMutableTreeNode): Int {
            val row = tree.getRowBounds(tree.getRowForPath(TreePath(node.path)))
            return image.getRGB(x, row.y + row.height / 2) and 0xFFFFFF
        }
        assertEquals("purple bar first on the current file", purple, rgbAt(1, current))
        assertEquals("amber bar right after it", amber, rgbAt(bar + 1, current))
        assertEquals("amber bar at the edge of a pending-only file", amber, rgbAt(1, pendingOnly))
        val pillRange = (2 * bar + 4 until 2 * bar + 24)
        assertTrue("a badge follows the bars", pillRange.any { rgbAt(it, pendingOnly) == amber })
        val dark = ReviewChangeTrees.PENDING_TEXT.rgb and 0xFFFFFF
        val pendingRow = tree.getRowBounds(tree.getRowForPath(TreePath(pendingOnly.path)))
        assertTrue("the badge carries dark digits", pillRange.any { x -> (-3..3).any { dy -> (image.getRGB(x, pendingRow.y + pendingRow.height / 2 + dy) and 0xFFFFFF) == dark } })
        assertTrue("plain file untouched", rgbAt(1, plain) != amber && rgbAt(1, plain) != purple)
    }

    fun `test a collapsed folder gets a light bar and an expanded one nothing`() {
        val folder = DefaultMutableTreeNode(LocalFilePath("/repo/src", true))
        folder.add(DefaultMutableTreeNode(change("/repo/src/A.java")))
        val tree = treeWith(folder)
        val overlay = overlays(tree).single()
        overlay.pendingMarks = PendingMarks.of("/repo", listOf("src/A.java"))
        tree.collapseRow(0)

        val folderRow = tree.getRowBounds(0)
        val unmarked = render(treeWith(DefaultMutableTreeNode(LocalFilePath("/repo/other", true)))).getRGB(1, folderRow.y + folderRow.height / 2) and 0xFFFFFF
        val collapsed = render(tree)
        val lightAmber = collapsed.getRGB(1, folderRow.y + folderRow.height / 2) and 0xFFFFFF
        assertTrue("collapsed folder is marked", lightAmber != unmarked)

        tree.expandRow(0)
        val expanded = render(tree)
        assertEquals("expanded folder shows nothing", unmarked, expanded.getRGB(1, folderRow.y + folderRow.height / 2) and 0xFFFFFF)
        val fileRow = tree.getRowBounds(1)
        assertEquals("its file carries the mark", ReviewChangeTrees.PENDING.rgb and 0xFFFFFF, expanded.getRGB(1, fileRow.y + fileRow.height / 2) and 0xFFFFFF)
    }

    fun `test uninstall cancels the pending-comments subscription`() {
        val tree = object : ChangesTree(project, false, false) {
            override fun rebuildTree() {}
        }
        ReviewChangeTrees.install(tree, project.service<CurrentReviewFile>())
        val overlay = overlays(tree).single()
        val job = kotlinx.coroutines.Job()
        overlay.marksJob?.cancel()
        overlay.marksJob = job
        ReviewChangeTrees.uninstall(tree)
        assertTrue(job.isCancelled)
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
        assertEquals(com.intellij.openapi.vcs.actions.VcsContextFactory.getInstance().createFilePathOn(file), project.service<CurrentReviewFile>().path)
    }

    fun `test the overlay follows the tree size, never takes clicks and goes away on uninstall`() {
        val tree = object : ChangesTree(project, false, false) {
            override fun rebuildTree() {}
        }
        tree.setSize(200, 100)
        ReviewChangeTrees.install(tree, project.service<CurrentReviewFile>())
        val overlay = tree.getClientProperty(ReviewChangeTrees.INSTALLED) as ReviewChangeTrees.CurrentFileOverlay
        assertEquals(listOf(overlay), overlays(tree))
        assertEquals(java.awt.Rectangle(0, 0, 200, 100), overlay.bounds)
        assertFalse(overlay.contains(10, 10))

        tree.setSize(400, 300)
        tree.dispatchEvent(java.awt.event.ComponentEvent(tree, java.awt.event.ComponentEvent.COMPONENT_RESIZED))
        assertEquals(java.awt.Rectangle(0, 0, 400, 300), overlay.bounds)

        tree.updateUI()
        assertEquals("a look-and-feel reset keeps the overlay", listOf(overlay), overlays(tree))

        ReviewChangeTrees.uninstall(tree)
        assertTrue(overlays(tree).isEmpty())
        assertNull(tree.getClientProperty(ReviewChangeTrees.INSTALLED))
    }

    fun `test an overlay from another plugin version is replaced`() {
        val tree = object : ChangesTree(project, false, false) {
            override fun rebuildTree() {}
        }
        ReviewChangeTrees.install(tree, project.service<CurrentReviewFile>())
        val old = overlays(tree).single()
        ReviewChangeTrees.install(tree, project.service<CurrentReviewFile>()) // a newer version takes over
        assertEquals(1, overlays(tree).size)
        assertNotSame(old, overlays(tree).single())
        ReviewChangeTrees.uninstall(tree)
    }
}
