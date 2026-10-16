package pl.adrros.bettercodereview.commit

import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import pl.adrros.bettercodereview.review.files.CheckboxClicks
import pl.adrros.bettercodereview.review.files.RowSlots
import java.awt.Point
import java.awt.image.BufferedImage
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class CommitInclusionMarksTest : BasePlatformTestCase() {
    private class TestTree(project: com.intellij.openapi.project.Project) : ChangesTree(project, true, false) {
        override fun rebuildTree() {}
        fun show(model: DefaultTreeModel) = updateTreeModel(model)
    }

    private val stamps = HashMap<FilePath, CommitInclusionMarks.Stamp>()
    private lateinit var defaultStampOf: (FilePath) -> CommitInclusionMarks.Stamp?
    private var treeOrNull: TestTree? = null
    private val a = LocalFilePath("/repo/src/A.java", false)
    private val b = LocalFilePath("/repo/src/B.java", false)
    private lateinit var changeA: Change
    private lateinit var changeB: Change
    private lateinit var nodeA: ChangesBrowserNode<*>
    private lateinit var nodeB: ChangesBrowserNode<*>

    private fun revision(path: FilePath) = object : ContentRevision {
        override fun getContent(): String = ""
        override fun getFile(): FilePath = path
        override fun getRevisionNumber(): VcsRevisionNumber = VcsRevisionNumber.NULL
    }

    override fun setUp() {
        super.setUp()
        defaultStampOf = CommitInclusionMarks.stampOf
        CommitInclusionMarks.stampOf = { stamps[it] }
        stamps[a] = CommitInclusionMarks.Stamp(-1, 1, 100)
        stamps[b] = CommitInclusionMarks.Stamp(-1, 1, 100)
        changeA = Change(null, revision(a))
        changeB = Change(null, revision(b))
        nodeA = ChangesBrowserNode.createChange(project, changeA)
        nodeB = ChangesBrowserNode.createChange(project, changeB)
        val root = ChangesBrowserNode.createRoot()
        root.add(nodeA)
        root.add(nodeB)
        val tree = TestTree(project).also { treeOrNull = it }
        tree.show(DefaultTreeModel(root))
        tree.setSize(300, 120)
        tree.doLayout()
        tree.setIncludedChanges(listOf(changeA))
        CommitInclusionMarks.install(tree)
    }

    override fun tearDown() {
        try {
            treeOrNull?.let { CommitInclusionMarks.uninstall(it) }
            if (::defaultStampOf.isInitialized) CommitInclusionMarks.stampOf = defaultStampOf
        } finally {
            super.tearDown()
        }
    }

    private val tree: TestTree get() = treeOrNull!!
    private val overlay get() = tree.components.filterIsInstance<CommitInclusionMarks.InclusionOverlay>().single()

    private fun render(): BufferedImage {
        val image = BufferedImage(tree.width, tree.height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        tree.paint(g)
        g.dispose()
        return image
    }

    private fun dashPixels(image: BufferedImage, node: ChangesBrowserNode<*>): Int {
        val row = tree.getPathBounds(TreePath(node.path))!!
        val slot = RowSlots.stateSlot(tree, TreePath(node.path), tree.getRowForPath(TreePath(node.path)))!!.bounds
        val dash = RowSlots.DASH_IN_BOX.rgb and 0xFFFFFF
        return (slot.x until minOf(slot.x + slot.width, image.width)).sumOf { x -> (row.y until row.y + row.height).count { y -> (image.getRGB(x, y) and 0xFFFFFF) == dash } }
    }

    fun `test the inclusion checkbox is the slot and a ticked unchanged file has no dash`() {
        val slot = RowSlots.stateSlot(tree, TreePath(nodeA.path), 0)!!
        assertTrue("the platform's inclusion renderer puts the checkbox on the left", slot.isCheckbox && slot.bounds.x < tree.width / 2)
        val image = render()
        assertEquals(0, dashPixels(image, nodeA))
        assertEquals(0, dashPixels(image, nodeB))
    }

    fun `test a ticked file that changed since gets the dash, an unticked one never`() {
        assertEquals("baseline taken at install", 0, dashPixels(render(), nodeA))
        stamps[a] = CommitInclusionMarks.Stamp(-1, 2, 100)
        stamps[b] = CommitInclusionMarks.Stamp(-1, 2, 100)
        val image = render()
        assertTrue("dash inside A's checkbox", dashPixels(image, nodeA) > 0)
        assertEquals("B is not ticked", 0, dashPixels(image, nodeB))
    }

    fun `test the document stamp counts only when both sides had a document`() {
        val base = CommitInclusionMarks.Stamp(-1, 1, 100)
        assertFalse(CommitInclusionMarks.Stamp(42, 1, 100).changedSince(base))
        assertTrue(CommitInclusionMarks.Stamp(43, 1, 100).changedSince(CommitInclusionMarks.Stamp(42, 1, 100)))
        assertTrue(CommitInclusionMarks.Stamp(-1, 1, 101).changedSince(base))
        assertTrue(CommitInclusionMarks.Stamp(-1, 2, 100).changedSince(base))
    }

    private fun slotCentre(node: ChangesBrowserNode<*>): Point {
        val path = TreePath(node.path)
        val bounds = RowSlots.stateSlot(tree, path, tree.getRowForPath(path))!!.bounds
        return Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2)
    }

    fun `test clicking a ticked file flags it and keeps it ticked, clicking the dash goes to the platform`() {
        render()
        val decision = overlay.decide(slotCentre(nodeA))
        assertNotNull("a ticked unmarked file is ours", decision)
        assertTrue(decision!!.consume)
        decision.onClick()
        assertTrue(tree.isIncluded(changeA))
        assertTrue(overlay.isFlagged(a))
        assertTrue(dashPixels(render(), nodeA) > 0)
        assertNull("the dash click unticks through the platform", overlay.decide(slotCentre(nodeA)))
        assertNull("an unticked file ticks through the platform", overlay.decide(slotCentre(nodeB)))
        assertNull("outside the checkbox nothing is ours", overlay.decide(Point(tree.width - 5, slotCentre(nodeA).y)))
    }

    fun `test unticking drops the flag and re-ticking takes a new baseline`() {
        render()
        overlay.decide(slotCentre(nodeA))!!.onClick()
        tree.setIncludedChanges(emptyList<Change>())
        overlay.syncInclusion()
        assertFalse(overlay.isFlagged(a))
        stamps[a] = CommitInclusionMarks.Stamp(-1, 5, 500)
        tree.setIncludedChanges(listOf(changeA))
        overlay.syncInclusion()
        assertFalse("ticked again with the current content as baseline", overlay.isMarked(a))
        stamps[a] = CommitInclusionMarks.Stamp(-1, 6, 600)
        assertTrue(overlay.isMarked(a))
    }

    fun `test uninstall removes the overlay and the click handler`() {
        CommitInclusionMarks.uninstall(tree)
        assertEmpty(tree.components.filterIsInstance<CommitInclusionMarks.InclusionOverlay>())
        val press = java.awt.event.MouseEvent(tree, java.awt.event.MouseEvent.MOUSE_PRESSED, 0, 0, 5, 5, 1, false, java.awt.event.MouseEvent.BUTTON1)
        CheckboxClicks.intercept(press)
        val click = java.awt.event.MouseEvent(tree, java.awt.event.MouseEvent.MOUSE_CLICKED, 0, 0, 5, 5, 1, false, java.awt.event.MouseEvent.BUTTON1)
        CheckboxClicks.intercept(click)
        assertFalse(click.isConsumed)
        CommitInclusionMarks.install(tree)
    }
}
